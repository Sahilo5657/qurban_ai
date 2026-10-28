package com.chinar.assistant.agent.actions

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import com.chinar.assistant.actions.FlashlightAction
import com.chinar.assistant.data.ActionResult

/**
 * Native Layer A device controls providing battery state, audio volume,
 * system settings navigation, and hardware flashlight operation.
 */
class DeviceControls(
    private val context: Context,
    private val flashlightAction: FlashlightAction = FlashlightAction(context)
) {

    companion object {
        private const val TAG = "DeviceControls"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Queries current battery percentage and charging status.
     */
    fun getBatteryStatus(): ActionResult {
        val batteryStatusIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return ActionResult(false, "Could not determine battery status.")

        val level = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val status = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = batteryStatusIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val plugType = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_USB -> "via USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "via AC charger"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wirelessly"
            else -> ""
        }

        val feedback = buildString {
            append("Battery is at $percent%.")
            if (isCharging) {
                append(" It is currently charging")
                if (plugType.isNotBlank()) append(" $plugType")
                append(".")
            }
        }

        Log.d(TAG, "[BATTERY_QUERY] $feedback")
        return ActionResult(
            success = true,
            message = feedback,
            details = mapOf(
                "percentage" to percent.toString(),
                "isCharging" to isCharging.toString()
            )
        )
    }

    /**
     * Controls or queries media volume.
     */
    fun controlVolume(direction: String? = null, levelPercent: Int? = null): ActionResult {
        val am = audioManager ?: return ActionResult(false, "Audio service unavailable.")

        val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)

        if (levelPercent != null) {
            val clamped = levelPercent.coerceIn(0, 100)
            val targetIndex = (clamped * maxVolume) / 100
            am.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, AudioManager.FLAG_SHOW_UI)
            return ActionResult(true, "Set volume to $clamped%.")
        }

        when (direction?.lowercase()) {
            "up", "increase", "higher" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                val newVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val newPct = (newVol * 100) / maxVolume
                return ActionResult(true, "Increased volume to $newPct%.")
            }
            "down", "decrease", "lower" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                val newVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val newPct = (newVol * 100) / maxVolume
                return ActionResult(true, "Decreased volume to $newPct%.")
            }
            "mute" -> {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                return ActionResult(true, "Muted media volume.")
            }
            "status", "query", null -> {
                val pct = (currentVolume * 100) / maxVolume
                return ActionResult(true, "Media volume is currently at $pct%.", mapOf("percentage" to pct.toString()))
            }
            else -> {
                return ActionResult(false, "Unknown volume direction: $direction")
            }
        }
    }

    /**
     * Navigates directly to a target Android system settings page.
     */
    fun openSettingsPage(pageName: String?): ActionResult {
        val action = when (pageName?.lowercase()?.trim()) {
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "wifi", "wi-fi", "internet", "network" -> Settings.ACTION_WIFI_SETTINGS
            "display", "screen", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "audio", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery", "power" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "applications", "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "security", "privacy" -> Settings.ACTION_SECURITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            Log.d(TAG, "[SETTINGS_OPENED] Opened settings for: '$pageName' ($action)")
            ActionResult(true, "Opened ${pageName ?: "System"} Settings.")
        } catch (e: Exception) {
            Log.e(TAG, "[SETTINGS_ERROR] Failed opening settings for $pageName", e)
            ActionResult(false, "Failed to open settings: ${e.localizedMessage}")
        }
    }

    /**
     * Toggles device flashlight.
     */
    suspend fun toggleFlashlight(state: String?): ActionResult {
        val params = state?.let { mapOf("state" to it) } ?: emptyMap()
        return flashlightAction.execute(params)
    }
}
