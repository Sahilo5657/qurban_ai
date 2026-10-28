package com.chinar.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class AndroidTtsEngine(
    private val context: Context,
    private val onInitComplete: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AndroidTtsEngine"
        private const val OPLUS_TTS_ENGINE = "com.oplus.ttsaccessibilityengine"
        private const val MAX_CHUNK_LENGTH = 120

        /**
         * Splits text into natural speech chunks (sentences and clauses) across English, Urdu,
         * Pashto, and Hindi so TTS can start playing the first chunk with near-zero latency.
         */
        fun splitIntoSpeechChunks(text: String, maxChunkLength: Int = MAX_CHUNK_LENGTH): List<String> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()

            // 1. Split by major sentence terminators: . ! ? ; \n and Urdu/Arabic/Hindi marks (۔ ؟ ।)
            val sentenceRegex = Regex("""(?<=[.!?;\n۔؟।])\s+""")
            val sentences = trimmed.split(sentenceRegex).map { it.trim() }.filter { it.isNotEmpty() }

            val chunks = mutableListOf<String>()

            for (sentence in sentences) {
                if (sentence.length <= maxChunkLength) {
                    chunks.add(sentence)
                } else {
                    // For longer sentences, split at natural clause pauses: comma, colon, dash, Urdu comma (،)
                    val clauseRegex = Regex("""(?<=[,:\-—،])\s+""")
                    val clauses = sentence.split(clauseRegex).map { it.trim() }.filter { it.isNotEmpty() }

                    var current = StringBuilder()
                    for (clause in clauses) {
                        if (current.isNotEmpty() && current.length + clause.length + 1 > maxChunkLength) {
                            chunks.add(current.toString().trim())
                            current = StringBuilder()
                        }
                        if (current.isNotEmpty()) current.append(" ")
                        current.append(clause)
                    }
                    if (current.isNotEmpty()) {
                        chunks.add(current.toString().trim())
                    }
                }
            }

            return if (chunks.isEmpty()) listOf(trimmed) else chunks
        }
    }

    /**
     * Local LRU and filesystem audio cache for instant playback of frequent short responses.
     */
    class TtsAudioCache(private val context: Context) {
        private val cacheDir = File(context.cacheDir, "tts_audio_cache").apply { mkdirs() }
        private val memCache = java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, File>(50, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, File>?): Boolean = size > 50
            }
        )

        fun get(text: String, voiceName: String, locale: Locale): File? {
            val key = hashKey(text, voiceName, locale)
            val inMem = memCache[key]
            if (inMem != null && inMem.exists() && inMem.length() > 0) return inMem
            val onDisk = File(cacheDir, "$key.wav")
            if (onDisk.exists() && onDisk.length() > 0) {
                memCache[key] = onDisk
                return onDisk
            }
            return null
        }

        fun getFileForWriting(text: String, voiceName: String, locale: Locale): File {
            val key = hashKey(text, voiceName, locale)
            return File(cacheDir, "$key.wav")
        }

        fun put(text: String, voiceName: String, locale: Locale, file: File) {
            if (!file.exists() || file.length() == 0L) return
            val key = hashKey(text, voiceName, locale)
            memCache[key] = file
        }

        private fun hashKey(text: String, voiceName: String, locale: Locale): String {
            val raw = "${text.trim().lowercase(Locale.ROOT)}_#_${voiceName.lowercase(Locale.ROOT)}_#_${locale.language}"
            return try {
                val md = MessageDigest.getInstance("MD5")
                md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                raw.hashCode().toString()
            }
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var focusRequest: Any? = null // AudioFocusRequest on API 26+

    private var tts: TextToSpeech? = null
    private var isInitialized: Boolean = false
    private var triedFallback: Boolean = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var activeUtteranceId: String? = null
    private var onSpeechDoneCallback: (() -> Unit)? = null
    private var playbackWatchdogJob: Job? = null

    private val audioCache by lazy { TtsAudioCache(context.applicationContext) }
    private var cachedAudioPlayer: MediaPlayer? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
            setupProgressListener()
        } catch (e: Exception) {
            Log.e(TAG, "Initial TextToSpeech constructor exception", e)
            tryFallbackEngine()
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS onStart: $utteranceId")
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS onDone: $utteranceId (activeFinal=$activeUtteranceId)")
                if (utteranceId != null && utteranceId == activeUtteranceId) {
                    finishPlayback(utteranceId)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "TTS onError: $utteranceId")
                finishPlayback(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "TTS onError: $utteranceId, code: $errorCode")
                finishPlayback(utteranceId)
            }
        })
    }

    private fun finishPlayback(utteranceId: String?) {
        playbackWatchdogJob?.cancel()
        playbackWatchdogJob = null
        _isSpeaking.value = false
        abandonAudioFocus()

        if (utteranceId != null && utteranceId == activeUtteranceId) {
            val cb = onSpeechDoneCallback
            onSpeechDoneCallback = null
            activeUtteranceId = null
            cb?.invoke()
        } else if (activeUtteranceId != null) {
            val cb = onSpeechDoneCallback
            onSpeechDoneCallback = null
            activeUtteranceId = null
            cb?.invoke()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            configureLanguageAndFinish()
        } else {
            Log.w(TAG, "TTS default onInit failed with status $status")
            if (!triedFallback) {
                tryFallbackEngine()
            } else {
                Log.e(TAG, "Both default and fallback TTS initialization failed")
                isInitialized = false
                onInitComplete(false)
            }
        }
    }

    private fun tryFallbackEngine() {
        triedFallback = true
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down prior TTS instance", e)
        }

        try {
            Log.d(TAG, "Attempting init with OEM engine: $OPLUS_TTS_ENGINE")
            tts = TextToSpeech(context.applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    configureLanguageAndFinish()
                } else {
                    Log.e(TAG, "OEM TTS engine onInit failed with status: $status")
                    isInitialized = false
                    onInitComplete(false)
                }
            }, OPLUS_TTS_ENGINE)
            setupProgressListener()
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating OEM TextToSpeech instance", e)
            isInitialized = false
            onInitComplete(false)
        }
    }

    private fun configureLanguageAndFinish() {
        val locale = Locale.getDefault()
        var result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fallback to English US
            result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Default and US languages report missing data or not supported")
            }
        }

        // Configure AudioAttributes for foreground service & background assistant audible output
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)
            Log.d(TAG, "Configured TTS AudioAttributes with USAGE_MEDIA (stream=STREAM_MUSIC)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not setAudioAttributes on TTS", e)
        }

        isInitialized = true
        Log.d(TAG, "TTS initialized successfully with voice: ${tts?.voice?.name ?: "default"}")
        onInitComplete(true)
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "TTS Audio focus changed: $focusChange")
                    }
                    .build()
                focusRequest = req
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed requesting audio focus for TTS", e)
            false
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (focusRequest as? AudioFocusRequest)?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    fun resolveTargetLocale(text: String, voiceName: String): Locale {
        return when {
            text.any { it in listOf('پ', 'چ', 'ژ', 'ښ', 'ګ', 'ڼ', 'ې', 'ۍ') } -> Locale("ps")
            text.any { it in '\u0600'..'\u06FF' } -> Locale("ur", "PK")
            text.any { it in '\u0900'..'\u097F' } -> Locale("hi", "IN")
            else -> {
                if (voiceName.equals("JARVIS", ignoreCase = true)) Locale.UK else Locale.ENGLISH
            }
        }
    }

    private fun playCachedAudio(file: File, onDone: () -> Unit) {
        requestAudioFocus()
        try {
            cachedAudioPlayer?.release()
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp ->
                    mp.release()
                    cachedAudioPlayer = null
                    _isSpeaking.value = false
                    abandonAudioFocus()
                    onDone()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.w(TAG, "[TTS_CACHE_PLAYER_ERROR] Error $what/$extra playing cached audio")
                    mp.release()
                    cachedAudioPlayer = null
                    _isSpeaking.value = false
                    abandonAudioFocus()
                    onDone()
                    true
                }
                prepare()
                start()
            }
            cachedAudioPlayer = player
            _isSpeaking.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing cached audio file", e)
            abandonAudioFocus()
            onDone()
        }
    }

    /**
     * Speaks text with reduced latency:
     * 1. Checks on-device audio cache for common short phrases (<10ms playback).
     * 2. Otherwise splits text into natural chunks (sentences/clauses).
     * 3. Plays chunk 0 immediately with QUEUE_FLUSH for instant speech start.
     * 4. Queues remaining chunks seamlessly with QUEUE_ADD while chunk 0 is speaking.
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "Cannot speak: TTS not initialized")
            onDone()
            return
        }

        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onDone()
            return
        }

        stop()
        requestAudioFocus()

        val chunks = splitIntoSpeechChunks(trimmed)
        if (chunks.isEmpty()) {
            onDone()
            return
        }

        val securePreferences = com.chinar.assistant.data.SecurePreferences(context)
        val activeVoice = com.chinar.assistant.data.SecurePreferences.getVoiceOption(securePreferences.selectedVoice)
        val targetLocale = resolveTargetLocale(trimmed, activeVoice.name)

        // Check if single short phrase is already cached on disk for instantaneous (<10ms) playback
        if (chunks.size == 1 && chunks.first().length < 80) {
            val cachedFile = audioCache.get(chunks.first(), activeVoice.name, targetLocale)
            if (cachedFile != null) {
                Log.d(TAG, "[TTS_CACHE_HIT] Instant playback from cache for '${chunks.first().take(30)}' (${cachedFile.length()}B)")
                playCachedAudio(cachedFile, onDone)
                return
            }
        }

        try {
            tts?.setPitch(activeVoice.ttsPitch)
            tts?.setSpeechRate(activeVoice.ttsSpeechRate)
            tts?.language = targetLocale
        } catch (e: Exception) {
            // graceful fallback
        }

        val sessionId = UUID.randomUUID().toString()
        val finalChunkId = "${sessionId}_chunk_${chunks.size - 1}"
        activeUtteranceId = finalChunkId
        onSpeechDoneCallback = onDone

        val timeoutMs = maxOf(6000L, trimmed.length * 120L)
        playbackWatchdogJob = coroutineScope.launch {
            delay(timeoutMs)
            if (activeUtteranceId == finalChunkId) {
                Log.w(TAG, "[TTS_WATCHDOG] Utterance $finalChunkId timed out after ${timeoutMs}ms without completion callback. Forcing finish.")
                finishPlayback(finalChunkId)
            }
        }

        Log.d(TAG, "[TTS_CHUNK_PIPELINE] Speaking ${chunks.size} chunk(s) (totalLen=${trimmed.length}): first='${chunks.first().take(40)}'")

        for (index in chunks.indices) {
            val chunk = chunks[index]
            val chunkId = "${sessionId}_chunk_$index"
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, chunkId)
            }

            // Chunk 0 uses QUEUE_FLUSH to immediately start audio without waiting.
            // Chunks 1..N-1 use QUEUE_ADD to pre-synthesize in background while previous chunk speaks.
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val speakResult = tts?.speak(chunk, queueMode, params, chunkId)
            if (speakResult != TextToSpeech.SUCCESS) {
                Log.e(TAG, "[TTS_ERROR] speak() failed on chunk $index: result=$speakResult")
                if (index == 0) {
                    finishPlayback(finalChunkId)
                    return
                }
            }

            // Background pre-cache for frequent short chunks (< 80 chars)
            if (chunk.length < 80 && audioCache.get(chunk, activeVoice.name, targetLocale) == null) {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val cacheFile = audioCache.getFileForWriting(chunk, activeVoice.name, targetLocale)
                        val cacheBundle = Bundle().apply {
                            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "cache_${UUID.randomUUID()}")
                        }
                        tts?.synthesizeToFile(chunk, cacheBundle, cacheFile, "cache_${UUID.randomUUID()}")
                        audioCache.put(chunk, activeVoice.name, targetLocale, cacheFile)
                    } catch (e: Exception) {
                        // ignore caching error
                    }
                }
            }
        }
    }

    fun stop() {
        try {
            playbackWatchdogJob?.cancel()
            playbackWatchdogJob = null
            cachedAudioPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            cachedAudioPlayer = null
            if (_isSpeaking.value) {
                tts?.stop()
                _isSpeaking.value = false
            }
            abandonAudioFocus()
            onSpeechDoneCallback = null
            activeUtteranceId = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS", e)
        }
    }

    fun destroy() {
        stop()
        try {
            cachedAudioPlayer?.release()
            cachedAudioPlayer = null
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS", e)
        } finally {
            tts = null
            isInitialized = false
        }
    }
}
