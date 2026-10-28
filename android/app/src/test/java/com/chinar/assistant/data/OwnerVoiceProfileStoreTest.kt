package com.chinar.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerVoiceProfileStoreTest {

    @Test
    fun testVectorSerializationRoundtrip() {
        // 128-d simulated Kaldi x-vector
        val original = FloatArray(128) { i -> (i - 64) * 0.015f }
        val serialized = original.joinToString(",") { it.toString() }

        val parts = serialized.split(",")
        val restored = FloatArray(parts.size) { i -> parts[i].toFloat() }

        assertEquals(original.size, restored.size)
        for (i in original.indices) {
            assertEquals(original[i], restored[i], 0.0001f)
        }
    }

    @Test
    fun testEnrollmentDetailsDefaults() {
        val details = VoiceEnrollmentDetails(
            isEnrolled = false,
            enrolledAtMillis = 0L,
            sampleCount = 0,
            ownerName = "Sahil Khan",
            dimension = 0
        )

        assertFalse(details.isEnrolled)
        assertEquals("Sahil Khan", details.ownerName)
        assertEquals(0, details.dimension)
    }

    @Test
    fun testEnrollmentDetailsWhenEnrolled() {
        val details = VoiceEnrollmentDetails(
            isEnrolled = true,
            enrolledAtMillis = 1700000000000L,
            sampleCount = 3,
            ownerName = "Sahil Khan",
            dimension = 128
        )

        assertTrue(details.isEnrolled)
        assertEquals("Sahil Khan", details.ownerName)
        assertEquals(3, details.sampleCount)
        assertEquals(128, details.dimension)
    }
}
