package com.chinar.assistant.data

import android.content.Context
import android.util.Log

data class VoiceEnrollmentDetails(
    val isEnrolled: Boolean,
    val enrolledAtMillis: Long,
    val sampleCount: Int,
    val ownerName: String = "Sahil Khan",
    val dimension: Int = 128
)

/**
 * Manages the on-device storage and lifecycle of the enrolled owner's voice biometric template.
 *
 * Backed by Keystore-encrypted SecurePreferences. Raw audio recordings are never stored;
 * only the averaged 128-dimensional Kaldi x-vector embedding is retained.
 *
 * Privacy Guarantees:
 * 1. Biometric template is never transmitted off-device or shared with cloud services.
 * 2. User has explicit controls to delete or re-enroll their voice profile at any time.
 * 3. Separation of Concerns: Biometric template is strictly decoupled from the owner's biography/persona.
 */
class OwnerVoiceProfileStore(context: Context) {

    companion object {
        private const val TAG = "VoiceProfileStore"
        const val DEFAULT_OWNER_NAME = "Sahil Khan"
    }

    private val securePreferences = SecurePreferences(context)

    /**
     * Checks whether an owner voice profile is currently enrolled.
     */
    val isOwnerEnrolled: Boolean
        get() = securePreferences.isOwnerVoiceEnrolled

    /**
     * Retrieves the stored 128-d owner template vector, or null if not enrolled.
     */
    fun getOwnerTemplate(): FloatArray? {
        val serialized = securePreferences.ownerVoiceTemplate ?: return null
        return deserializeVector(serialized)
    }

    /**
     * Saves the averaged owner template vector and records enrollment metadata.
     */
    fun saveOwnerTemplate(
        template: FloatArray,
        sampleCount: Int = 3,
        ownerName: String = DEFAULT_OWNER_NAME
    ): Boolean {
        if (template.isEmpty()) {
            Log.e(TAG, "Cannot save empty biometric template")
            return false
        }

        val serialized = serializeVector(template)
        securePreferences.ownerVoiceTemplate = serialized
        securePreferences.ownerEnrolledAtMillis = System.currentTimeMillis()
        securePreferences.ownerEnrollmentSampleCount = sampleCount
        // Automatically enable owner-only verification and wake-word upon successful enrollment
        securePreferences.isOwnerOnlyVerificationEnabled = true
        securePreferences.isWakeWordEnabled = true

        Log.d(TAG, "[VOICE_PROFILE_SAVED] Saved enrolled owner template ($sampleCount samples, ${template.size}-d vector) for $ownerName")
        return true
    }

    /**
     * Retrieves high-level enrollment details without exposing the raw biometric vector.
     */
    fun getEnrollmentDetails(): VoiceEnrollmentDetails {
        val isEnrolled = securePreferences.isOwnerVoiceEnrolled
        return VoiceEnrollmentDetails(
            isEnrolled = isEnrolled,
            enrolledAtMillis = securePreferences.ownerEnrolledAtMillis,
            sampleCount = securePreferences.ownerEnrollmentSampleCount,
            ownerName = DEFAULT_OWNER_NAME,
            dimension = if (isEnrolled) 128 else 0
        )
    }

    /**
     * Explicitly deletes the stored biometric voice template and disables owner-only gate.
     */
    fun deleteOwnerProfile() {
        securePreferences.deleteOwnerVoiceProfile()
        Log.d(TAG, "[VOICE_PROFILE_DELETED] Biometric template removed from encrypted storage")
    }

    private fun serializeVector(vector: FloatArray): String {
        return vector.joinToString(",") { it.toString() }
    }

    private fun deserializeVector(serialized: String): FloatArray? {
        return try {
            val parts = serialized.split(",")
            if (parts.isEmpty()) return null
            FloatArray(parts.size) { i -> parts[i].toFloat() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed deserializing biometric vector", e)
            null
        }
    }
}
