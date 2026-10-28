package com.chinar.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.chinar.assistant.coordinator.AssistantSessionCoordinator
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for [com.chinar.assistant.MainActivity].
 *
 * Delegates state and voice session management to [AssistantSessionCoordinator],
 * ensuring seamless continuity between the Full-Screen Activity and the Floating Assistant Overlay.
 */
class AssistantViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val coordinator = AssistantSessionCoordinator.getInstance(application)

    val securePreferences get() = coordinator.securePreferences
    val uiState: StateFlow<AssistantUiState> get() = coordinator.uiState
    val toolCallDispatcher get() = coordinator.toolCallDispatcher
    val liveVoiceSession get() = coordinator.liveVoiceSession
    val conversationPhase get() = coordinator.conversationPhase
    val isTrustedSessionActive: StateFlow<Boolean> get() = coordinator.isTrustedSessionActive

    fun startFromWakeWord() = coordinator.startFromWakeWord()
    fun openSettings() = coordinator.openSettings()
    fun closeSettings() = coordinator.closeSettings()
    fun onSettingsSaved() = coordinator.onSettingsSaved()
    fun updatePermissionStatus(isGranted: Boolean) = coordinator.updatePermissionStatus(isGranted)
    fun onMicTapped() = coordinator.onMicTapped()
    fun executeTextCommand(commandText: String) = coordinator.executeTextCommand(commandText)
    fun stopSpeaking() = coordinator.stopSpeaking()
    fun stopListening() = coordinator.stopListening()
    fun clearHistory() = coordinator.clearHistory()
    fun lockTrustedSession() = coordinator.lockTrustedSession()
}
