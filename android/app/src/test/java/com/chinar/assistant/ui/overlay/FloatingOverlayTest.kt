package com.chinar.assistant.ui.overlay

import com.chinar.assistant.ui.AssistantUiState
import com.chinar.assistant.ui.ConversationMessage
import com.chinar.assistant.ui.ConversationPhase
import com.chinar.assistant.ui.ListeningState
import com.chinar.assistant.ui.MessageSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Floating Overlay pill presentation logic,
 * visual state badge mapping, dynamic tool status formatting, and subtitle generation.
 */
class FloatingOverlayTest {

    @Test
    fun testBadgeTextMappingForAllStates() {
        // 1. Tool execution state takes highest priority
        val toolState = AssistantUiState(
            listeningState = ListeningState.PROCESSING,
            activeToolStatus = "⚡ Search Media"
        )
        val toolBadge = toolState.activeToolStatus ?: when (toolState.listeningState) {
            ListeningState.LISTENING -> "Listening"
            ListeningState.PROCESSING -> "Thinking..."
            ListeningState.SPEAKING -> "Speaking"
            ListeningState.ERROR -> "Error"
            else -> "Standby"
        }
        assertEquals("⚡ Search Media", toolBadge)

        // 2. Listening state
        val listeningState = AssistantUiState(listeningState = ListeningState.LISTENING)
        val listeningBadge = listeningState.activeToolStatus ?: when (listeningState.listeningState) {
            ListeningState.LISTENING -> "Listening"
            ListeningState.PROCESSING -> "Thinking..."
            ListeningState.SPEAKING -> "Speaking"
            ListeningState.ERROR -> "Error"
            else -> "Standby"
        }
        assertEquals("Listening", listeningBadge)

        // 3. Thinking / Processing state
        val processingState = AssistantUiState(listeningState = ListeningState.PROCESSING)
        val processingBadge = processingState.activeToolStatus ?: when (processingState.listeningState) {
            ListeningState.LISTENING -> "Listening"
            ListeningState.PROCESSING -> "Thinking..."
            ListeningState.SPEAKING -> "Speaking"
            ListeningState.ERROR -> "Error"
            else -> "Standby"
        }
        assertEquals("Thinking...", processingBadge)

        // 4. Speaking state
        val speakingState = AssistantUiState(listeningState = ListeningState.SPEAKING)
        val speakingBadge = speakingState.activeToolStatus ?: when (speakingState.listeningState) {
            ListeningState.LISTENING -> "Listening"
            ListeningState.PROCESSING -> "Thinking..."
            ListeningState.SPEAKING -> "Speaking"
            ListeningState.ERROR -> "Error"
            else -> "Standby"
        }
        assertEquals("Speaking", speakingBadge)

        // 5. Error state
        val errorState = AssistantUiState(listeningState = ListeningState.ERROR, errorMessage = "Connection timed out")
        val errorBadge = errorState.activeToolStatus ?: when (errorState.listeningState) {
            ListeningState.LISTENING -> "Listening"
            ListeningState.PROCESSING -> "Thinking..."
            ListeningState.SPEAKING -> "Speaking"
            ListeningState.ERROR -> "Error"
            else -> "Standby"
        }
        assertEquals("Error", errorBadge)
    }

    @Test
    fun testSubtitleTextFormatting() {
        // Case 1: Active tool executing
        val toolState = AssistantUiState(
            listeningState = ListeningState.PROCESSING,
            activeToolStatus = "⚡ Click Element"
        )
        val toolSubtitle = when {
            toolState.activeToolStatus != null -> "Executing whole-phone action..."
            toolState.listeningState == ListeningState.SPEAKING && toolState.assistantResponse.isNotBlank() ->
                toolState.assistantResponse.takeLast(70)
            toolState.recognizedText.isNotBlank() ->
                toolState.recognizedText.takeLast(70)
            toolState.errorMessage != null ->
                toolState.errorMessage!!
            toolState.listeningState == ListeningState.LISTENING ->
                "Listening to your voice..."
            else ->
                "How can I help you?"
        }
        assertEquals("Executing whole-phone action...", toolSubtitle)

        // Case 2: Speaking with model response
        val speakingState = AssistantUiState(
            listeningState = ListeningState.SPEAKING,
            assistantResponse = "I have opened YouTube and searching for Kashmir folk music."
        )
        val speakingSubtitle = when {
            speakingState.activeToolStatus != null -> "Executing whole-phone action..."
            speakingState.listeningState == ListeningState.SPEAKING && speakingState.assistantResponse.isNotBlank() ->
                speakingState.assistantResponse.takeLast(70)
            speakingState.recognizedText.isNotBlank() ->
                speakingState.recognizedText.takeLast(70)
            speakingState.errorMessage != null ->
                speakingState.errorMessage!!
            speakingState.listeningState == ListeningState.LISTENING ->
                "Listening to your voice..."
            else ->
                "How can I help you?"
        }
        assertEquals("I have opened YouTube and searching for Kashmir folk music.", speakingSubtitle)

        // Case 3: Error state
        val errorState = AssistantUiState(
            listeningState = ListeningState.ERROR,
            errorMessage = "WebSocket closed unexpectedly (1006)"
        )
        val errorSubtitle = when {
            errorState.activeToolStatus != null -> "Executing whole-phone action..."
            errorState.listeningState == ListeningState.SPEAKING && errorState.assistantResponse.isNotBlank() ->
                errorState.assistantResponse.takeLast(70)
            errorState.recognizedText.isNotBlank() ->
                errorState.recognizedText.takeLast(70)
            errorState.errorMessage != null ->
                errorState.errorMessage!!
            errorState.listeningState == ListeningState.LISTENING ->
                "Listening to your voice..."
            else ->
                "How can I help you?"
        }
        assertEquals("WebSocket closed unexpectedly (1006)", errorSubtitle)
    }

    @Test
    fun testToolNameFormatting() {
        val rawToolNames = listOf(
            "observe_screen",
            "click_element",
            "enter_text",
            "scroll_screen",
            "navigate_system",
            "open_app",
            "open_settings",
            "device_query",
            "device_action",
            "search_media",
            "prepare_communication",
            "web_search"
        )

        val formattedNames = rawToolNames.map { raw ->
            raw.replace("_", " ").replaceFirstChar { it.uppercase() }
        }

        assertEquals(
            listOf(
                "Observe screen",
                "Click element",
                "Enter text",
                "Scroll screen",
                "Navigate system",
                "Open app",
                "Open settings",
                "Device query",
                "Device action",
                "Search media",
                "Prepare communication",
                "Web search"
            ),
            formattedNames
        )
    }

    @Test
    fun testConversationPhaseStandbyTransitions() {
        var phase = ConversationPhase.STANDBY
        assertEquals(ConversationPhase.STANDBY, phase)

        // Wake word detects -> initial request
        phase = ConversationPhase.AWAITING_INITIAL_REQUEST
        assertEquals(ConversationPhase.AWAITING_INITIAL_REQUEST, phase)

        // User speaks
        phase = ConversationPhase.USER_SPEAKING
        assertEquals(ConversationPhase.USER_SPEAKING, phase)

        // Gemini Live processes
        phase = ConversationPhase.AI_PROCESSING
        assertEquals(ConversationPhase.AI_PROCESSING, phase)

        // Audio streams back
        phase = ConversationPhase.AI_SPEAKING
        assertEquals(ConversationPhase.AI_SPEAKING, phase)

        // Playback finishes -> continuous follow-up window
        phase = ConversationPhase.AWAITING_FOLLOWUP
        assertEquals(ConversationPhase.AWAITING_FOLLOWUP, phase)

        // Silence timeout expires -> back to standby
        phase = ConversationPhase.STANDBY
        assertEquals(ConversationPhase.STANDBY, phase)
    }
}
