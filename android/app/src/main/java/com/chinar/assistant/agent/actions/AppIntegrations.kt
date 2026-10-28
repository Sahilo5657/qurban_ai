package com.chinar.assistant.agent.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.chinar.assistant.accessibility.ChinarAccessibilityService
import com.chinar.assistant.agent.observation.ScreenObserver
import com.chinar.assistant.data.ActionResult
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Layer B App Integrations providing deep-linking, fast WhatsApp automation,
 * chat conversation handling, media search (YouTube), and safe communication preparation.
 */
class AppIntegrations(
    private val context: Context,
    private val screenObserver: ScreenObserver = ScreenObserver()
) {

    companion object {
        private const val TAG = "AppIntegrations"
    }

    /**
     * Sends a WhatsApp message in a single fast, smooth, automated command.
     * Supports both direct phone numbers (via deep-link) and contact names (via accessibility).
     */
    suspend fun sendWhatsAppMessage(
        recipient: String,
        message: String,
        autoSend: Boolean = true
    ): ActionResult {
        val cleanRecipient = recipient.trim()
        val cleanMessage = message.trim()

        if (cleanRecipient.isBlank()) {
            return ActionResult(false, "Please specify a contact name or phone number for WhatsApp.")
        }
        if (cleanMessage.isBlank()) {
            return ActionResult(false, "Please specify the message text to send.")
        }

        // 1. If recipient is a direct phone number (>= 7 digits)
        val digitsOnly = cleanRecipient.filter { it.isDigit() }
        if (digitsOnly.length >= 7) {
            val cleanPhone = cleanRecipient.replace(Regex("[^0-9+]"), "")
            val encodedText = URLEncoder.encode(cleanMessage, StandardCharsets.UTF_8.toString())
            val waUri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedText")
            val directIntent = Intent(Intent.ACTION_VIEW, waUri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(directIntent)
                if (autoSend) {
                    delay(800)
                    val service = ChinarAccessibilityService.getInstance()
                    if (service != null) {
                        findWhatsAppSendButton(service)?.let { service.performClickOnNode(it) }
                    }
                }
                return ActionResult(true, "Sent WhatsApp message to $cleanRecipient: \"$cleanMessage\"")
            } catch (e: Exception) {
                Log.w(TAG, "Direct WhatsApp URI failed, falling back to app launch", e)
            }
        }

        // 2. Contact name automation via AccessibilityService
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.whatsapp")
            ?: return ActionResult(false, "WhatsApp is not installed on this device.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(launchIntent)

        val service = ChinarAccessibilityService.getInstance()
            ?: return ActionResult(true, "Opened WhatsApp. Enable Accessibility Service for automatic typing.")

        // Wait for WhatsApp to come into foreground
        var poll = 0
        while (poll < 12 && service.currentPackageName != "com.whatsapp") {
            delay(100)
            poll++
        }
        delay(350)

        // Find and click WhatsApp search
        val searchNode = findWhatsAppSearchNode(service)
        if (searchNode != null) {
            service.performClickOnNode(searchNode)
            delay(350)

            val searchInput = findWhatsAppSearchInput(service)
            if (searchInput != null) {
                service.performSetTextOnNode(searchInput, cleanRecipient)
                delay(500)

                val contactResult = findWhatsAppContactResult(service, cleanRecipient)
                if (contactResult != null) {
                    service.performClickOnNode(contactResult)
                    delay(500)
                }
            }
        }

        // In the chat screen: find message input field and type
        val messageInput = findWhatsAppMessageInput(service) ?: findAnyEditableNode(service)
        if (messageInput != null) {
            service.performSetTextOnNode(messageInput, cleanMessage)
            delay(250)

            if (autoSend) {
                val sendBtn = findWhatsAppSendButton(service)
                if (sendBtn != null) {
                    service.performClickOnNode(sendBtn)
                    return ActionResult(true, "Successfully sent WhatsApp message to $cleanRecipient: \"$cleanMessage\"")
                }
            }
            return ActionResult(true, "Typed message for $cleanRecipient on WhatsApp: \"$cleanMessage\"")
        }

        return ActionResult(true, "Opened WhatsApp for $cleanRecipient. Prepared: \"$cleanMessage\"")
    }

    /**
     * Reads recent chat conversation messages from current screen (WhatsApp, Messages, etc.).
     */
    fun readChatMessages(limit: Int = 10): JSONObject {
        val service = ChinarAccessibilityService.getInstance()
            ?: return JSONObject().apply {
                put("status", "error")
                put("message", "Accessibility Service is not enabled. Please enable it in Settings.")
            }

        val root = service.rootInActiveWindow
            ?: return JSONObject().apply {
                put("status", "error")
                put("message", "No active window or chat screen detected.")
            }

        val currentPkg = service.currentPackageName
        val title = service.currentWindowTitle

        val textNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesByCriteria(root, textNodes) { node ->
            val text = node.text?.toString()?.trim() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val isIgnored = text.isBlank() ||
                text.length < 2 ||
                id.contains("header") ||
                id.contains("action_bar") ||
                id.contains("search") ||
                id.contains("entry") ||
                id.contains("input") ||
                desc.contains("camera") ||
                desc.contains("attach") ||
                desc.contains("voice message") ||
                text.equals("type a message", ignoreCase = true) ||
                text.equals("message", ignoreCase = true) ||
                text.equals("search", ignoreCase = true) ||
                text.equals("online", ignoreCase = true)
            !isIgnored
        }

        val messages = textNodes.mapNotNull { it.text?.toString()?.trim() }
            .distinct()
            .takeLast(limit)

        val latestMessage = messages.lastOrNull() ?: ""

        return JSONObject().apply {
            put("status", "success")
            put("app", currentPkg)
            put("chatTitle", title)
            put("messages", JSONArray(messages))
            put("latestIncomingMessage", latestMessage)
            put("summary", if (messages.isNotEmpty()) {
                "Recent chat messages:\n" + messages.joinToString("\n") { "- $it" }
            } else {
                "No readable messages detected in the active chat."
            })
        }
    }

    /**
     * Replies directly in the currently active chat screen.
     */
    suspend fun replyInChat(message: String, autoSend: Boolean = true): ActionResult {
        val cleanMessage = message.trim()
        if (cleanMessage.isBlank()) {
            return ActionResult(false, "Please provide the reply message text.")
        }

        val service = ChinarAccessibilityService.getInstance()
            ?: return ActionResult(false, "Accessibility Service is not enabled.")

        val inputNode = findWhatsAppMessageInput(service) ?: findAnyEditableNode(service)
            ?: return ActionResult(false, "Could not find chat input field on screen.")

        val textSet = service.performSetTextOnNode(inputNode, cleanMessage)
        if (!textSet) {
            return ActionResult(false, "Failed to enter reply text into chat.")
        }

        delay(200)
        if (autoSend) {
            val sendBtn = findWhatsAppSendButton(service)
            if (sendBtn != null) {
                service.performClickOnNode(sendBtn)
                return ActionResult(true, "Sent reply in chat: \"$cleanMessage\"")
            }
        }

        return ActionResult(true, "Entered reply in chat: \"$cleanMessage\"")
    }

    private fun findWhatsAppSearchNode(service: ChinarAccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findNodeByCriteria(root) { node ->
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            id.endsWith(":id/menuitem_search") ||
                id.contains("search_holder") ||
                desc.contains("search") ||
                desc.contains("تلاش") ||
                desc.contains("खोज") ||
                text == "search"
        }
    }

    private fun findWhatsAppSearchInput(service: ChinarAccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findNodeByCriteria(root) { node ->
            val id = node.viewIdResourceName?.lowercase() ?: ""
            node.isEditable && (id.contains("search_src_text") || id.contains("search_input") || id.contains("search"))
        } ?: findAnyEditableNode(service)
    }

    private fun findWhatsAppContactResult(service: ChinarAccessibilityService, recipient: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val lowerRecipient = recipient.trim().lowercase()
        val matchingNode = findNodeByCriteria(root) { node ->
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            !node.isEditable && (text.contains(lowerRecipient) || desc.contains(lowerRecipient))
        }
        if (matchingNode != null) {
            return matchingNode
        }
        return findNodeByCriteria(root) { node ->
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val isSearch = id.contains("search")
            !isSearch && !node.isEditable && (node.isClickable || node.parent?.isClickable == true) &&
                (node.text?.isNotBlank() == true || node.contentDescription?.isNotBlank() == true)
        }
    }

    private fun findWhatsAppMessageInput(service: ChinarAccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findNodeByCriteria(root) { node ->
            val id = node.viewIdResourceName?.lowercase() ?: ""
            node.isEditable && (id.endsWith(":id/entry") || id.contains("conversation_text_entry") || id.contains("input"))
        }
    }

    private fun findAnyEditableNode(service: ChinarAccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findNodeByCriteria(root) { it.isEditable }
    }

    private fun findWhatsAppSendButton(service: ChinarAccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findNodeByCriteria(root) { node ->
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            id.endsWith(":id/send") ||
                desc == "send" ||
                desc == "بھیجیں" ||
                desc == "भेजें" ||
                desc.contains("send message")
        }
    }

    private fun findNodeByCriteria(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByCriteria(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun findAllNodesByCriteria(
        root: AccessibilityNodeInfo,
        collector: MutableList<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ) {
        if (predicate(root)) collector.add(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findAllNodesByCriteria(child, collector, predicate)
        }
    }

    /**
     * Searches YouTube for a video, song, artist, or query.
     * Prefers native YouTube app if installed, falling back cleanly to web browser.
     */
    fun searchYouTube(query: String): ActionResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return ActionResult(false, "Please specify what you would like to search for on YouTube.")
        }

        val encoded = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8.toString())
        val appUri = Uri.parse("vnd.youtube:results?search_query=$encoded")
        val webUri = Uri.parse("https://www.youtube.com/results?search_query=$encoded")

        // 1. Try native YouTube app
        val appIntent = Intent(Intent.ACTION_VIEW, appUri).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pm = context.packageManager
        if (appIntent.resolveActivity(pm) != null) {
            return try {
                context.startActivity(appIntent)
                Log.d(TAG, "[YOUTUBE_APP] Opened native YouTube search for: '$cleanQuery'")
                ActionResult(
                    success = true,
                    message = "Opened YouTube search results for \"$cleanQuery\".",
                    details = mapOf("query" to cleanQuery, "provider" to "youtube_app")
                )
            } catch (e: Exception) {
                Log.w(TAG, "[YOUTUBE_APP_ERROR] Falling back to browser for YouTube", e)
                openWebUrl(webUri, cleanQuery)
            }
        }

        // 2. Fallback to web browser
        return openWebUrl(webUri, cleanQuery)
    }

    private fun openWebUrl(uri: Uri, query: String): ActionResult {
        return try {
            val webIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
            Log.d(TAG, "[YOUTUBE_WEB] Opened web YouTube search for: '$query'")
            ActionResult(
                success = true,
                message = "Opened YouTube search in browser for \"$query\".",
                details = mapOf("query" to query, "provider" to "browser")
            )
        } catch (e: Exception) {
            Log.e(TAG, "[WEB_INTENT_ERROR] Failed opening web URL", e)
            ActionResult(false, "Could not open YouTube: ${e.localizedMessage}")
        }
    }

    /**
     * Prepares an SMS message or phone call draft.
     * CRITICAL SAFETY: Never silently sends without explicit user confirmation!
     */
    fun prepareCommunication(
        type: String, // "sms" or "call"
        recipient: String,
        messageBody: String? = null,
        confirmed: Boolean = false
    ): ActionResult {
        val cleanRecipient = recipient.trim()
        if (cleanRecipient.isBlank()) {
            return ActionResult(false, "Please specify a contact name or phone number.")
        }

        when (type.lowercase()) {
            "sms", "message" -> {
                if (!confirmed) {
                    val prompt = if (!messageBody.isNullOrBlank()) {
                        "Are you sure you want to send this message to $cleanRecipient: \"$messageBody\"?"
                    } else {
                        "Are you sure you want to open a new message to $cleanRecipient?"
                    }
                    return ActionResult(
                        success = false,
                        message = prompt,
                        details = mapOf(
                            "requiresConfirmation" to "true",
                            "actionType" to "sms",
                            "recipient" to cleanRecipient,
                            "body" to (messageBody ?: "")
                        )
                    )
                }

                // If user confirmed, open composer intent
                return try {
                    val smsUri = Uri.parse("smsto:$cleanRecipient")
                    val smsIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                        if (!messageBody.isNullOrBlank()) {
                            putExtra("sms_body", messageBody)
                        }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(smsIntent)
                    ActionResult(true, "Prepared message for $cleanRecipient.")
                } catch (e: Exception) {
                    ActionResult(false, "Failed to open messaging app: ${e.localizedMessage}")
                }
            }
            "call", "phone" -> {
                if (!confirmed) {
                    return ActionResult(
                        success = false,
                        message = "Are you sure you want to call $cleanRecipient?",
                        details = mapOf(
                            "requiresConfirmation" to "true",
                            "actionType" to "call",
                            "recipient" to cleanRecipient
                        )
                    )
                }

                return try {
                    val dialUri = Uri.parse("tel:$cleanRecipient")
                    val dialIntent = Intent(Intent.ACTION_DIAL, dialUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(dialIntent)
                    ActionResult(true, "Opened dialer for $cleanRecipient.")
                } catch (e: Exception) {
                    ActionResult(false, "Failed to open phone dialer: ${e.localizedMessage}")
                }
            }
            else -> {
                return ActionResult(false, "Unsupported communication type: $type")
            }
        }
    }
}
