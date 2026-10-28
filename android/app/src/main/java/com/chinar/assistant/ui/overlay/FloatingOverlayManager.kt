package com.chinar.assistant.ui.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.chinar.assistant.MainActivity
import com.chinar.assistant.ChinarTheme
import com.chinar.assistant.coordinator.AssistantSessionCoordinator

/**
 * Manages the floating WindowManager overlay lifecycle for Chinar.
 *
 * Displays a non-intrusive floating voice pill over active third-party apps
 * when wake-word triggers in background or hands-free mode.
 */
class FloatingOverlayManager private constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "FloatingOverlayManager"

        @Volatile
        private var instance: FloatingOverlayManager? = null

        fun getInstance(context: Context): FloatingOverlayManager {
            return instance ?: synchronized(this) {
                instance ?: FloatingOverlayManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    fun show(): Boolean {
        if (!canDrawOverlays(context)) {
            Log.w(TAG, "[OVERLAY_REJECTED] SYSTEM_ALERT_WINDOW permission is not granted.")
            return false
        }

        if (isShowing) {
            Log.d(TAG, "[OVERLAY_ALREADY_ACTIVE] Overlay is already showing.")
            return true
        }

        mainHandler.post {
            try {
                if (isShowing || windowManager == null) return@post

                val owner = OverlayLifecycleOwner().apply {
                    onCreate()
                    onStart()
                }
                lifecycleOwner = owner

                val coordinator = AssistantSessionCoordinator.getInstance(context)

                val composeView = ComposeView(context).apply {
                    setViewTreeLifecycleOwner(owner)
                    setViewTreeSavedStateRegistryOwner(owner)
                    setViewTreeViewModelStoreOwner(owner)

                    setContent {
                        ChinarTheme {
                            FloatingOverlayPill(
                                coordinator = coordinator,
                                onDismiss = {
                                    hide()
                                    coordinator.stopListening()
                                },
                                onExpand = {
                                    hide()
                                    val intent = Intent(context, MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    }
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                }

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 48
                }

                windowManager.addView(composeView, layoutParams)
                overlayView = composeView
                isShowing = true
                Log.d(TAG, "[OVERLAY_ATTACHED] Floating Assistant Overlay successfully displayed over foreground screen.")
            } catch (e: Exception) {
                Log.e(TAG, "[OVERLAY_ERROR] Failed displaying overlay view", e)
                isShowing = false
                lifecycleOwner?.onDestroy()
                lifecycleOwner = null
                overlayView = null
            }
        }

        return true
    }

    fun hide() {
        if (!isShowing && overlayView == null) return

        mainHandler.post {
            try {
                overlayView?.let { view ->
                    windowManager?.removeView(view)
                    Log.d(TAG, "[OVERLAY_REMOVED] Floating Assistant Overlay detached.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[OVERLAY_REMOVE_ERROR] Error removing overlay view", e)
            } finally {
                lifecycleOwner?.onDestroy()
                lifecycleOwner = null
                overlayView = null
                isShowing = false
            }
        }
    }
}
