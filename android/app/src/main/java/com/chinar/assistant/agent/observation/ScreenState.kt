package com.chinar.assistant.agent.observation

/**
 * Immutable snapshot of the active on-screen state captured by [ScreenObserver].
 */
data class ScreenState(
    val packageName: String,
    val windowTitle: String,
    val elements: List<UiNode>,
    val timestampMs: Long = System.currentTimeMillis()
) {
    /**
     * Formats a concise, token-efficient summary of the current screen for Gemini Live reasoning.
     */
    fun toPromptSummary(maxElements: Int = 40): String {
        val appName = packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        val header = buildString {
            append("App: $appName ($packageName)")
            if (windowTitle.isNotBlank()) append(" | Window: \"$windowTitle\"")
            append("\nInteractive UI Elements (${elements.size} found):")
        }

        if (elements.isEmpty()) {
            return "$header\n(No interactive accessibility elements visible on screen)"
        }

        val lines = elements.take(maxElements).map { it.toSummaryLine() }
        val truncatedNotice = if (elements.size > maxElements) {
            "\n... and ${elements.size - maxElements} more elements"
        } else {
            ""
        }
        return header + "\n" + lines.joinToString("\n") + truncatedNotice
    }
}
