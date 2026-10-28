package com.chinar.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Android AccessibilityService enabling Chinar to inspect foreground application UI hierarchies,
 * semantically locate elements, navigate interfaces, perform clicks, enter text, and scroll.
 *
 * Implements:
 * 1. Safe lifecycle tracking via singleton accessor [instance].
 * 2. Active package and window state observation.
 * 3. Semantic element activation (resolving clickable ancestors).
 * 4. Text input injection via [AccessibilityNodeInfo.ACTION_SET_TEXT].
 * 5. Global system navigation ([GLOBAL_ACTION_BACK], [GLOBAL_ACTION_HOME], [GLOBAL_ACTION_RECENTS]).
 * 6. Coordinate gesture fallback for custom-rendered canvas views.
 */
class ChinarAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChinarAccessibility"

        @Volatile
        private var instance: ChinarAccessibilityService? = null

        /**
         * Returns active service instance if currently enabled and connected by user.
         */
        fun getInstance(): ChinarAccessibilityService? = instance

        /**
         * Checks whether Chinar's AccessibilityService is actively running and connected.
         */
        fun isAccessibilityRunning(): Boolean = instance != null

        /**
         * Checks whether Chinar's AccessibilityService is enabled in Android Settings
         * via active connection, AccessibilityManager list, or Settings.Secure.
         */
        fun isAccessibilityEnabled(context: Context): Boolean {
            if (instance != null) return true

            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val enabledList = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val expectedPackage = context.packageName
            val expectedClass = ChinarAccessibilityService::class.java.name

            val isPresentInManager = enabledList?.any {
                val sInfo = it.resolveInfo?.serviceInfo
                sInfo != null && sInfo.packageName == expectedPackage &&
                    (sInfo.name == expectedClass || sInfo.name == "$expectedPackage.accessibility.ChinarAccessibilityService")
            } ?: false

            if (isPresentInManager) return true

            return try {
                val enabledSetting = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val targetComponent = ComponentName(context, ChinarAccessibilityService::class.java).flattenToString()
                val targetShortComponent = ComponentName(context, ChinarAccessibilityService::class.java).flattenToShortString()

                val isGloballyEnabled = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                ) == 1

                isGloballyEnabled && enabledSetting.split(':').any {
                    it.equals(targetComponent, ignoreCase = true) || it.equals(targetShortComponent, ignoreCase = true)
                }
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Checks whether Android 13+ (API 33+) Restricted Settings may be blocking the user
         * from enabling this sideloaded/debug AccessibilityService.
         */
        fun isRestrictedSettingsBlocked(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            return try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
                val mode = appOps.checkOpNoThrow(
                    "android:access_restricted_settings",
                    android.os.Process.myUid(),
                    context.packageName
                )
                mode != AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Launches Android system Accessibility Settings screen to guide user enablement.
         */
        fun openAccessibilitySettings(context: Context): Boolean {
            return try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
                Log.d(TAG, "[SETTINGS_LAUNCH] Opened Android Accessibility Settings")
                true
            } catch (e: Exception) {
                Log.w(TAG, "[SETTINGS_ERROR] Failed to open accessibility settings, attempting general settings", e)
                try {
                    val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                    true
                } catch (e2: Exception) {
                    Log.e(TAG, "[SETTINGS_ERROR] Failed fallback to general settings", e2)
                    false
                }
            }
        }

        /**
         * Launches App Info screen for Chinar so user can tap top-right menu (⋮) -> 'Allow restricted settings'
         * on Android 13/14/15/16.
         */
        fun openAppInfoSettings(context: Context): Boolean {
            return try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.d(TAG, "[SETTINGS_LAUNCH] Opened App Info Settings for restricted settings")
                true
            } catch (e: Exception) {
                Log.e(TAG, "[SETTINGS_ERROR] Failed to open App Info settings", e)
                false
            }
        }
    }

    @Volatile
    var currentPackageName: String = ""
        private set

    @Volatile
    var currentWindowTitle: String = ""
        private set

    @Volatile
    var lastUiEventTimestampMs: Long = 0L
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "[SERVICE_CONNECTED] ChinarAccessibilityService successfully connected and active")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info
        checkVoiceServiceWatchdog()
    }

    private var lastServiceWatchdogCheckMs: Long = 0L

    private fun checkVoiceServiceWatchdog() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastServiceWatchdogCheckMs < 10000L) return
        lastServiceWatchdogCheckMs = now

        try {
            val prefs = com.chinar.assistant.data.SecurePreferences(applicationContext)
            if (prefs.isWakeWordEnabled && !com.chinar.assistant.service.ChinarVoiceService.isServiceRunning.value) {
                Log.i(TAG, "[WATCHDOG] ChinarVoiceService is not running but wake-word is enabled. Auto-reviving voice service.")
                com.chinar.assistant.service.ChinarVoiceService.start(applicationContext)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[WATCHDOG] Error in watchdog check", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        lastUiEventTimestampMs = SystemClock.elapsedRealtime()
        checkVoiceServiceWatchdog()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrBlank()) {
                    currentPackageName = pkg
                }
                val title = event.text.firstOrNull()?.toString()
                if (!title.isNullOrBlank()) {
                    currentWindowTitle = title
                }
                Log.d(TAG, "[WINDOW_STATE] App: $currentPackageName, Title: '$currentWindowTitle'")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrBlank() && currentPackageName.isBlank()) {
                    currentPackageName = pkg
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "[SERVICE_INTERRUPTED] AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "[SERVICE_DESTROYED] ChinarAccessibilityService destroyed")
    }

    /**
     * Safely triggers click action on [node].
     * If the target node is not directly clickable (e.g. an inner TextView),
     * walks up the parent chain to locate the nearest clickable ancestor.
     */
    fun performClickOnNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.isEnabled) {
                val success = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "[ACTION_CLICK] Click on ${target.className} (text='${target.text}', id='${target.viewIdResourceName}'): $success")
                return success
            }
            target = target.parent
        }

        // If no clickable ancestor found, attempt click directly on original node
        val fallbackSuccess = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, "[ACTION_CLICK_FALLBACK] Direct click on ${node.className}: $fallbackSuccess")
        return fallbackSuccess
    }

    /**
     * Enters text into an editable [node] via [AccessibilityNodeInfo.ACTION_SET_TEXT].
     */
    fun performSetTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "[ACTION_SET_TEXT] Set text on ${node.className} (text='$text'): $success")
        return success
    }

    /**
     * Executes scroll on the active screen or scrollable container.
     */
    fun performScroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollableNode = findScrollableNode(root)
        if (scrollableNode != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val success = scrollableNode.performAction(action)
            Log.d(TAG, "[ACTION_SCROLL] Scroll (forward=$forward) on ${scrollableNode.className}: $success")
            return success
        }

        // Fallback swipe gesture
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        val startY = if (forward) height * 0.75f else height * 0.25f
        val endY = if (forward) height * 0.25f else height * 0.75f
        return performGestureSwipe(width / 2f, startY, width / 2f, endY)
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Executes system global actions (Back, Home, Recents).
     */
    fun performGlobalNavAction(action: Int): Boolean {
        val success = performGlobalAction(action)
        Log.d(TAG, "[GLOBAL_ACTION] Action $action executed: $success")
        return success
    }

    /**
     * Fallback touch gesture using screen coordinates.
     */
    fun performGestureTap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var result = false
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    result = true
                    Log.d(TAG, "[GESTURE_COMPLETED] Tap at ($x, $y) succeeded")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "[GESTURE_CANCELLED] Tap at ($x, $y) cancelled")
                }
            },
            null
        )
        return dispatched
    }

    private fun performGestureSwipe(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}
