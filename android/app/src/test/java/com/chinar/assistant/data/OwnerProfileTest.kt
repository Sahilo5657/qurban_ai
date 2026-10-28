package com.chinar.assistant.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerProfileTest {

    @Test
    fun testDefaultProfileContainsRequiredIdentityAndRoots() {
        val profile = OwnerProfile()
        assertEquals("Sahil Khan", profile.basicIdentity.ownerName)
        assertEquals("Qurban", profile.basicIdentity.assistantName)

        assertTrue(profile.rootsAndEducation.birthplace.contains("Khona Kaly"))
        assertTrue(profile.rootsAndEducation.birthplace.contains("Bajaur"))
        assertTrue(profile.rootsAndEducation.currentResidence.contains("Peshawar"))
        assertTrue(profile.rootsAndEducation.university.contains("IMSciences"))
        assertTrue(profile.rootsAndEducation.university.contains("BS Software Engineering"))

        assertTrue(profile.professionalExperience.currentRole.contains("Quite Logic"))
        assertTrue(profile.professionalExperience.thesisTitle.contains("Hospital Patient Flow"))
        assertTrue(profile.currentGoal.primaryGoal.contains("Taiwan"))
    }

    @Test
    fun testJsonSerializationRoundtrip() {
        val original = OwnerProfile()
        val json = original.toJson()
        val restored = OwnerProfile.fromJson(json)

        assertEquals(original.basicIdentity.ownerName, restored.basicIdentity.ownerName)
        assertEquals(original.basicIdentity.assistantName, restored.basicIdentity.assistantName)
        assertEquals(original.rootsAndEducation.birthplace, restored.rootsAndEducation.birthplace)
        assertEquals(original.rootsAndEducation.university, restored.rootsAndEducation.university)
        assertEquals(original.professionalExperience.currentRole, restored.professionalExperience.currentRole)
        assertEquals(original.professionalExperience.thesisTitle, restored.professionalExperience.thesisTitle)
        assertEquals(original.currentGoal.primaryGoal, restored.currentGoal.primaryGoal)
        assertEquals(original.communityAndCertifications.certifications.size, restored.communityAndCertifications.certifications.size)
    }

    @Test
    fun testSystemInstructionFormattingContext() {
        val profile = OwnerProfile()
        val contextPrompt = profile.formatSystemInstructionContext()

        assertTrue(contextPrompt.contains("Sahil Khan"))
        assertTrue(contextPrompt.contains("Qurban"))
        assertTrue(contextPrompt.contains("IMSciences"))
        assertTrue(contextPrompt.contains("Quite Logic"))
        assertTrue(contextPrompt.contains("Taiwan"))

        // Must not contain any biometric vectors or embeddings
        assertFalse(contextPrompt.contains("vector"))
        assertFalse(contextPrompt.contains("embedding"))
    }

    @Test
    fun testProfileIsDecoupledFromBiometrics() {
        val profile = OwnerProfile()
        val jsonString = profile.toJson().toString()

        // Profile must not contain biometric keys
        assertFalse(jsonString.contains("spk"))
        assertFalse(jsonString.contains("xvector"))
        assertFalse(jsonString.contains("biometricTemplate"))
    }

    @Test
    fun testEditingProfileFields() {
        val profile = OwnerProfile(
            basicIdentity = BasicIdentity(ownerName = "Sahil", assistantName = "Chinar AI"),
            currentGoal = CurrentGoal(primaryGoal = "Studying AI Master's in Taiwan")
        )

        assertEquals("Sahil", profile.basicIdentity.ownerName)
        assertEquals("Chinar AI", profile.basicIdentity.assistantName)
        assertEquals("Studying AI Master's in Taiwan", profile.currentGoal.primaryGoal)
    }
}
