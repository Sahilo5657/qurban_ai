package com.chinar.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.chinar.assistant.data.SecurePreferences
import com.chinar.assistant.service.ChinarVoiceService

/**
 * Ensures Chinar voice assistant automatically starts wake-word standby upon phone boot
 * or application update, behaving identically to system assistants (Alexa/Siri).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "[BOOT_EVENT] Received broadcast action: $action")

        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON"
        )

        if (action in validActions) {
            val prefs = SecurePreferences(context.applicationContext)
            if (prefs.isWakeWordEnabled) {
                Log.i(TAG, "[BOOT_START] Wake-word is enabled. Starting ChinarVoiceService in background.")
                ChinarVoiceService.start(context.applicationContext)
            } else {
                Log.d(TAG, "[BOOT_SKIP] Wake-word is disabled in user preferences.")
            }
        }
    }
}
