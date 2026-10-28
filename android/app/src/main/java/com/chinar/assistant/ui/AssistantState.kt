package com.chinar.assistant.ui

import com.chinar.assistant.data.AssistantAction

enum class ListeningState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

enum class MessageSender {
    USER,
    CHINAR
}

data class ConversationMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class AssistantUiState(
    val listeningState: ListeningState = ListeningState.IDLE,
    val recognizedText: String = "",
    val assistantResponse: String = "",
    val conversationHistory: List<ConversationMessage> = emptyList(),
    val audioRms: Float = 0f,
    val errorMessage: String? = null,
    val isTtsReady: Boolean = false,
    val hasMicPermission: Boolean = false,
    val lastAction: AssistantAction? = null,
    val isSettingsOpen: Boolean = false,
    val hasConfiguredApiKey: Boolean = false,
    val isWakeWordEnabled: Boolean = false,
    val isLiveSessionActive: Boolean = false,
    val activeToolStatus: String? = null,
    val isTrustedSessionActive: Boolean = false,
    val isOwnerVoiceEnrolled: Boolean = false,
    val isOwnerOnlyVerificationEnabled: Boolean = false
)

/**
 * Tracks the hands-free conversation phase to enforce deterministic timer lifecycle
 * and prevent unwanted wakeups, repeat speech, or hung states.
 */
enum class ConversationPhase {
    STANDBY,
    AWAITING_INITIAL_REQUEST,
    USER_SPEAKING,
    AI_PROCESSING,
    AI_SPEAKING,
    AWAITING_FOLLOWUP
}

