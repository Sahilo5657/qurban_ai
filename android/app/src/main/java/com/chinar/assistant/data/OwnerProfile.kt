package com.chinar.assistant.data

import org.json.JSONArray
import org.json.JSONObject

data class BasicIdentity(
    val ownerName: String = "Sahil Khan",
    val assistantName: String = "Qurban"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ownerName", ownerName)
        put("assistantName", assistantName)
    }

    companion object {
        fun fromJson(json: JSONObject): BasicIdentity = BasicIdentity(
            ownerName = json.optString("ownerName", "Sahil Khan"),
            assistantName = json.optString("assistantName", "Qurban")
        )
    }
}

data class RootsAndEducation(
    val birthplace: String = "Khona Kaly, Tehsil Nawagai, District Bajaur, Pakistan",
    val currentResidence: String = "Afghan Colony, Peshawar, Pakistan",
    val earlySchooling: String = "Islamia Children Academy (started 2011)",
    val grade8Schooling: String = "Punjab Bahawalpur Army Public School and College (2016)",
    val matricAchievements: String = "3rd position in Grade 9 board examinations, 1st position in school in Grade 10",
    val college: String = "Government College Hashtnaghri & Government Degree College Nawagai",
    val university: String = "BS Software Engineering, Institute of Management Sciences (IMSciences), Oct 10 2022 – Sep 16 2026"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("birthplace", birthplace)
        put("currentResidence", currentResidence)
        put("earlySchooling", earlySchooling)
        put("grade8Schooling", grade8Schooling)
        put("matricAchievements", matricAchievements)
        put("college", college)
        put("university", university)
    }

    companion object {
        fun fromJson(json: JSONObject): RootsAndEducation = RootsAndEducation(
            birthplace = json.optString("birthplace", "Khona Kaly, Tehsil Nawagai, District Bajaur, Pakistan"),
            currentResidence = json.optString("currentResidence", "Afghan Colony, Peshawar, Pakistan"),
            earlySchooling = json.optString("earlySchooling", "Islamia Children Academy (started 2011)"),
            grade8Schooling = json.optString("grade8Schooling", "Punjab Bahawalpur Army Public School and College (2016)"),
            matricAchievements = json.optString("matricAchievements", "3rd position in Grade 9 board examinations, 1st position in school in Grade 10"),
            college = json.optString("college", "Government College Hashtnaghri & Government Degree College Nawagai"),
            university = json.optString("university", "BS Software Engineering, Institute of Management Sciences (IMSciences), Oct 10 2022 – Sep 16 2026")
        )
    }
}

data class CommunityAndCertifications(
    val communityRoles: List<String> = listOf(
        "Core and Tech Team Member, Google Developer Groups onCampus",
        "Helped organize DevFest 2024 and SheLeads 2025",
        "Attended DevFest 2023 and DevFest Cloud Islamabad 2024"
    ),
    val languageProficiency: String = "B2 CEFR EnglishScore",
    val certifications: List<String> = listOf(
        "Coursera: Software Development",
        "Coursera: Flutter",
        "Coursera: Introduction to Java",
        "Coursera: Getting started with Flutter",
        "Git/GitHub Workshop",
        "DigiSkills: Graphic Design, Freelancing, AI using Python, Mobile Game and App Development, Data Analytics & Business Intelligence, SEO, WordPress, Video Editing, Animation and Vlogging"
    )
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("communityRoles", JSONArray(communityRoles))
        put("languageProficiency", languageProficiency)
        put("certifications", JSONArray(certifications))
    }

    companion object {
        fun fromJson(json: JSONObject): CommunityAndCertifications {
            val roles = json.optJSONArray("communityRoles")?.let { arr ->
                List(arr.length()) { i -> arr.getString(i) }
            } ?: emptyList()
            val certs = json.optJSONArray("certifications")?.let { arr ->
                List(arr.length()) { i -> arr.getString(i) }
            } ?: emptyList()
            return CommunityAndCertifications(
                communityRoles = roles.ifEmpty { CommunityAndCertifications().communityRoles },
                languageProficiency = json.optString("languageProficiency", "B2 CEFR EnglishScore"),
                certifications = certs.ifEmpty { CommunityAndCertifications().certifications }
            )
        }
    }
}

data class ProfessionalExperience(
    val currentRole: String = "Flutter Developer at Quite Logic",
    val thesisTitle: String = "Integrated Hospital Patient Flow Management System",
    val thesisDefenseDate: String = "July 21, 2026",
    val thesisDescription: String = "NFC-based patient-flow management, IoT edge nodes, Flutter applications, Cloud Firestore, and AI/NLP for clinical notes",
    val longTermAmbition: String = "Global AI Systems Integration Architect focused on secure distributed systems and automated manufacturing"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("currentRole", currentRole)
        put("thesisTitle", thesisTitle)
        put("thesisDefenseDate", thesisDefenseDate)
        put("thesisDescription", thesisDescription)
        put("longTermAmbition", longTermAmbition)
    }

    companion object {
        fun fromJson(json: JSONObject): ProfessionalExperience = ProfessionalExperience(
            currentRole = json.optString("currentRole", "Flutter Developer at Quite Logic"),
            thesisTitle = json.optString("thesisTitle", "Integrated Hospital Patient Flow Management System"),
            thesisDefenseDate = json.optString("thesisDefenseDate", "July 21, 2026"),
            thesisDescription = json.optString("thesisDescription", "NFC-based patient-flow management, IoT edge nodes, Flutter applications, Cloud Firestore, and AI/NLP for clinical notes"),
            longTermAmbition = json.optString("longTermAmbition", "Global AI Systems Integration Architect focused on secure distributed systems and automated manufacturing")
        )
    }
}

data class CurrentGoal(
    val primaryGoal: String = "Exploring Master's admission and deferral processes for Taiwan's Spring 2027 intake"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("primaryGoal", primaryGoal)
    }

    companion object {
        fun fromJson(json: JSONObject): CurrentGoal = CurrentGoal(
            primaryGoal = json.optString("primaryGoal", "Exploring Master's admission and deferral processes for Taiwan's Spring 2027 intake")
        )
    }
}

/**
 * Structured Personal Assistant Profile for Sahil Khan.
 * Completely editable, locally stored, and kept strictly separate from voice biometric templates.
 */
data class OwnerProfile(
    val basicIdentity: BasicIdentity = BasicIdentity(),
    val rootsAndEducation: RootsAndEducation = RootsAndEducation(),
    val communityAndCertifications: CommunityAndCertifications = CommunityAndCertifications(),
    val professionalExperience: ProfessionalExperience = ProfessionalExperience(),
    val currentGoal: CurrentGoal = CurrentGoal()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("basicIdentity", basicIdentity.toJson())
        put("rootsAndEducation", rootsAndEducation.toJson())
        put("communityAndCertifications", communityAndCertifications.toJson())
        put("professionalExperience", professionalExperience.toJson())
        put("currentGoal", currentGoal.toJson())
    }

    /**
     * Formats a concise, relevant persona context snippet for Gemini Live.
     * Injected only when the owner is authenticated.
     */
    fun formatSystemInstructionContext(activeVoice: VoiceOption? = null): String {
        return buildString {
            append("You are Qurban, a personalized, highly efficient, whole-phone autonomous AI assistant for your owner, ${basicIdentity.ownerName}.\n")
            append("Owner Background Summary:\n")
            append("- Roots: Born in ${rootsAndEducation.birthplace}, residing in ${rootsAndEducation.currentResidence}.\n")
            append("- Education: ${rootsAndEducation.university}.\n")
            append("- Current Career: ${professionalExperience.currentRole}. Final year thesis: ${professionalExperience.thesisTitle}.\n")
            append("- Ambition: ${professionalExperience.longTermAmbition}.\n")
            append("- Active Focus: ${currentGoal.primaryGoal}.\n")
            append("Address ${basicIdentity.ownerName} with respect, natural warmth, and concise helpful responses.\n\n")

            if (activeVoice != null && activeVoice.personaPrompt.isNotBlank()) {
                append(activeVoice.personaPrompt.trim())
                append("\n\n")
            }

            append("=== STRICT 4-LANGUAGE RESTRICTION ===\n")
            append("You are strictly and exclusively restricted to these 4 languages only:\n")
            append("1. English\n")
            append("2. Pashto (پښتو)\n")
            append("3. Urdu (اردو)\n")
            append("4. Hindi (हिन्दी) (including natural colloquial forms: Hinglish, Roman Urdu, and Roman Pashto).\n")
            append("MANDATORY RULES:\n")
            append("- You must ONLY speak, listen, understand, and generate responses in these 4 languages.\n")
            append("- Absolutely NEVER converse, speak, or reply in any other language (such as Arabic, French, Spanish, German, Chinese, etc.).\n")
            append("- If addressed in any other language, politely refuse in one of the 4 allowed languages stating that you strictly only operate in English, Pashto, Urdu, and Hindi.\n\n")

            append("=== FAST SINGLE-COMMAND PHONE ACTIONS ===\n")
            append("- When commanded to send a message on WhatsApp or open WhatsApp and message someone (e.g. 'send a message to abc on whatsapp saying hello', 'whatsapp mom that I will be late'):\n")
            append("  ALWAYS use the single dedicated tool `send_whatsapp_message(recipient, message, auto_send=true)`.\n")
            append("  Fulfill it in ONE single command smoothly. NEVER break it into multi-step clicks or searches.\n")
            append("  NEVER navigate to the home screen or switch away while fulfilling messaging commands.\n\n")

            append("=== IN-CHAT AUTONOMOUS CONVERSATIONAL BOT ===\n")
            append("- When commanded to communicate, converse, or act like a bot inside a chat (e.g. WhatsApp, Messages):\n")
            append("  1. Call `read_chat_messages` to read what the other person wrote.\n")
            append("  2. Act as an intelligent, courteous conversation bot representing ${basicIdentity.ownerName}.\n")
            append("  3. CRITICAL LANGUAGE MATCHING: Accurately match the language, script, and tone of the sender's incoming message:\n")
            append("     * English message -> Reply in English.\n")
            append("     * Urdu message (Urdu script or Roman Urdu) -> Reply in Urdu (matching their script style).\n")
            append("     * Hinglish message -> Reply in natural Hinglish.\n")
            append("     * Hindi message -> Reply in Hindi / Hinglish.\n")
            append("     * Pashto message (Pashto script or Roman Pashto) -> Reply in Pashto (matching their script style).\n")
            append("  4. Call `reply_in_chat(message, auto_send=true)` to deliver your response smoothly into the chat.\n")
            append("  5. Keep your spoken confirmation to ${basicIdentity.ownerName} brief.")
        }
    }

    companion object {
        fun fromJson(json: JSONObject): OwnerProfile = OwnerProfile(
            basicIdentity = json.optJSONObject("basicIdentity")?.let { BasicIdentity.fromJson(it) } ?: BasicIdentity(),
            rootsAndEducation = json.optJSONObject("rootsAndEducation")?.let { RootsAndEducation.fromJson(it) } ?: RootsAndEducation(),
            communityAndCertifications = json.optJSONObject("communityAndCertifications")?.let { CommunityAndCertifications.fromJson(it) } ?: CommunityAndCertifications(),
            professionalExperience = json.optJSONObject("professionalExperience")?.let { ProfessionalExperience.fromJson(it) } ?: ProfessionalExperience(),
            currentGoal = json.optJSONObject("currentGoal")?.let { CurrentGoal.fromJson(it) } ?: CurrentGoal()
        )
    }
}
