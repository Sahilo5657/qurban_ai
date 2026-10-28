package com.chinar.assistant.agent.observation

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.chinar.assistant.accessibility.ChinarAccessibilityService
import java.util.concurrent.ConcurrentHashMap

/**
 * Traverses active Android AccessibilityNodeInfo hierarchies, extracts semantic interactive elements,
 * assigns sequential element IDs, and maintains safe node mapping for agent execution.
 */
class ScreenObserver {

    companion object {
        private const val TAG = "ScreenObserver"
        private const val MAX_TRAVERSAL_DEPTH = 20
        private const val MAX_ELEMENTS_TO_INDEX = 60

        fun inferRoleFromAttributes(
            isEditable: Boolean,
            isScrollable: Boolean,
            isClickable: Boolean,
            hasText: Boolean,
            className: String
        ): UiRole {
            val lowerClass = className.lowercase()
            return when {
                isEditable || lowerClass.contains("edittext") -> UiRole.INPUT_FIELD
                lowerClass.contains("checkbox") -> UiRole.CHECKBOX
                lowerClass.contains("switch") || lowerClass.contains("togglebutton") -> UiRole.SWITCH
                lowerClass.contains("tab") -> UiRole.TAB
                isScrollable || lowerClass.contains("scrollview") || lowerClass.contains("recyclerview") || lowerClass.contains("listview") -> UiRole.SCROLLABLE
                lowerClass.contains("button") || isClickable -> UiRole.BUTTON
                lowerClass.contains("imageview") -> UiRole.IMAGE
                hasText -> UiRole.TEXT
                else -> UiRole.UNKNOWN
            }
        }
    }

    private val activeNodeMap = ConcurrentHashMap<Int, AccessibilityNodeInfo>()

    @Volatile
    var lastScreenState: ScreenState? = null
        private set

    /**
     * Captures current active screen state from [ChinarAccessibilityService].
     */
    fun observeCurrentScreen(): ScreenState {
        val service = ChinarAccessibilityService.getInstance()
        if (service == null) {
            Log.w(TAG, "[OBSERVE_FAILED] ChinarAccessibilityService is not running/connected")
            val emptyState = ScreenState(
                packageName = "",
                windowTitle = "Accessibility Service Not Enabled",
                elements = emptyList()
            )
            lastScreenState = emptyState
            return emptyState
        }

        val rootNode = service.rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "[OBSERVE_EMPTY] rootInActiveWindow is null")
            val state = ScreenState(
                packageName = service.currentPackageName,
                windowTitle = service.currentWindowTitle,
                elements = emptyList()
            )
            lastScreenState = state
            return state
        }

        // Reset previous node map
        activeNodeMap.clear()

        val extractedElements = ArrayList<UiNode>()
        var nextId = 1

        traverseNodeHierarchy(
            node = rootNode,
            depth = 0,
            nextId = { nextId++ },
            collector = extractedElements
        )

        val state = ScreenState(
            packageName = service.currentPackageName.ifBlank { rootNode.packageName?.toString() ?: "unknown" },
            windowTitle = service.currentWindowTitle,
            elements = extractedElements
        )
        lastScreenState = state
        Log.d(TAG, "[OBSERVED_SCREEN] App: ${state.packageName}, Found ${extractedElements.size} interactive elements")
        return state
    }

    private fun traverseNodeHierarchy(
        node: AccessibilityNodeInfo,
        depth: Int,
        nextId: () -> Int,
        collector: MutableList<UiNode>
    ) {
        if (depth > MAX_TRAVERSAL_DEPTH || collector.size >= MAX_ELEMENTS_TO_INDEX) return

        if (isInteractiveOrSemantic(node)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Only index visible elements with non-zero area
            if (bounds.width() > 0 && bounds.height() > 0) {
                val elementId = nextId()
                val isPasswordField = node.isPassword ||
                    node.className?.contains("password", ignoreCase = true) == true ||
                    node.viewIdResourceName?.contains("password", ignoreCase = true) == true

                val text = if (isPasswordField) "[PROTECTED_PASSWORD]" else (node.text?.toString() ?: "")
                val desc = if (isPasswordField) "[PROTECTED_PASSWORD]" else (node.contentDescription?.toString() ?: "")
                val className = node.className?.toString() ?: ""
                val role = if (isPasswordField) UiRole.INPUT_FIELD else inferRole(node, className)

                val uiNode = UiNode(
                    id = elementId,
                    text = text,
                    contentDescription = desc,
                    className = className,
                    role = role,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isCheckable = node.isCheckable,
                    isChecked = node.isChecked,
                    isEnabled = node.isEnabled,
                    bounds = bounds,
                    resourceId = node.viewIdResourceName,
                    isPassword = isPasswordField
                )

                collector.add(uiNode)
                activeNodeMap[elementId] = node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNodeHierarchy(child, depth + 1, nextId, collector)
        }
    }

    private fun isInteractiveOrSemantic(node: AccessibilityNodeInfo): Boolean {
        // Exclude completely disabled or invisible elements
        if (!node.isVisibleToUser) return false

        // Always include actionable elements
        if (node.isClickable || node.isEditable || node.isScrollable || node.isCheckable) {
            return true
        }

        // Include text labels that have substantive human-readable content
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.isNullOrBlank() || !desc.isNullOrBlank()) {
            return true
        }

        return false
    }

    private fun inferRole(node: AccessibilityNodeInfo, className: String): UiRole {
        return inferRoleFromAttributes(
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isClickable = node.isClickable,
            hasText = node.text?.isNotBlank() == true,
            className = className
        )
    }

    /**
     * Retrieves the cached live [AccessibilityNodeInfo] by assigned [elementId].
     */
    fun getNodeInfoById(elementId: Int): AccessibilityNodeInfo? {
        return activeNodeMap[elementId]
    }

    /**
     * Resolves an element by ID or searches semantically across labels if ID is missing.
     */
    fun findElement(elementId: Int? = null, semanticQuery: String? = null): Pair<UiNode, AccessibilityNodeInfo>? {
        val state = lastScreenState ?: observeCurrentScreen()

        if (elementId != null && elementId > 0) {
            val uiNode = state.elements.firstOrNull { it.id == elementId }
            val nodeInfo = activeNodeMap[elementId]
            if (uiNode != null && nodeInfo != null) {
                return Pair(uiNode, nodeInfo)
            }
        }

        if (!semanticQuery.isNullOrBlank()) {
            val cleanQuery = semanticQuery.trim().lowercase()
            // 1. Exact match on label
            val exactMatch = state.elements.firstOrNull { it.displayLabel.lowercase() == cleanQuery }
            if (exactMatch != null) {
                val nodeInfo = activeNodeMap[exactMatch.id]
                if (nodeInfo != null) return Pair(exactMatch, nodeInfo)
            }

            // 2. Contains match
            val containsMatch = state.elements.firstOrNull {
                it.displayLabel.lowercase().contains(cleanQuery) ||
                    it.resourceId?.lowercase()?.contains(cleanQuery) == true
            }
            if (containsMatch != null) {
                val nodeInfo = activeNodeMap[containsMatch.id]
                if (nodeInfo != null) return Pair(containsMatch, nodeInfo)
            }
        }

        return null
    }

    /**
     * Clears node references to prevent holding stale window hierarchies.
     */
    fun clearCache() {
        activeNodeMap.clear()
        lastScreenState = null
    }
}
