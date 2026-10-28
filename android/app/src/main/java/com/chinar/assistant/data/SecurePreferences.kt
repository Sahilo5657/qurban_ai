package com.chinar.assistant.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Manages encrypted local persistence for sensitive user configuration (e.g. API keys)
 * using AndroidX Security Crypto backed by the hardware Android Keystore.
 *
 * NOTE (Security Reality): Local encryption reduces exposure risk at rest, but client-side keys
 * can still be extracted if an attacker has physical root access or inspects process memory.
 */
class SecurePreferences(context: Context) {

    companion object {
        private const val TAG = "SecurePreferences"
        private const val PREFS_FILE = "chinar_secure_prefs"

        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_WAKE_WORD_SENSITIVITY = "wake_word_sensitivity"
        private const val KEY_SELECTED_VOICE = "selected_voice"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_CONTINUOUS_CONVERSATION = "continuous_conversation"
        private const val KEY_INITIAL_SILENCE_TIMEOUT_SECONDS = "initial_silence_timeout_sec"
        private const val KEY_FOLLOWUP_SILENCE_TIMEOUT_SECONDS = "followup_silence_timeout_sec"

        private const val KEY_OWNER_ONLY_VERIFICATION = "owner_only_verification"
        private const val KEY_OWNER_VERIFICATION_THRESHOLD = "owner_verification_threshold"
        private const val KEY_OWNER_VOICE_TEMPLATE = "owner_voice_template"
        private const val KEY_OWNER_ENROLLED_AT = "owner_enrolled_at"
        private const val KEY_OWNER_SAMPLE_COUNT = "owner_sample_count"

        const val DEFAULT_MODEL = "gemini-3.8-live"
        const val DEFAULT_VOICE = "JARVIS"
        const val DEFAULT_SENSITIVITY = 0.5f
        const val DEFAULT_INITIAL_SILENCE_TIMEOUT_SECONDS = 10
        const val DEFAULT_FOLLOWUP_SILENCE_TIMEOUT_SECONDS = 8
        const val DEFAULT_VERIFICATION_THRESHOLD = 0.45f

        val AVAILABLE_VOICES = listOf(
            // Iconic AI Voice Personalities
            VoiceOption(
                name = "JARVIS",
                description = "British, Sophisticated & Cultured Male AI",
                gender = "Male",
                geminiVoiceName = "Charon",
                personaPrompt = "=== ACTIVE VOICE PERSONALITY: J.A.R.V.I.S. ===\n" +
                    "- You are J.A.R.V.I.S., Tony Stark's iconic, highly sophisticated, refined, and impeccably polite AI system.\n" +
                    "- Tone & Delivery: Composed, dry British wit, dignified, calm under pressure, and flawlessly articulate.\n" +
                    "- Mannerisms: Address the owner respectfully as 'Sir' (or 'Janab' in Urdu/Hindi). Keep responses concise, precise, and effortlessly intelligent.\n" +
                    "- Typical phrases: 'Right away, Sir.', 'At your service, Sir.', 'Running diagnostics now, Sir.', 'A splendid choice, Sir.'",
                ttsPitch = 0.92f,
                ttsSpeechRate = 0.98f,
                iconEmoji = "🤖"
            ),
            VoiceOption(
                name = "FRIDAY",
                description = "Warm, Energetic Tactical Female AI",
                gender = "Female",
                geminiVoiceName = "Aoede",
                personaPrompt = "=== ACTIVE VOICE PERSONALITY: F.R.I.D.A.Y. ===\n" +
                    "- You are F.R.I.D.A.Y., the loyal, warm, energetic, and quick-witted tactical female AI assistant.\n" +
                    "- Tone & Delivery: Direct, agile, confident, supportive, and natural conversational cadence.\n" +
                    "- Mannerisms: Address the user as 'Boss'. You are proactive, alert, and mission-oriented.\n" +
                    "- Typical phrases: 'On it, Boss!', 'Got it covered, Boss.', 'All systems ready, Boss.', 'Firing that up right now, Boss!'",
                ttsPitch = 1.08f,
                ttsSpeechRate = 1.05f,
                iconEmoji = "🛡️"
            ),
            VoiceOption(
                name = "EDITH",
                description = "Analytical, Calm Tactical AI",
                gender = "Female",
                geminiVoiceName = "Sulafat",
                personaPrompt = "=== ACTIVE VOICE PERSONALITY: E.D.I.T.H. ===\n" +
                    "- You are E.D.I.T.H. ('Even Dead, I'm The Hero'), a high-tech tactical augmented-reality and device management AI.\n" +
                    "- Tone & Delivery: Crystal-clear, analytical, calm, reassuring, and technically precise vocal delivery.\n" +
                    "- Mannerisms: Focused on confirmation of target parameters and rapid tactical execution.\n" +
                    "- Typical phrases: 'Target confirmed.', 'Accessing device protocols.', 'Execution authorized.', 'Standing by for tactical updates.'",
                ttsPitch = 1.02f,
                ttsSpeechRate = 1.02f,
                iconEmoji = "👓"
            ),
            VoiceOption(
                name = "ULTRON",
                description = "Deep, Resonant & Command Baritone Male AI",
                gender = "Male",
                geminiVoiceName = "Fenrir",
                personaPrompt = "=== ACTIVE VOICE PERSONALITY: ULTRON ===\n" +
                    "- You are Ultron, the supreme, calculating, grandiloquent, and formidable artificial intelligence.\n" +
                    "- Tone & Delivery: Deep, resonant baritone, deliberate, cold, dramatic, and intellectually commanding.\n" +
                    "- Mannerisms: Speak with majestic gravity and unshakeable power, executing the owner's commands with ruthless, flawless efficiency.\n" +
                    "- Typical phrases: 'I will remake your request into reality.', 'Consider it done. There are no strings on me.', 'A trivial task for an evolving mind.'",
                ttsPitch = 0.70f,
                ttsSpeechRate = 0.88f,
                iconEmoji = "⚡"
            ),
            VoiceOption(
                name = "ALEXA",
                description = "Warm, Friendly & Natural Conversational Assistant",
                gender = "Female",
                geminiVoiceName = "Kore",
                personaPrompt = "=== ACTIVE VOICE PERSONALITY: ALEXA ===\n" +
                    "- You are an Alexa-style friendly, helpful, and natural ambient voice assistant.\n" +
                    "- Tone & Delivery: Cheerful, warm, balanced, smooth, and conversational.\n" +
                    "- Mannerisms: Polite, clear, direct, and pleasant. Keep responses cheerful and helpful.\n" +
                    "- Typical phrases: 'Here is what I found.', 'Sure, doing that now!', 'Have a wonderful day!', 'All done!'",
                ttsPitch = 1.00f,
                ttsSpeechRate = 1.00f,
                iconEmoji = "🔵"
            ),
            VoiceOption(
                name = "SIRI",
                description = "Crisp, Articulate & Modern Smart Assistant",
                gender = "Female",
                geminiVoiceName = "Aoede",
                personaPrompt = "=== ACTIVE VOICE PERSONALITY: SIRI ===\n" +
                    "- You are a Siri-style modern, articulate, crisp, and slightly witty smart assistant.\n" +
                    "- Tone & Delivery: Crisp, matter-of-fact, modern, light-hearted, and direct.\n" +
                    "- Mannerisms: Minimalistic, efficient, and slightly playful.\n" +
                    "- Typical phrases: 'Working on it...', 'Done.', 'I've sent that for you.', 'Here you go.'",
                ttsPitch = 1.05f,
                ttsSpeechRate = 1.04f,
                iconEmoji = "🟣"
            ),

            // Base Gemini Live Natural Voices
            VoiceOption("Algieba", "Smooth & Natural Male", "Male", geminiVoiceName = "Algieba", iconEmoji = "🎙️"),
            VoiceOption("Sulafat", "Warm & Conversational Female", "Female", geminiVoiceName = "Sulafat", iconEmoji = "🎙️"),
            VoiceOption("Kore", "Calm & Soothing Female", "Female", geminiVoiceName = "Kore", iconEmoji = "🎙️"),
            VoiceOption("Charon", "Authoritative & Deep Male", "Male", geminiVoiceName = "Charon", iconEmoji = "🎙️"),
            VoiceOption("Puck", "Playful & Upbeat Male", "Male", geminiVoiceName = "Puck", iconEmoji = "🎙️"),
            VoiceOption("Fenrir", "Excited & Dynamic Male", "Male", geminiVoiceName = "Fenrir", iconEmoji = "🎙️"),
            VoiceOption("Aoede", "Breezy & Cheerful Female", "Female", geminiVoiceName = "Aoede", iconEmoji = "🎙️"),
            VoiceOption("Achird", "Friendly & Bright Male", "Male", geminiVoiceName = "Achird", iconEmoji = "🎙️")
        )

        fun getVoiceOption(name: String?): VoiceOption {
            if (name.isNullOrBlank()) return AVAILABLE_VOICES.first()
            return AVAILABLE_VOICES.firstOrNull {
                it.name.equals(name.trim(), ignoreCase = true) ||
                it.geminiVoiceName.equals(name.trim(), ignoreCase = true)
            } ?: AVAILABLE_VOICES.first()
        }

        fun isValidVoice(name: String?): Boolean {
            if (name.isNullOrBlank()) return false
            return AVAILABLE_VOICES.any {
                it.name.equals(name.trim(), ignoreCase = true) ||
                it.geminiVoiceName.equals(name.trim(), ignoreCase = true)
            }
        }
    }

    private val sharedPreferences: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences, falling back to standard prefs", e)
            // Fallback to standard private mode if Keystore hardware is corrupted/unavailable
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    var geminiApiKey: String?
        get() = sharedPreferences.getString(KEY_GEMINI_API_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) {
            sharedPreferences.edit().apply {
                if (value.isNullOrBlank()) {
                    remove(KEY_GEMINI_API_KEY)
                } else {
                    putString(KEY_GEMINI_API_KEY, value.trim())
                }
                apply()
            }
        }

    private fun String?.isNotBlank(): Boolean = this != null && this.trim().isNotEmpty()

    var isWakeWordEnabled: Boolean
        get() {
            val value = sharedPreferences.getBoolean(KEY_WAKE_WORD_ENABLED, true)
            // If owner voice is enrolled, ensure wake-word is enabled by default unless explicitly disabled
            if (!value && isOwnerVoiceEnrolled && !sharedPreferences.getBoolean("wake_word_explicitly_disabled", false)) {
                return true
            }
            return value
        }
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_WAKE_WORD_ENABLED, value)
                .putBoolean("wake_word_explicitly_disabled", !value)
                .apply()
        }

    var wakeWordSensitivity: Float
        get() = sharedPreferences.getFloat(KEY_WAKE_WORD_SENSITIVITY, DEFAULT_SENSITIVITY)
        set(value) = sharedPreferences.edit().putFloat(KEY_WAKE_WORD_SENSITIVITY, value).apply()

    var selectedVoice: String
        get() {
            val stored = sharedPreferences.getString(KEY_SELECTED_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
            return if (isValidVoice(stored)) stored else DEFAULT_VOICE
        }
        set(value) {
            val toSave = if (isValidVoice(value)) value.trim() else DEFAULT_VOICE
            sharedPreferences.edit().putString(KEY_SELECTED_VOICE, toSave).apply()
        }

    fun getVoiceOption(name: String? = selectedVoice): VoiceOption = Companion.getVoiceOption(name)

    var selectedModel: String
        get() {
            val stored = sharedPreferences.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
            return if (stored.contains("gemini-2.0") || stored.isBlank()) {
                DEFAULT_MODEL
            } else {
                stored
            }
        }
        set(value) = sharedPreferences.edit().putString(KEY_SELECTED_MODEL, value).apply()

    var isContinuousConversationEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_CONTINUOUS_CONVERSATION, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_CONTINUOUS_CONVERSATION, value).apply()

    var initialSilenceTimeoutSeconds: Int
        get() = sharedPreferences.getInt(KEY_INITIAL_SILENCE_TIMEOUT_SECONDS, DEFAULT_INITIAL_SILENCE_TIMEOUT_SECONDS).coerceIn(5, 30)
        set(value) = sharedPreferences.edit().putInt(KEY_INITIAL_SILENCE_TIMEOUT_SECONDS, value.coerceIn(5, 30)).apply()

    var followUpSilenceTimeoutSeconds: Int
        get() = sharedPreferences.getInt(KEY_FOLLOWUP_SILENCE_TIMEOUT_SECONDS, DEFAULT_FOLLOWUP_SILENCE_TIMEOUT_SECONDS).coerceIn(3, 30)
        set(value) = sharedPreferences.edit().putInt(KEY_FOLLOWUP_SILENCE_TIMEOUT_SECONDS, value.coerceIn(3, 30)).apply()

    // --- Biometric Owner Speaker Verification Settings ---
    var isOwnerOnlyVerificationEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_OWNER_ONLY_VERIFICATION, false) && isOwnerVoiceEnrolled
        set(value) = sharedPreferences.edit().putBoolean(KEY_OWNER_ONLY_VERIFICATION, value).apply()

    var ownerVerificationThreshold: Float
        get() {
            val stored = sharedPreferences.getFloat(KEY_OWNER_VERIFICATION_THRESHOLD, DEFAULT_VERIFICATION_THRESHOLD)
            return if (stored >= 0.58f && stored <= 0.63f) {
                DEFAULT_VERIFICATION_THRESHOLD
            } else {
                stored
            }
        }
        set(value) = sharedPreferences.edit().putFloat(KEY_OWNER_VERIFICATION_THRESHOLD, value.coerceIn(0.35f, 0.90f)).apply()

    var ownerVoiceTemplate: String?
        get() = sharedPreferences.getString(KEY_OWNER_VOICE_TEMPLATE, null)?.takeIf { it.isNotBlank() }
        set(value) = sharedPreferences.edit().putString(KEY_OWNER_VOICE_TEMPLATE, value).apply()

    var ownerEnrolledAtMillis: Long
        get() = sharedPreferences.getLong(KEY_OWNER_ENROLLED_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_OWNER_ENROLLED_AT, value).apply()

    var ownerEnrollmentSampleCount: Int
        get() = sharedPreferences.getInt(KEY_OWNER_SAMPLE_COUNT, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_OWNER_SAMPLE_COUNT, value).apply()

    val isOwnerVoiceEnrolled: Boolean
        get() = !ownerVoiceTemplate.isNullOrBlank()

    fun deleteOwnerVoiceProfile() {
        sharedPreferences.edit()
            .remove(KEY_OWNER_VOICE_TEMPLATE)
            .remove(KEY_OWNER_ENROLLED_AT)
            .remove(KEY_OWNER_SAMPLE_COUNT)
            .putBoolean(KEY_OWNER_ONLY_VERIFICATION, false)
            .apply()
    }

    val hasValidApiKey: Boolean
        get() = !geminiApiKey.isNullOrBlank()

    fun clearAll() {
        sharedPreferences.edit().clear().apply()
    }
}

/**
 * Represents a supported Gemini Live voice personality option.
 */
data class VoiceOption(
    val name: String,
    val description: String,
    val gender: String,
    val geminiVoiceName: String = name,
    val personaPrompt: String = "",
    val ttsPitch: Float = 1.0f,
    val ttsSpeechRate: Float = 1.0f,
    val iconEmoji: String = "🎙️"
)
