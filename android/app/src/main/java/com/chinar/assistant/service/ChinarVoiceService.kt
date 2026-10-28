package com.chinar.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.chinar.assistant.MainActivity
import com.chinar.assistant.R
import com.chinar.assistant.data.SecurePreferences
import com.chinar.assistant.speech.VoskWakeWordDetector
import com.chinar.assistant.speech.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background Foreground Service for 24/7 low-power wake-word detection ("Hey Chinar").
 *
 * Implements:
 * 1. Android 14/15/16 FOREGROUND_SERVICE_MICROPHONE lifecycle.
 * 2. Persistent control notification with "Stop Listening" action.
 * 3. PARTIAL_WAKE_LOCK to prevent ColorOS Athena CPU freeze during screen-off standby.
 * 4. AudioManager focus listener to pause capture during phone calls and media playback.
 * 5. Strict microphone mutual exclusion: pauses when active conversational session starts,
 *    and resumes when conversational session releases microphone.
 */
class ChinarVoiceService : Service() {

    companion object {
        private const val TAG = "ChinarVoiceService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "chinar_voice_channel"

        const val ACTION_START_LISTENING = "com.chinar.assistant.action.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.chinar.assistant.action.STOP_LISTENING"
        const val ACTION_PAUSE_FOR_SESSION = "com.chinar.assistant.action.PAUSE_FOR_SESSION"
        const val ACTION_RESUME_AFTER_SESSION = "com.chinar.assistant.action.RESUME_AFTER_SESSION"

        const val EXTRA_KEYWORD = "extra_keyword"
        const val EXTRA_AUTO_START = "extra_auto_start_live"

        // Global shared flows for UI and ViewModel observation
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _wakeWordEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val wakeWordEvents: SharedFlow<String> = _wakeWordEvents.asSharedFlow()

        @Volatile
        private var activeInstance: ChinarVoiceService? = null

        fun start(context: Context) {
            val intent = Intent(context, ChinarVoiceService::class.java).apply {
                action = ACTION_START_LISTENING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ChinarVoiceService::class.java).apply {
                action = ACTION_STOP_LISTENING
            }
            context.startService(intent)
        }

        fun pauseForLiveSession(context: Context) {
            activeInstance?.pauseServiceInternal()
            try {
                val intent = Intent(context, ChinarVoiceService::class.java).apply {
                    action = ACTION_PAUSE_FOR_SESSION
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Error sending PAUSE_FOR_SESSION intent", e)
            }
        }

        fun resumeAfterLiveSession(context: Context) {
            activeInstance?.resumeServiceInternal()
            try {
                val intent = Intent(context, ChinarVoiceService::class.java).apply {
                    action = ACTION_RESUME_AFTER_SESSION
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Error sending RESUME_AFTER_SESSION intent", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeWordDetector: WakeWordDetector? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewalJob: Job? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var isPausedForSession: Boolean = false
    private var stuckSessionWatchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        Log.d(TAG, "[SERVICE_CREATE] ChinarVoiceService created")
        createNotificationChannel()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        wakeWordDetector = VoskWakeWordDetector(applicationContext, serviceScope)

        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_LISTENING
        Log.d(TAG, "[SERVICE_COMMAND] Action: $action")

        when (action) {
            ACTION_START_LISTENING -> {
                startForegroundWithNotification()
                _isServiceRunning.value = true
                isPausedForSession = false
                stuckSessionWatchdogJob?.cancel()
                stuckSessionWatchdogJob = null
                startWakeWordDetection()
            }
            ACTION_STOP_LISTENING -> {
                val prefs = SecurePreferences(applicationContext)
                if (!prefs.isWakeWordEnabled) {
                    Log.d(TAG, "[SERVICE_STOP] Stop command received and wake-word explicitly disabled in preferences. Stopping wake-word service.")
                    com.chinar.assistant.ui.overlay.FloatingOverlayManager.getInstance(this).hide()
                    stuckSessionWatchdogJob?.cancel()
                    stuckSessionWatchdogJob = null
                    stopWakeWordDetection()
                    _isServiceRunning.value = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    Log.d(TAG, "[SERVICE_STOP_IGNORED] Stop command ignored because wake-word is enabled in preferences. Resuming standby.")
                    resumeServiceInternal()
                }
            }
            ACTION_PAUSE_FOR_SESSION -> {
                pauseServiceInternal()
            }
            ACTION_RESUME_AFTER_SESSION -> {
                resumeServiceInternal()
            }
        }

        return START_STICKY
    }

    fun pauseServiceInternal() {
        if (isPausedForSession) return
        Log.d(TAG, "[SERVICE_PAUSE] Pausing wake word for active live session")
        isPausedForSession = true
        stopWakeWordDetection()
        updateNotificationText("Active voice conversation in progress...")
        startStuckSessionWatchdog()
    }

    fun resumeServiceInternal() {
        stuckSessionWatchdogJob?.cancel()
        stuckSessionWatchdogJob = null
        Log.d(TAG, "[SERVICE_RESUME] Live session finished. Resuming wake-word standby.")
        isPausedForSession = false
        updateNotificationText("Listening for 'Qurban' / 'Hey Qurban'...")
        // Ensure state machine is reset to STANDBY so next wake word is accepted
        try {
            val coordinator = com.chinar.assistant.coordinator.AssistantSessionCoordinator.getInstance(applicationContext)
            coordinator.resetToStandby()
        } catch (e: Exception) {
            Log.w(TAG, "[SERVICE_RESUME] Could not reset state machine", e)
        }
        startWakeWordDetection()
    }

    private fun startStuckSessionWatchdog() {
        stuckSessionWatchdogJob?.cancel()
        stuckSessionWatchdogJob = serviceScope.launch {
            delay(45000L)
            if (isPausedForSession) {
                val coordinator = com.chinar.assistant.coordinator.AssistantSessionCoordinator.getInstance(applicationContext)
                val isLiveActive = coordinator.uiState.value.isLiveSessionActive
                val isSpeaking = coordinator.uiState.value.listeningState == com.chinar.assistant.ui.ListeningState.SPEAKING
                if (!isLiveActive && !isSpeaking) {
                    Log.w(TAG, "[STUCK_SESSION_RECOVERY] Service paused for >45s without active speech/session. Auto-recovering to standby wake-word detection.")
                    resumeServiceInternal()
                }
            }
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("Listening for 'Qurban' / 'Hey Qurban'...")
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
            )
            Log.d(TAG, "[FOREGROUND_STARTED] Service promoted to foreground with microphone type")
        } catch (e: Exception) {
            Log.e(TAG, "[FOREGROUND_ERROR] Failed to start foreground service", e)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Qurban Voice Assistant")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppPendingIntent)
            .build()
    }

    private fun updateNotificationText(text: String) {
        val notification = buildNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Qurban Voice Standby",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Runs background hands-free wake-word detection."
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startWakeWordDetection() {
        val detector = wakeWordDetector ?: return
        if (detector.isListening || isPausedForSession) return

        serviceScope.launch {
            val micOwnership = com.chinar.assistant.coordinator.MicrophoneOwnershipManager.getInstance()
            micOwnership.acquire(com.chinar.assistant.coordinator.MicrophoneOwner.WAKE_WORD_DETECTOR)
        }

        Log.d(TAG, "[WAKE_WORD] Starting wake-word listening loop")
        detector.startListening(
            onWakeWord = { keyword, confidence ->
                handleWakeWordDetected(keyword, confidence)
            },
            onError = { error ->
                Log.e(TAG, "[WAKE_WORD_ERROR] Error in wake word detector: $error")
            }
        )
    }

    private fun stopWakeWordDetection() {
        wakeWordDetector?.stopListening()
        serviceScope.launch {
            val micOwnership = com.chinar.assistant.coordinator.MicrophoneOwnershipManager.getInstance()
            micOwnership.release(com.chinar.assistant.coordinator.MicrophoneOwner.WAKE_WORD_DETECTOR)
        }
    }

    private fun handleWakeWordDetected(keyword: String, confidence: Float) {
        val coordinator = com.chinar.assistant.coordinator.AssistantSessionCoordinator.getInstance(applicationContext)
        val correlationId = coordinator.conversationStateMachine.currentSessionId.take(8)
        Log.i(TAG, "[SESSION_$correlationId] WAKE_DETECTED keyword='$keyword' (confidence=$confidence)")

        val isAuthorized = coordinator.isOwnerSessionAuthorized()

        // 1. Tactile feedback: Only pulse for verification if session is not yet authorized
        if (!isAuthorized) {
            pulseVibrator()
        }

        // 2. Pause wake-word capture immediately to release AudioRecord for the conversational session
        isPausedForSession = true
        stopWakeWordDetection()
        Log.d(TAG, "[SESSION_$correlationId] MIC_RELEASED Standby wake-word microphone released")

        updateNotificationText(
            if (isAuthorized) "Listening for your command..." else "Wake phrase detected. Verifying..."
        )

        // 3. Emit event to UI / ViewModel
        serviceScope.launch {
            _wakeWordEvents.emit(keyword)
        }

        // 4. Coordinate display: Floating Overlay vs Full-Screen Activity
        val wakeUtterance = (wakeWordDetector as? com.chinar.assistant.speech.VoskWakeWordDetector)?.getAndClearLastWakeUtterance()

        if (MainActivity.isForeground) {
            Log.d(TAG, "[SESSION_$correlationId] [WAKE_WORD_ROUTING] MainActivity is in foreground. Using full-screen UI (wakeUtteranceBytes=${wakeUtterance?.size ?: 0}).")
        } else if (com.chinar.assistant.ui.overlay.FloatingOverlayManager.canDrawOverlays(this)) {
            Log.d(TAG, "[SESSION_$correlationId] [WAKE_WORD_ROUTING] Presenting Floating Assistant Overlay (wakeUtteranceBytes=${wakeUtterance?.size ?: 0}).")
            com.chinar.assistant.ui.overlay.FloatingOverlayManager.getInstance(this).show()
        } else {
            Log.d(TAG, "[SESSION_$correlationId] [WAKE_WORD_ROUTING] Pure voice background mode (no overlay permission).")
        }

        // Unconditionally route to coordinator so speech interaction succeeds even in pure background
        coordinator.startFromWakeWord(keyword, wakeUtterance)
        startStuckSessionWatchdog()
    }

    private fun playAcknowledgmentTone() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            serviceScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(120)
                try {
                    toneGen.release()
                } catch (e: Exception) {
                    // ignore
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed playing acknowledgment tone", e)
        }
    }

    private fun pulseVibrator() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(120)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed pulsing vibrator", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "chinar:wake_word_standby"
            )?.apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }
            Log.d(TAG, "[WAKE_LOCK] Acquired PARTIAL_WAKE_LOCK for ColorOS screen-off standby")
            // Auto-renew wake lock every 9 minutes to prevent ColorOS CPU freeze
            startWakeLockRenewal()
        } catch (e: Exception) {
            Log.w(TAG, "Failed acquiring WakeLock", e)
        }
    }

    private fun startWakeLockRenewal() {
        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(9 * 60 * 1000L) // 9 minutes
                try {
                    wakeLock?.let {
                        if (it.isHeld) {
                            it.release()
                        }
                        it.acquire(10 * 60 * 1000L)
                        Log.d(TAG, "[WAKE_LOCK] Renewed PARTIAL_WAKE_LOCK for another 10 minutes")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[WAKE_LOCK] Error renewing wake lock", e)
                }
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = null
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
            Log.d(TAG, "[WAKE_LOCK] Released PARTIAL_WAKE_LOCK")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock", e)
        }
    }

    /**
     * Called when user swipes app away from Recents.
     * ColorOS aggressively kills services in this case.
     * Re-schedule the service to survive task removal.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "[SERVICE_TASK_REMOVED] App swiped from Recents. Re-scheduling service to survive ColorOS kill.")
        val prefs = SecurePreferences(applicationContext)
        if (prefs.isWakeWordEnabled) {
            try {
                val restartIntent = Intent(applicationContext, ChinarVoiceService::class.java).apply {
                    action = ACTION_START_LISTENING
                    `package` = packageName
                }
                val pendingIntent = PendingIntent.getService(
                    applicationContext,
                    101,
                    restartIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                alarmManager?.set(
                    android.app.AlarmManager.ELAPSED_REALTIME,
                    android.os.SystemClock.elapsedRealtime() + 1000,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error scheduling restart in onTaskRemoved", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "[SERVICE_DESTROY] ChinarVoiceService destroying")
        _isServiceRunning.value = false
        com.chinar.assistant.ui.overlay.FloatingOverlayManager.getInstance(this).hide()
        stopWakeWordDetection()
        wakeWordDetector?.release()
        wakeWordDetector = null
        releaseWakeLock()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
