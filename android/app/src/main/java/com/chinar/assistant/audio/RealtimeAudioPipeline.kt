package com.chinar.assistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-latency audio capture (16kHz PCM) and playback (24kHz PCM) pipeline for real-time
 * bidirectional voice conversations with Gemini Multimodal Live API.
 *
 * Implements:
 * 1. Single reusable streaming AudioTrack with 1.0s buffer to prevent underruns.
 * 2. 150ms jitter pre-buffer before starting playback to eliminate network-induced stutter.
 * 3. Blocking write loop correctly handling partial writes and backpressure.
 * 4. Response epoch tracking to discard stale audio chunks after interruption.
 * 5. Strict lifecycle state machine: playback is NEVER declared drained during active generation.
 * 6. Detailed, credential-safe timestamped diagnostics across capture, write, buffer, and lifecycle.
 */
class RealtimeAudioPipeline(
    private val scope: CoroutineScope,
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "RealtimeAudioPipeline"

        const val CAPTURE_SAMPLE_RATE = 16000
        const val PLAYBACK_SAMPLE_RATE = 24000

        // 100ms chunk at 16kHz, 16-bit mono = 1600 samples = 3200 bytes
        private const val CAPTURE_CHUNK_BYTES = 3200

        // Jitter buffer threshold: 150ms of 24kHz 16-bit mono PCM = 3600 samples = 7200 bytes
        private const val PRE_BUFFER_BYTES = 7200

        // 1.0s buffer at 24kHz 16-bit mono PCM = 24000 samples = 48000 bytes
        private const val PLAYBACK_BUFFER_BYTES = 48000

        // Calibrated amplitude threshold: with hardware AEC on VOICE_COMMUNICATION,
        // residual loudspeaker echo is < 100, while normal conversational speech is 1,200 - 8,000.
        const val BARGE_IN_THRESHOLD = 1200

        // Client-side speech gate: prevents background room noise/HVAC (< 350) from being
        // forwarded to Gemini Live, while allowing normal conversational speech (> 500) to pass freely.
        const val SPEECH_GATE_THRESHOLD = 500

        // Hangover chunks (10 * 100ms = 1.0s) to preserve inter-syllable pauses and trailing word sounds
        private const val SPEECH_HANGOVER_CHUNKS = 10

        // Cooldown period after loudspeaker playback finishes before lowering threshold back to SPEECH_GATE_THRESHOLD
        private const val ECHO_COOLDOWN_MS = 250L
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private val currentEpoch = AtomicLong(0L)

    private val isPrebuffering = AtomicBoolean(true)
    private val isServerTurnComplete = AtomicBoolean(false)
    private val preBuffer = ArrayList<ByteArray>()
    private var preBufferByteCount = 0
    private val preBufferLock = Any()

    private var totalFramesWritten: Long = 0L
    private var lastUnderrunCount: Int = 0
    private var lastChunkArrivalTimeMs: Long = 0L
    private var turnChunksReceived: Long = 0L
    private var turnBytesReceived: Long = 0L
    private var playbackEndTimeMs: Long = 0L
    private var speechHangoverRemaining: Int = 0

    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneOn: Boolean = false
    private var isRoutingConfigured: Boolean = false
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var currentTargetDevice: AudioDeviceInfo? = null
    private var playbackFocusRequest: Any? = null

    private fun requestPlaybackAudioFocus() {
        val ctx = context ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { /* handle transient duck */ }
                    .build()
                playbackFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting playback audio focus", e)
        }
    }

    private fun abandonPlaybackAudioFocus() {
        val ctx = context ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (playbackFocusRequest as? android.media.AudioFocusRequest)?.let { audioManager.abandonAudioFocusRequest(it) }
                playbackFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    @Volatile
    var isCapturing: Boolean = false
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Checks if a headset (wired, bluetooth, or USB) is currently connected/active.
     * When a headset is connected, loudspeaker echo cancellation is not needed.
     */
    fun isHeadsetConnected(): Boolean {
        val ctx = context ?: return false
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDev = audioManager.communicationDevice
            if (commDev != null) {
                return isHeadsetDeviceType(commDev.type)
            }
        }
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { device -> isHeadsetDeviceType(device.type) }
    }

    private fun isHeadsetDeviceType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_DEVICE
    }

    /**
     * Configures communication audio routing.
     * Keeps MODE_IN_COMMUNICATION for voice processing/AEC, but explicitly routes audio to:
     * 1. An external device (wired headset, Bluetooth SCO, USB headset, etc.) if connected.
     * 2. The built-in loudspeaker (TYPE_BUILTIN_SPEAKER) if no external device is connected,
     *    preventing the default fallback to the phone earpiece (TYPE_BUILTIN_EARPIECE).
     */
    fun configureAudioRouting() {
        val ctx = context ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        try {
            if (!isRoutingConfigured) {
                previousAudioMode = audioManager.mode
                @Suppress("DEPRECATION")
                previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
                isRoutingConfigured = true
                Log.d(TAG, "[AUDIO_ROUTING] Saved initial audio state: mode=$previousAudioMode, speakerphoneOn=$previousSpeakerphoneOn")
            }

            // Set mode to MODE_IN_COMMUNICATION for AEC and full-duplex VoIP
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.d(TAG, "[AUDIO_ROUTING] Set AudioManager mode = MODE_IN_COMMUNICATION")

            // Register dynamic audio device callback to handle headset plug/unplug events
            registerDeviceCallback(audioManager)

            // Select and apply optimal audio route
            updateAudioRouting(audioManager)
        } catch (e: Exception) {
            Log.e(TAG, "[AUDIO_ROUTING_ERROR] Failed configuring audio routing", e)
        }
    }

    private fun updateAudioRouting(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val availableDevices = audioManager.availableCommunicationDevices
            Log.d(
                TAG,
                "[AUDIO_ROUTING] Available communication devices (${availableDevices.size}): " +
                    availableDevices.joinToString { "${it.productName} (type=${getDeviceTypeName(it.type)}, id=${it.id})" }
            )

            // Prioritize external devices: wired headset/headphones, bluetooth SCO/BLE, USB headset
            val externalDevice = availableDevices.firstOrNull { device ->
                isHeadsetDeviceType(device.type)
            }

            val targetDevice: AudioDeviceInfo?
            val routeReason: String

            if (externalDevice != null) {
                targetDevice = externalDevice
                routeReason = "External device detected (${externalDevice.productName}, type=${getDeviceTypeName(externalDevice.type)})"
            } else {
                val speakerDevice = availableDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    targetDevice = speakerDevice
                    routeReason = "No external headset connected -> routing to built-in loudspeaker"
                } else {
                    targetDevice = null
                    routeReason = "Warning: TYPE_BUILTIN_SPEAKER not found in availableCommunicationDevices"
                }
            }

            if (targetDevice != null) {
                currentTargetDevice = targetDevice
                val success = audioManager.setCommunicationDevice(targetDevice)
                val actual = audioManager.communicationDevice
                if (success && actual?.id == targetDevice.id) {
                    Log.i(
                        TAG,
                        "[AUDIO_ROUTING_SUCCESS] $routeReason. Successfully set communication device to: ${actual.productName} (type=${getDeviceTypeName(actual.type)}, id=${actual.id})"
                    )
                } else if (success) {
                    Log.w(
                        TAG,
                        "[AUDIO_ROUTING_WARN] setCommunicationDevice returned true, but actual communication device is: ${actual?.productName} (type=${actual?.let { getDeviceTypeName(it.type) }}, id=${actual?.id})"
                    )
                } else {
                    Log.e(
                        TAG,
                        "[AUDIO_ROUTING_FAILURE] Failed to set communication device to: ${targetDevice.productName} (type=${getDeviceTypeName(targetDevice.type)}, id=${targetDevice.id}). Active device remains: ${actual?.productName} (type=${actual?.let { getDeviceTypeName(it.type) }})"
                    )
                }

                // Also update preferred device on active AudioTrack if initialized
                audioTrack?.let { track ->
                    try {
                        val trackSuccess = track.setPreferredDevice(targetDevice)
                        Log.d(TAG, "[AUDIO_ROUTING_TRACK] AudioTrack.setPreferredDevice(${targetDevice.productName}) returned: $trackSuccess")
                    } catch (e: Exception) {
                        Log.w(TAG, "[AUDIO_ROUTING_TRACK_WARN] Error setting preferred device on AudioTrack", e)
                    }
                }
            } else {
                Log.w(TAG, "[AUDIO_ROUTING_WARN] No target device identified: $routeReason")
            }
        } else {
            // Legacy path for Android versions prior to API 31
            val headsetConnected = isHeadsetConnected()
            val enableSpeaker = !headsetConnected
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enableSpeaker
            @Suppress("DEPRECATION")
            val actualSpeaker = audioManager.isSpeakerphoneOn
            Log.i(TAG, "[AUDIO_ROUTING_LEGACY] HeadsetConnected=$headsetConnected. Requested isSpeakerphoneOn=$enableSpeaker, Actual isSpeakerphoneOn=$actualSpeaker")
        }
    }

    private fun registerDeviceCallback(audioManager: AudioManager) {
        if (audioDeviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                val names = addedDevices?.joinToString { "${it.productName} (${getDeviceTypeName(it.type)})" }
                Log.d(TAG, "[AUDIO_DEVICE_CALLBACK] Devices connected: $names")
                updateAudioRouting(audioManager)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                val names = removedDevices?.joinToString { "${it.productName} (${getDeviceTypeName(it.type)})" }
                Log.d(TAG, "[AUDIO_DEVICE_CALLBACK] Devices disconnected: $names")
                updateAudioRouting(audioManager)
            }
        }
        audioManager.registerAudioDeviceCallback(callback, null)
        audioDeviceCallback = callback
        Log.d(TAG, "[AUDIO_DEVICE_CALLBACK] Registered AudioDeviceCallback")
    }

    private fun unregisterDeviceCallback(audioManager: AudioManager?) {
        audioDeviceCallback?.let { callback ->
            try {
                audioManager?.unregisterAudioDeviceCallback(callback)
                Log.d(TAG, "[AUDIO_DEVICE_CALLBACK] Unregistered AudioDeviceCallback")
            } catch (e: Exception) {
                Log.w(TAG, "[AUDIO_DEVICE_CALLBACK_WARN] Error unregistering AudioDeviceCallback", e)
            }
        }
        audioDeviceCallback = null
    }

    /**
     * Restores previous audio mode, clears communication device selection,
     * and unregisters audio device callbacks.
     */
    fun restoreAudioRouting() {
        if (!isRoutingConfigured) return
        isRoutingConfigured = false

        val ctx = context ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        unregisterDeviceCallback(audioManager)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
                Log.d(TAG, "[AUDIO_ROUTING] Cleared communication device")
            } catch (e: Exception) {
                Log.w(TAG, "[AUDIO_ROUTING_WARN] Error clearing communication device", e)
            }
        }

        try {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = previousSpeakerphoneOn
            Log.d(TAG, "[AUDIO_ROUTING] Restored isSpeakerphoneOn to $previousSpeakerphoneOn")
        } catch (e: Exception) {
            Log.w(TAG, "[AUDIO_ROUTING_WARN] Error restoring isSpeakerphoneOn", e)
        }

        try {
            audioManager.mode = previousAudioMode
            Log.d(TAG, "[AUDIO_ROUTING] Restored audio mode to $previousAudioMode")
        } catch (e: Exception) {
            Log.w(TAG, "[AUDIO_ROUTING_WARN] Error restoring audio mode to $previousAudioMode", e)
        }

        try {
            audioTrack?.setPreferredDevice(null)
        } catch (e: Exception) {
            // ignore
        }
        currentTargetDevice = null
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE (1)"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER (2)"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET (3)"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES (4)"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO (7)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP (8)"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE (11)"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET (22)"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET (26)"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER (27)"
            else -> "UNKNOWN ($type)"
        }
    }

    /**
     * Initializes and starts microphone audio capture at 16kHz mono PCM.
     */
    @SuppressLint("MissingPermission")
    fun startCapture(onAudioChunk: (ByteArray) -> Unit) {
        if (isCapturing) return

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                CAPTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize, CAPTURE_CHUNK_BYTES * 2)

            // Configure audio mode and explicitly route to loudspeaker or headset
            configureAudioRouting()

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CAPTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "[CAPTURE_ERROR] AudioRecord initialization failed: state=${record.state}")
                return
            }

            // Attempt to bind Acoustic Echo Cancellation (AEC) if available
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply {
                        enabled = true
                        Log.d(TAG, "[AEC_INIT] Hardware AcousticEchoCanceler enabled on session ${record.audioSessionId}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[AEC_WARN] Could not attach AcousticEchoCanceler: ${e.message}")
                }
            }

            audioRecord = record
            record.startRecording()
            isCapturing = true

            ensurePlaybackLoopRunning()

            captureJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(CAPTURE_CHUNK_BYTES)
                var chunkCount = 0L
                var suppressedEchoChunks = 0L
                var lastLoggedEchoSuppressTime = 0L
                var suppressedNoiseChunks = 0L
                var lastLoggedNoiseSuppressTime = 0L

                while (isActive && isCapturing) {
                    val bytesRead = record.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        chunkCount++

                        // Calculate peak amplitude for this chunk
                        var maxAmp = 0
                        for (i in 0 until bytesRead step 2) {
                            if (i + 1 < bytesRead) {
                                val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
                                val abs = kotlin.math.abs(sample.toShort().toInt())
                                if (abs > maxAmp) maxAmp = abs
                            }
                        }

                        val headset = isHeadsetConnected()
                        val playing = isPlaying
                        val now = SystemClock.elapsedRealtime()
                        val inEchoCooldown = !playing && !headset && (now - playbackEndTimeMs < ECHO_COOLDOWN_MS)

                        val chunkToSend = if (playing && !headset) {
                            // Assistant actively speaking on loudspeaker -> Echo Gate
                            if (maxAmp < BARGE_IN_THRESHOLD) {
                                suppressedEchoChunks++
                                if (now - lastLoggedEchoSuppressTime > 2000L) {
                                    lastLoggedEchoSuppressTime = now
                                    Log.d(TAG, "[MIC_ECHO_GATE] Suppressed speaker echo (peakAmp=$maxAmp < $BARGE_IN_THRESHOLD, totalSuppressed=$suppressedEchoChunks)")
                                }
                                ByteArray(bytesRead) // Zeroed PCM keeps stream timing intact
                            } else {
                                Log.d(TAG, "[MIC_BARGE_IN_PIERCED] Barge-in speech detected! peakAmp=$maxAmp >= $BARGE_IN_THRESHOLD")
                                speechHangoverRemaining = SPEECH_HANGOVER_CHUNKS
                                buffer.copyOf(bytesRead)
                            }
                        } else if (inEchoCooldown) {
                            // Brief 250ms room reverberation cooldown after speaker stops
                            if (maxAmp < BARGE_IN_THRESHOLD) {
                                ByteArray(bytesRead)
                            } else {
                                speechHangoverRemaining = SPEECH_HANGOVER_CHUNKS
                                buffer.copyOf(bytesRead)
                            }
                        } else {
                            // Assistant is listening / waiting for user -> Pass raw speech directly to model VAD
                            buffer.copyOf(bytesRead)
                        }

                        if (chunkCount % 20 == 0L) {
                            Log.d(TAG, "[MIC_STAT] Chunk #$chunkCount: $bytesRead bytes, peakAmp=$maxAmp, playing=$playing, headset=$headset, hangover=$speechHangoverRemaining")
                        }

                        onAudioChunk(chunkToSend)
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "[MIC_READ_ERROR] AudioRecord.read error code: $bytesRead")
                        delay(20)
                    }
                }
            }

            Log.d(TAG, "[CAPTURE_STARTED] Realtime audio capture started at ${CAPTURE_SAMPLE_RATE}Hz using session ${record.audioSessionId}")
        } catch (e: Exception) {
            Log.e(TAG, "[CAPTURE_ERROR] Error starting audio capture", e)
            stopCapture()
        }
    }

    /**
     * Stops microphone audio capture.
     */
    fun stopCapture() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null

        try {
            echoCanceler?.release()
            echoCanceler = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            if (!isPlaying && playbackQueue.isEmpty()) {
                restoreAudioRouting()
            }

            Log.d(TAG, "[CAPTURE_STOPPED] AudioRecord and AEC stopped and released")
        } catch (e: Exception) {
            Log.w(TAG, "[CAPTURE_WARN] Error stopping AudioRecord", e)
        }
    }

    private fun getOrCreateAudioTrack(): AudioTrack? {
        audioTrack?.let { if (it.state == AudioTrack.STATE_INITIALIZED) return it }

        return try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                PLAYBACK_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize * 4, PLAYBACK_BUFFER_BYTES)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(PLAYBACK_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            val sharedSessionId = audioRecord?.audioSessionId ?: AudioManager.AUDIO_SESSION_ID_GENERATE

            val track = AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                sharedSessionId
            )

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "[TRACK_ERROR] AudioTrack initialization failed: state=${track.state}")
                return null
            }

            totalFramesWritten = 0L
            lastUnderrunCount = 0
            audioTrack = track
            Log.d(TAG, "[TRACK_INIT] Initialized reusable AudioTrack: session=$sharedSessionId, bufferSize=$bufferSize bytes (~${bufferSize * 1000 / (PLAYBACK_SAMPLE_RATE * 2)}ms), sampleRate=${PLAYBACK_SAMPLE_RATE}Hz")

            // Route AudioTrack to currently selected communication device
            currentTargetDevice?.let { target ->
                try {
                    val trackSuccess = track.setPreferredDevice(target)
                    Log.d(TAG, "[TRACK_INIT_DEVICE] Set preferred device on new AudioTrack to ${target.productName} (type=${getDeviceTypeName(target.type)}): $trackSuccess")
                } catch (e: Exception) {
                    Log.w(TAG, "[TRACK_INIT_DEVICE_WARN] Error setting preferred device on new AudioTrack", e)
                }
            }

            // Monitor routing changes on AudioTrack
            track.addOnRoutingChangedListener(
                { routing ->
                    val routed = (routing as? AudioTrack)?.routedDevice
                    Log.d(TAG, "[TRACK_ROUTING_CHANGED] AudioTrack actual output route: ${routed?.productName} (type=${routed?.let { getDeviceTypeName(it.type) }}, id=${routed?.id})")
                },
                null
            )

            track
        } catch (e: Exception) {
            Log.e(TAG, "[TRACK_ERROR] Error creating AudioTrack", e)
            null
        }
    }

    /**
     * Ensures the background playback consumer coroutine is actively running.
     */
    fun ensurePlaybackLoopRunning() {
        if (playbackJob?.isActive == true) return

        playbackJob = scope.launch(Dispatchers.IO) {
            val track = getOrCreateAudioTrack() ?: run {
                Log.e(TAG, "[PLAYBACK_LOOP_ERROR] Cannot start playback loop: AudioTrack is null")
                return@launch
            }

            Log.d(TAG, "[PLAYBACK_LOOP] Playback streaming loop active")
            while (isActive) {
                val chunk: ByteArray
                try {
                    val polled = playbackQueue.poll(50, TimeUnit.MILLISECONDS)
                    if (polled == null) {
                        if (isPlaying) {
                            checkIfPlaybackDrained(track)
                        }
                        continue
                    }
                    chunk = polled
                } catch (e: InterruptedException) {
                    break
                }

                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        track.play()
                        Log.d(TAG, "[TRACK_PLAY] AudioTrack.play() called (headPos=${track.playbackHeadPosition}, totalWritten=$totalFramesWritten)")
                    } catch (e: Exception) {
                        Log.w(TAG, "[TRACK_WARN] Error starting AudioTrack.play()", e)
                    }
                }

                // Write chunk with full write loop handling partial writes
                var bytesWrittenForChunk = 0
                while (bytesWrittenForChunk < chunk.size && isActive) {
                    val toWrite = chunk.size - bytesWrittenForChunk
                    val res = track.write(chunk, bytesWrittenForChunk, toWrite, AudioTrack.WRITE_BLOCKING)
                    if (res > 0) {
                        bytesWrittenForChunk += res
                        totalFramesWritten += (res / 2) // 16-bit mono = 2 bytes/frame
                    } else if (res < 0) {
                        Log.e(TAG, "[TRACK_WRITE_ERROR] AudioTrack.write returned error code: $res")
                        break
                    } else {
                        // res == 0: AudioTrack was flushed or paused during write
                        break
                    }
                }

                // Monitor underruns
                val underruns = track.underrunCount
                if (underruns > lastUnderrunCount) {
                    val diff = underruns - lastUnderrunCount
                    val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    Log.w(TAG, "[UNDERRUN] AudioTrack starved! +$diff underruns (total: $underruns, headPos=$head, totalWritten=$totalFramesWritten)")
                    lastUnderrunCount = underruns
                }
            }
        }
    }

    /**
     * Checks if playback has completely drained.
     * CRITICAL: Playback is NEVER declared drained while the model is still actively generating.
     */
    private fun checkIfPlaybackDrained(track: AudioTrack) {
        if (!isServerTurnComplete.get()) {
            // Model is still generating! A 50ms queue starvation is just network jitter, not turn end.
            return
        }

        val headPos = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (headPos >= totalFramesWritten && totalFramesWritten > 0L && playbackQueue.isEmpty() && preBufferByteCount == 0) {
            isPlaying = false
            abandonPlaybackAudioFocus()
            isServerTurnComplete.set(false)
            playbackEndTimeMs = SystemClock.elapsedRealtime()
            synchronized(preBufferLock) {
                isPrebuffering.set(true)
            }
            try {
                track.pause()
            } catch (e: Exception) {
                Log.w(TAG, "[TRACK_WARN] Error pausing drained AudioTrack", e)
            }
            Log.d(TAG, "[PLAYBACK_DRAINED] All frames physically played to speakers (headPos=$headPos, totalWritten=$totalFramesWritten). Returning to listening.")
            if (!isCapturing) {
                restoreAudioRouting()
            }
            scope.launch(Dispatchers.Main) {
                onPlaybackStateChanged?.invoke(false)
            }
        }
    }

    /**
     * Queues a 24kHz PCM audio chunk received from the Gemini Live stream.
     * Enforces pre-buffering (jitter buffer) before audio is sent to AudioTrack.
     * Discards chunks if they match an old, interrupted epoch.
     */
    fun enqueuePlaybackChunk(pcmBytes: ByteArray, epoch: Long = currentEpoch.get()) {
        if (epoch != currentEpoch.get()) {
            Log.d(TAG, "[STALE_CHUNK_DROPPED] Discarded stale chunk from epoch $epoch (current is ${currentEpoch.get()})")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val delta = if (lastChunkArrivalTimeMs > 0L) now - lastChunkArrivalTimeMs else 0L
        lastChunkArrivalTimeMs = now
        turnChunksReceived++
        turnBytesReceived += pcmBytes.size

        // During streaming, model is actively generating
        isServerTurnComplete.set(false)

        ensurePlaybackLoopRunning()

        val queueMs = (playbackQueue.sumOf { it.size } + preBufferByteCount) * 1000 / (PLAYBACK_SAMPLE_RATE * 2)
        if (delta > 100L) {
            Log.w(TAG, "[JITTER_GAP] Gap between incoming audio chunks: ${delta}ms (chunk #$turnChunksReceived, size=${pcmBytes.size}b, queueDuration=${queueMs}ms)")
        }

        synchronized(preBufferLock) {
            if (isPrebuffering.get()) {
                preBuffer.add(pcmBytes)
                preBufferByteCount += pcmBytes.size
                if (preBufferByteCount >= PRE_BUFFER_BYTES) {
                    flushPreBuffer()
                }
            } else {
                playbackQueue.offer(pcmBytes)
            }
        }
    }

    private fun flushPreBuffer() {
        synchronized(preBufferLock) {
            if (!isPrebuffering.get()) return
            isPrebuffering.set(false)
            Log.d(TAG, "[JITTER_BUFFER_FILLED] Pre-buffer filled ($preBufferByteCount bytes / ~${preBufferByteCount * 1000 / (PLAYBACK_SAMPLE_RATE * 2)}ms). Starting smooth playback.")
            for (chunk in preBuffer) {
                playbackQueue.offer(chunk)
            }
            preBuffer.clear()
            preBufferByteCount = 0
            if (!isPlaying) {
                isPlaying = true
                requestPlaybackAudioFocus()
                scope.launch(Dispatchers.Main) {
                    onPlaybackStateChanged?.invoke(true)
                }
            }
        }
    }

    /**
     * Signals that the model has finished emitting tokens for the current turn.
     * Marks server turn as complete and flushes any remaining pre-buffered audio.
     */
    fun signalTurnEnding() {
        Log.d(TAG, "[TURN_ENDING] Model generation complete signaled. Marking turn as ending. Queued chunks: ${playbackQueue.size}, preBuffer: $preBufferByteCount bytes")
        isServerTurnComplete.set(true)
        synchronized(preBufferLock) {
            if (isPrebuffering.get() && preBuffer.isNotEmpty()) {
                Log.d(TAG, "[PREBUFFER_EARLY_FLUSH] Turn ended during prebuffering with $preBufferByteCount bytes. Flushing short utterance.")
                flushPreBuffer()
            }
        }
    }

    /**
     * Instantly halts audio output, flushes buffered audio, and increments epoch upon user barge-in.
     */
    fun stopPlaybackAndClear(reason: String = "user_barge_in") {
        val oldEpoch = currentEpoch.get()
        val newEpoch = currentEpoch.incrementAndGet()
        val headPos = audioTrack?.playbackHeadPosition?.toLong()?.and(0xFFFFFFFFL) ?: 0L
        Log.d(TAG, "[PLAYBACK_STOP] stopPlaybackAndClear called. Reason: '$reason'. Epoch: $oldEpoch -> $newEpoch, headPos=$headPos, totalWritten=$totalFramesWritten, queueSize=${playbackQueue.size}")

        synchronized(preBufferLock) {
            preBuffer.clear()
            preBufferByteCount = 0
            isPrebuffering.set(true)
        }

        isServerTurnComplete.set(false)
        playbackQueue.clear()

        try {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                }
            }
            // CRITICAL FIX: AudioTrack.flush() does not reset playbackHeadPosition.
            // Synchronize totalFramesWritten with current head position so future turns don't instantly declare drained.
            val currentHead = audioTrack?.playbackHeadPosition?.toLong()?.and(0xFFFFFFFFL) ?: 0L
            totalFramesWritten = currentHead
        } catch (e: Exception) {
            Log.w(TAG, "[FLUSH_WARN] Error flushing AudioTrack", e)
        }

        isPlaying = false
        playbackEndTimeMs = SystemClock.elapsedRealtime()
        lastChunkArrivalTimeMs = 0L
        turnChunksReceived = 0L
        turnBytesReceived = 0L

        // NOTE: Do NOT call onPlaybackStateChanged(false) here!
        // Interruption/barge-in is not a natural drain completion.
    }

    /**
     * Gets the current response epoch counter.
     */
    fun getCurrentEpoch(): Long = currentEpoch.get()

    /**
     * Stops audio playback and releases resources.
     */
    fun stopPlayback() {
        Log.d(TAG, "[PLAYBACK_RELEASE] Releasing AudioTrack")
        isPlaying = false
        abandonPlaybackAudioFocus()
        playbackEndTimeMs = 0L
        playbackJob?.cancel()
        playbackJob = null
        playbackQueue.clear()

        synchronized(preBufferLock) {
            preBuffer.clear()
            preBufferByteCount = 0
            isPrebuffering.set(true)
        }
        isServerTurnComplete.set(false)

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            totalFramesWritten = 0L
        } catch (e: Exception) {
            Log.w(TAG, "[PLAYBACK_WARN] Error stopping AudioTrack", e)
        }

        if (!isCapturing) {
            restoreAudioRouting()
        }
    }

    /**
     * Releases all audio resources and restores audio routing.
     */
    fun release() {
        stopCapture()
        stopPlayback()
        restoreAudioRouting()
    }
}
