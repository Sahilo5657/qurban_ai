package com.chinar.assistant

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.chinar.assistant.ui.AssistantScreen
import com.chinar.assistant.ui.AssistantViewModel

class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        var isForeground: Boolean = false
            private set
    }

    private val viewModel: AssistantViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        isForeground = true
        val prefs = com.chinar.assistant.data.SecurePreferences(this)
        android.util.Log.d("MainActivity", "[ON_RESUME] isWakeWordEnabled=${prefs.isWakeWordEnabled}, isOwnerVoiceEnrolled=${prefs.isOwnerVoiceEnrolled}")
        if (prefs.isWakeWordEnabled) {
            com.chinar.assistant.service.ChinarVoiceService.start(this)
        }
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isForeground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable display and turn screen on over lockscreen when activated via wake-word
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        handleIntent(intent)
        setContent {
            ChinarTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AssistantScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(com.chinar.assistant.service.ChinarVoiceService.EXTRA_AUTO_START, false) == true) {
            viewModel.startFromWakeWord()
        }
    }
}

@Composable
fun ChinarTheme(content: @Composable () -> Unit) {
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFF89B4FA),
        onPrimary = Color(0xFF11111B),
        secondary = Color(0xFFCBA6F7),
        onSecondary = Color(0xFF11111B),
        tertiary = Color(0xFFF9E2AF),
        onTertiary = Color(0xFF11111B),
        background = Color(0xFF1E1E2E),
        onBackground = Color(0xFFCDD6F4),
        surface = Color(0xFF181825),
        onSurface = Color(0xFFCDD6F4),
        surfaceVariant = Color(0xFF313244),
        onSurfaceVariant = Color(0xFFA6ADC8),
        error = Color(0xFFF38BA8),
        onError = Color(0xFF11111B),
        errorContainer = Color(0xFF452B35),
        onErrorContainer = Color(0xFFF38BA8),
        outline = Color(0xFF585B70),
        outlineVariant = Color(0xFF45475A)
    )

    MaterialTheme(
        colorScheme = darkColorScheme,
        content = content
    )
}
