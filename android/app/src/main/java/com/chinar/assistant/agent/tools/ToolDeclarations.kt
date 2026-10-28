package com.chinar.assistant.agent.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * Generates official Google Multimodal Live API function declarations
 * for Chinar's whole-phone control agent suite.
 */
object ToolDeclarations {

    fun getAllDeclarations(): List<JSONObject> {
        return listOf(
            buildObserveScreenDeclaration(),
            buildClickElementDeclaration(),
            buildEnterTextDeclaration(),
            buildScrollScreenDeclaration(),
            buildNavigateSystemDeclaration(),
            buildOpenAppDeclaration(),
            buildOpenSettingsDeclaration(),
            buildDeviceQueryDeclaration(),
            buildDeviceActionDeclaration(),
            buildSearchMediaDeclaration(),
            buildPrepareCommunicationDeclaration(),
            buildWebSearchDeclaration(),
            buildSendWhatsAppMessageDeclaration(),
            buildReadChatMessagesDeclaration(),
            buildReplyInChatDeclaration(),
            buildChangeVoiceDeclaration()
        )
    }

    private fun buildObserveScreenDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "observe_screen")
            put("description", "Inspect the current foreground screen, active application, window title, and list of visible interactive UI elements with their IDs.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            })
        }
    }

    private fun buildClickElementDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "click_element")
            put("description", "Click or tap an interactive UI element on the current screen by its numerical element ID or semantic text label.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("element_id", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "The 1-based ID of the element from the observe_screen list.")
                    })
                    put("semantic_text", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The visible label or content description of the element to click if ID is unknown.")
                    })
                })
            })
        }
    }

    private fun buildEnterTextDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "enter_text")
            put("description", "Enter text into an input field or text box on the current screen.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The text to enter into the field.")
                    })
                    put("element_id", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "The target input element ID from observe_screen.")
                    })
                    put("semantic_text", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The label/hint of the input field if ID is unknown.")
                    })
                })
                put("required", JSONArray().apply { put("text") })
            })
        }
    }

    private fun buildScrollScreenDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "scroll_screen")
            put("description", "Scroll the screen or active list view up or down.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("direction", JSONObject().apply {
                        put("type", "STRING")
                        put("enum", JSONArray().apply { put("down"); put("up") })
                        put("description", "Direction to scroll ('down' to view more content below, 'up' for previous content).")
                    })
                })
                put("required", JSONArray().apply { put("direction") })
            })
        }
    }

    private fun buildNavigateSystemDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "navigate_system")
            put("description", "Perform Android system navigation: go back to previous screen, go to home screen, or show recent apps.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "STRING")
                        put("enum", JSONArray().apply { put("back"); put("home"); put("recents") })
                        put("description", "System navigation action to execute.")
                    })
                })
                put("required", JSONArray().apply { put("action") })
            })
        }
    }

    private fun buildOpenAppDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "open_app")
            put("description", "Launch an installed Android application by its user-visible name (e.g. 'YouTube', 'WhatsApp', 'Spotify', 'Chrome', 'Settings', 'Camera', 'Clock').")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("app_name", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The common name of the application to launch.")
                    })
                })
                put("required", JSONArray().apply { put("app_name") })
            })
        }
    }

    private fun buildOpenSettingsDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "open_settings")
            put("description", "Directly navigate to a specific Android system settings page.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("page", JSONObject().apply {
                        put("type", "STRING")
                        put("enum", JSONArray().apply {
                            put("bluetooth"); put("wifi"); put("display"); put("sound")
                            put("battery"); put("accessibility"); put("apps"); put("general")
                        })
                        put("description", "Target settings page.")
                    })
                })
                put("required", JSONArray().apply { put("page") })
            })
        }
    }

    private fun buildDeviceQueryDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "device_query")
            put("description", "Query local device state such as battery percentage, charging status, or media volume.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query_type", JSONObject().apply {
                        put("type", "STRING")
                        put("enum", JSONArray().apply { put("battery"); put("volume") })
                        put("description", "Type of hardware device state to query.")
                    })
                })
                put("required", JSONArray().apply { put("query_type") })
            })
        }
    }

    private fun buildDeviceActionDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "device_action")
            put("description", "Perform device hardware action such as toggling the flashlight or adjusting media volume.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "STRING")
                        put("enum", JSONArray().apply {
                            put("toggle_flashlight"); put("volume_up"); put("volume_down"); put("volume_mute")
                        })
                        put("description", "Device hardware action to execute.")
                    })
                    put("state", JSONObject().apply {
                        put("type", "STRING")
                        put("enum", JSONArray().apply { put("on"); put("off") })
                        put("description", "For flashlight: 'on' or 'off'.")
                    })
                })
                put("required", JSONArray().apply { put("action") })
            })
        }
    }

    private fun buildSearchMediaDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "search_media")
            put("description", "Search YouTube for a specific song, video, artist, tutorial, or content.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The search query to look up on YouTube.")
                    })
                })
                put("required", JSONArray().apply { put("query") })
            })
        }
    }

    private fun buildPrepareCommunicationDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "prepare_communication")
            put("description", "Prepare a text message (SMS) or phone call draft. Consequential action requiring user confirmation.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("type", JSONObject().apply {
                        put("type", "STRING")
                        put("enum", JSONArray().apply { put("sms"); put("call") })
                        put("description", "Communication type: 'sms' or 'call'.")
                    })
                    put("recipient", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Contact name or phone number.")
                    })
                    put("message_body", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Text content for the SMS message.")
                    })
                    put("confirmed", JSONObject().apply {
                        put("type", "BOOLEAN")
                        put("description", "True if the user has explicitly spoken confirmation to send/call.")
                    })
                })
                put("required", JSONArray().apply { put("type"); put("recipient") })
            })
        }
    }

    private fun buildWebSearchDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "web_search")
            put("description", "Perform a search for current web information, real-time facts, news, or general knowledge.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The web search query.")
                    })
                })
                put("required", JSONArray().apply { put("query") })
            })
        }
    }

    private fun buildSendWhatsAppMessageDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "send_whatsapp_message")
            put("description", "Fast, single-step WhatsApp messaging. Directly opens WhatsApp, finds the contact or phone number, enters the message, and sends it smoothly without bouncing between screens.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("recipient", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The contact name or phone number of the person or group in WhatsApp.")
                    })
                    put("message", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The message text to send.")
                    })
                    put("auto_send", JSONObject().apply {
                        put("type", "BOOLEAN")
                        put("description", "Whether to automatically tap Send. Defaults to true.")
                    })
                })
                put("required", JSONArray().apply { put("recipient"); put("message") })
            })
        }
    }

    private fun buildReadChatMessagesDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "read_chat_messages")
            put("description", "Read recent conversation messages and incoming text from the currently open chat screen (WhatsApp, Telegram, SMS, etc.). Use when asked to converse, reply, or act as a bot in a chat.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("limit", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "Max number of recent messages to return. Defaults to 10.")
                    })
                })
            })
        }
    }

    private fun buildReplyInChatDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "reply_in_chat")
            put("description", "Send a reply message directly into the currently active chat screen (e.g. WhatsApp conversation) without opening or searching. Automatically matches the conversation style.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The reply message content to send.")
                    })
                    put("auto_send", JSONObject().apply {
                        put("type", "BOOLEAN")
                        put("description", "Whether to immediately tap the Send button. Defaults to true.")
                    })
                })
                put("required", JSONArray().apply { put("message") })
            })
        }
    }

    private fun buildChangeVoiceDeclaration(): JSONObject {
        return JSONObject().apply {
            put("name", "change_voice")
            put("description", "Change the assistant's active voice pack personality (e.g. 'JARVIS', 'FRIDAY', 'EDITH', 'ULTRON', 'ALEXA', 'SIRI', 'Algieba', 'Sulafat', 'Kore').")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("voice_name", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The name of the voice pack to activate (e.g. 'JARVIS', 'FRIDAY', 'EDITH', 'ULTRON', 'ALEXA', 'SIRI').")
                    })
                })
                put("required", JSONArray().apply { put("voice_name") })
            })
        }
    }
}
