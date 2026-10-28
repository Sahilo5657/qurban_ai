package com.chinar.assistant.agent

import android.graphics.Rect
import com.chinar.assistant.agent.actions.AppLauncher
import com.chinar.assistant.agent.observation.ScreenObserver
import com.chinar.assistant.agent.observation.ScreenState
import com.chinar.assistant.agent.observation.UiNode
import com.chinar.assistant.agent.observation.UiRole
import com.chinar.assistant.agent.tools.ToolCallDispatcher
import com.chinar.assistant.agent.tools.ToolDeclarations
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChinarAgentSystemTest {

    @Test
    fun testToolDeclarationsCountAndNames() {
        val declarations = ToolDeclarations.getAllDeclarations()
        assertEquals(16, declarations.size)

        val expectedTools = setOf(
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
            "web_search",
            "send_whatsapp_message",
            "read_chat_messages",
            "reply_in_chat",
            "change_voice"
        )

        val declaredNames = declarations.map { it.getString("name") }.toSet()
        assertEquals(expectedTools, declaredNames)

        for (decl in declarations) {
            assertTrue("Description must not be blank for ${decl.getString("name")}", decl.getString("description").isNotBlank())
            assertTrue("Must contain parameters object for ${decl.getString("name")}", decl.has("parameters"))
            val params = decl.getJSONObject("parameters")
            assertEquals("OBJECT", params.getString("type"))
            assertTrue(params.has("properties"))
        }
    }

    @Test
    fun testToolCallDispatcherDeduplication() = runTest {
        val dispatcher = ToolCallDispatcher(context = null)
        val callId = "test-call-dedup-101"
        val func = "web_search"
        val args = "{\"query\":\"what is kotlin\"}"

        val firstResult = dispatcher.dispatchToolCall(callId, func, args)
        assertEquals("success", firstResult.optString("status"))
        assertEquals("what is kotlin", firstResult.optString("query"))

        // Retransmitting the same callId must return cached response directly
        val secondResult = dispatcher.dispatchToolCall(callId, func, args)
        assertEquals(firstResult, secondResult)
    }

    @Test
    fun testToolCallDispatcherLoopDetection() = runTest {
        val dispatcher = ToolCallDispatcher(context = null)
        val func = "web_search"
        val args = "{\"query\":\"repeated identical query\"}"

        // Call 1
        val res1 = dispatcher.dispatchToolCall("call-1", func, args)
        assertEquals("success", res1.optString("status"))

        // Call 2
        val res2 = dispatcher.dispatchToolCall("call-2", func, args)
        assertEquals("success", res2.optString("status"))

        // Call 3 - identical action & arguments must trigger loop detection
        val res3 = dispatcher.dispatchToolCall("call-3", func, args)
        assertEquals("loop_detected", res3.optString("status"))
        assertTrue(res3.optString("message").contains("3 times in a row"))
    }

    @Test
    fun testAuthorizationGateBlocksUnauthenticatedTools() = runTest {
        val dispatcher = ToolCallDispatcher(context = null)

        // Gate returns false -> unauthorized
        dispatcher.authorizationGate = { false }

        // Test 1: Model hallucinates 'isOwner=true' in arguments on mutating tool
        val blockedRes1 = dispatcher.dispatchToolCall(
            "call-auth-1",
            "open_settings",
            "{\"setting\":\"wifi\",\"isOwner\":true,\"confirmed\":true}"
        )
        assertEquals("unauthorized", blockedRes1.optString("status"))
        assertTrue(blockedRes1.optString("error").contains("Speaker verification required"))

        // Test 2: Read-only query 'observe_screen' MUST ALSO be blocked before authentication
        val blockedRes2 = dispatcher.dispatchToolCall(
            "call-auth-2",
            "observe_screen",
            "{}"
        )
        assertEquals("unauthorized", blockedRes2.optString("status"))
        assertTrue(blockedRes2.optString("error").contains("Speaker verification required"))

        // Test 3: Hardware device action 'device_action' MUST ALSO be blocked
        val blockedRes3 = dispatcher.dispatchToolCall(
            "call-auth-3",
            "device_action",
            "{\"action\":\"toggle_flashlight\",\"state\":\"on\",\"adminOverride\":true}"
        )
        assertEquals("unauthorized", blockedRes3.optString("status"))
        assertTrue(blockedRes3.optString("error").contains("Speaker verification required"))

        // Gate returns true -> authorized
        dispatcher.authorizationGate = { true }
        val allowedRes = dispatcher.dispatchToolCall(
            "call-auth-4",
            "web_search",
            "{\"query\":\"how does speaker verification work\"}"
        )
        assertEquals("success", allowedRes.optString("status"))
    }

    @Test
    fun testAppLauncherSpokenAliasResolution() {
        val mockApps = listOf(
            AppLauncher.AppEntry(label = "YouTube", packageName = "com.google.android.youtube"),
            AppLauncher.AppEntry(label = "Google Chrome", packageName = "com.android.chrome"),
            AppLauncher.AppEntry(label = "Messages", packageName = "com.google.android.apps.messaging"),
            AppLauncher.AppEntry(label = "Spotify", packageName = "com.spotify.music"),
            AppLauncher.AppEntry(label = "Notes", packageName = "com.coloros.note"),
            AppLauncher.AppEntry(label = "Notepad Pro", packageName = "com.example.notepad")
        )

        val launcher = AppLauncher()

        // 1. Alias "yt" -> YouTube
        val ytMatch = launcher.findMatchingApp("yt", mockApps)
        assertTrue(ytMatch is AppLauncher.MatchResult.SingleMatch)
        assertEquals("YouTube", (ytMatch as AppLauncher.MatchResult.SingleMatch).entry.label)

        // 2. Alias "msg" -> Messages
        val msgMatch = launcher.findMatchingApp("msg", mockApps)
        assertTrue(msgMatch is AppLauncher.MatchResult.SingleMatch)
        assertEquals("Messages", (msgMatch as AppLauncher.MatchResult.SingleMatch).entry.label)

        // 3. Alias "browser" -> Google Chrome
        val browserMatch = launcher.findMatchingApp("browser", mockApps)
        assertTrue(browserMatch is AppLauncher.MatchResult.SingleMatch)
        assertEquals("Google Chrome", (browserMatch as AppLauncher.MatchResult.SingleMatch).entry.label)

        // 4. StartsWith "spot" -> Spotify
        val spotMatch = launcher.findMatchingApp("spot", mockApps)
        assertTrue(spotMatch is AppLauncher.MatchResult.SingleMatch)
        assertEquals("Spotify", (spotMatch as AppLauncher.MatchResult.SingleMatch).entry.label)

        // 5. Ambiguous query "not" -> matches Notes and Notepad Pro
        val ambiguousMatch = launcher.findMatchingApp("not", mockApps)
        assertTrue(ambiguousMatch is AppLauncher.MatchResult.MultipleMatches)
        assertEquals(2, (ambiguousMatch as AppLauncher.MatchResult.MultipleMatches).candidates.size)

        // 6. Unknown query -> NoMatch
        val noMatch = launcher.findMatchingApp("nonexistentapp", mockApps)
        assertTrue(noMatch is AppLauncher.MatchResult.NoMatch)
    }

    @Test
    fun testUiRoleInferenceFromAttributes() {
        // Editable class or attribute -> INPUT_FIELD
        assertEquals(
            UiRole.INPUT_FIELD,
            ScreenObserver.inferRoleFromAttributes(isEditable = true, isScrollable = false, isClickable = true, hasText = false, className = "android.view.View")
        )
        assertEquals(
            UiRole.INPUT_FIELD,
            ScreenObserver.inferRoleFromAttributes(isEditable = false, isScrollable = false, isClickable = true, hasText = false, className = "android.widget.EditText")
        )

        // CheckBox
        assertEquals(
            UiRole.CHECKBOX,
            ScreenObserver.inferRoleFromAttributes(isEditable = false, isScrollable = false, isClickable = true, hasText = true, className = "android.widget.CheckBox")
        )

        // Switch
        assertEquals(
            UiRole.SWITCH,
            ScreenObserver.inferRoleFromAttributes(isEditable = false, isScrollable = false, isClickable = true, hasText = true, className = "android.widget.Switch")
        )

        // Scrollable
        assertEquals(
            UiRole.SCROLLABLE,
            ScreenObserver.inferRoleFromAttributes(isEditable = false, isScrollable = true, isClickable = false, hasText = false, className = "androidx.recyclerview.widget.RecyclerView")
        )

        // Button / clickable
        assertEquals(
            UiRole.BUTTON,
            ScreenObserver.inferRoleFromAttributes(isEditable = false, isScrollable = false, isClickable = true, hasText = true, className = "android.widget.Button")
        )

        // Text display
        assertEquals(
            UiRole.TEXT,
            ScreenObserver.inferRoleFromAttributes(isEditable = false, isScrollable = false, isClickable = false, hasText = true, className = "android.widget.TextView")
        )
    }

    @Test
    fun testScreenStatePromptSummaryBounded() {
        val mockNodes = (1..25).map { index ->
            UiNode(
                id = index,
                text = "Item $index",
                contentDescription = "",
                className = "android.widget.TextView",
                role = UiRole.TEXT,
                isClickable = false,
                isEditable = false,
                isScrollable = false,
                isCheckable = false,
                isChecked = false,
                isEnabled = true,
                bounds = Rect(0, 0, 100, 100)
            )
        }

        val state = ScreenState(
            packageName = "com.google.android.youtube",
            windowTitle = "Home",
            elements = mockNodes
        )

        val summary = state.toPromptSummary(maxElements = 10)
        assertTrue(summary.contains("App: Youtube (com.google.android.youtube)"))
        assertTrue(summary.contains("Interactive UI Elements (25 found):"))
        assertTrue(summary.contains("[1] text \"Item 1\""))
        assertTrue(summary.contains("[10] text \"Item 10\""))
        assertFalse(summary.contains("[11] text \"Item 11\""))
        assertTrue(summary.contains("... and 15 more elements"))
    }

    @Test
    fun testWhatsAppAndChatToolDispatches() = runTest {
        val dispatcher = ToolCallDispatcher(context = null)

        // WhatsApp messaging without context returns graceful error
        val waRes = dispatcher.dispatchToolCall("call-wa-1", "send_whatsapp_message", "{\"recipient\":\"Ali\",\"message\":\"Salam\"}")
        assertEquals("error", waRes.optString("status"))

        // Read chat messages without context returns error
        val chatRes = dispatcher.dispatchToolCall("call-chat-1", "read_chat_messages", "{\"limit\":5}")
        assertEquals("error", chatRes.optString("status"))

        // Reply in chat without context returns error
        val replyRes = dispatcher.dispatchToolCall("call-reply-1", "reply_in_chat", "{\"message\":\"Fine\"}")
        assertEquals("error", replyRes.optString("status"))
    }
}
