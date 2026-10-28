package com.chinar.assistant.ai

import com.chinar.assistant.ui.ConversationMessage
import kotlinx.coroutines.flow.Flow

/**
 * Architectural interface for Chinar's conversational AI reasoning engine.
 *
 * Decouples conversation handling and tool decision-making from specific cloud or local LLM backends.
 */
interface AiReasoningEngine {

    /**
     * Whether the engine is configured and ready to communicate (e.g. valid API key present).
     */
    val isConfigured: Boolean

    /**
     * Sends a user query along with conversation history and returns a complete text response.
     */
    suspend fun generateResponse(
        prompt: String,
        history: List<ConversationMessage>
    ): Result<String>

    /**
     * Streams the model's text response token-by-token.
     */
    fun streamResponse(
        prompt: String,
        history: List<ConversationMessage>
    ): Flow<String>
}
