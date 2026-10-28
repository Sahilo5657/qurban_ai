package com.chinar.assistant.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Local file-based store for Sahil Khan's personal assistant profile.
 *
 * Persisted in private app storage (`owner_profile.json`).
 * Strictly separated from biometric voice templates.
 * Provides full read, edit, reset-to-default, and delete operations.
 */
class OwnerProfileStore(private val context: Context) {

    companion object {
        private const val TAG = "OwnerProfileStore"
        private const val PROFILE_FILE_NAME = "owner_profile.json"
    }

    private val profileFile: File
        get() = File(context.filesDir, PROFILE_FILE_NAME)

    @Synchronized
    fun getProfile(): OwnerProfile {
        return try {
            if (!profileFile.exists()) {
                val defaultProfile = OwnerProfile()
                saveProfile(defaultProfile)
                return defaultProfile
            }

            val content = profileFile.readText()
            if (content.isBlank()) {
                return OwnerProfile()
            }
            OwnerProfile.fromJson(JSONObject(content))
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading owner profile from disk, returning defaults", e)
            OwnerProfile()
        }
    }

    @Synchronized
    fun saveProfile(profile: OwnerProfile): Boolean {
        return try {
            val jsonStr = profile.toJson().toString(2)
            profileFile.writeText(jsonStr)
            Log.d(TAG, "[PROFILE_SAVED] Owner profile updated on disk (${profileFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving owner profile to disk", e)
            false
        }
    }

    @Synchronized
    fun resetToDefault(): OwnerProfile {
        val defaultProfile = OwnerProfile()
        saveProfile(defaultProfile)
        Log.d(TAG, "[PROFILE_RESET] Reset owner profile to default values")
        return defaultProfile
    }

    @Synchronized
    fun deleteProfile(): Boolean {
        return try {
            if (profileFile.exists()) {
                val deleted = profileFile.delete()
                Log.d(TAG, "[PROFILE_DELETED] Profile file deleted: $deleted")
                deleted
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed deleting owner profile file", e)
            false
        }
    }
}
