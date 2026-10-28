package com.chinar.assistant.agent.observation

import android.graphics.Rect

/**
 * Compact, bounded semantic representation of an interactive on-screen UI element.
 */
data class UiNode(
    val id: Int,
    val text: String,
    val contentDescription: String,
    val className: String,
    val role: UiRole,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isEnabled: Boolean,
    val bounds: Rect,
    val resourceId: String? = null,
    val isPassword: Boolean = false
) {
    /**
     * Primary human-readable label: visible text preferred, falling back to accessibility contentDescription.
     */
    val displayLabel: String
        get() = if (isPassword) "[PROTECTED_PASSWORD]" else if (text.isNotBlank()) text.trim() else contentDescription.trim()

    fun toSummaryLine(): String {
        val idPart = resourceId?.substringAfterLast("/")?.let { " [id:$it]" } ?: ""
        if (isPassword) {
            return "[$id] input_field \"[PROTECTED_PASSWORD]\" (password field)$idPart"
        }
        val label = displayLabel
        val typeDesc = role.name.lowercase()
        val extra = when {
            isEditable -> " (editable)"
            isCheckable -> if (isChecked) " (checked)" else " (unchecked)"
            else -> ""
        }
        return "[$id] $typeDesc \"$label\"$extra$idPart"
    }
}

/**
 * Categorized UI roles inferred from widget class names, accessibility attributes, and actions.
 */
enum class UiRole {
    BUTTON,
    INPUT_FIELD,
    TAB,
    TEXT,
    CHECKBOX,
    SWITCH,
    IMAGE,
    CONTAINER,
    SCROLLABLE,
    UNKNOWN
}
