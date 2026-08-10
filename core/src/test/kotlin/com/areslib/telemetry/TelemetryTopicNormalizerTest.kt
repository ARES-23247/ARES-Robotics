package com.areslib.telemetry

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TelemetryTopicNormalizerTest {

    @Test
    fun `aliases are not rewritten`() {
        assertEquals("Drive/Drive_Heading", TelemetryTopicNormalizer.normalizeTopic("Drive/Drive_Heading"))
        assertEquals("pinpoint_heading", TelemetryTopicNormalizer.normalizeTopic("pinpoint_heading"))
        assertEquals("SysId_Data", TelemetryTopicNormalizer.normalizeTopic("SysId_Data"))
    }

    @Test
    fun `canonical and unknown topics are stable and a leading slash is removed`() {
        val canonical = listOf(
            TelemetryTopicConstants.DRIVE_POSE_X,
            TelemetryTopicConstants.DRIVE_POSE_Y,
            TelemetryTopicConstants.DRIVE_POSE_HEADING,
            TelemetryTopicConstants.DRIVE_ODOM_X,
            TelemetryTopicConstants.DRIVE_ODOM_Y,
            TelemetryTopicConstants.DRIVE_ODOM_HEADING,
            TelemetryTopicConstants.ESTIMATED_POSE_X,
            TelemetryTopicConstants.ESTIMATED_POSE_Y,
            TelemetryTopicConstants.ESTIMATED_POSE_HEADING
        )

        for (topic in canonical) {
            assertEquals(topic, TelemetryTopicNormalizer.normalizeTopic(topic))
            assertEquals(topic, TelemetryTopicNormalizer.normalizeTopic("/$topic"))
        }
        assertEquals("Custom/Diagnostic", TelemetryTopicNormalizer.normalizeTopic("/Custom/Diagnostic"))
    }

    @Test
    fun `all leading slashes collapse to the same canonical topic`() {
        assertEquals(
            TelemetryTopicConstants.DRIVE_POSE_HEADING,
            TelemetryTopicNormalizer.normalizeTopic("////Drive/Pose_Heading")
        )
        assertEquals(
            "pinpoint_heading",
            TelemetryTopicNormalizer.normalizeTopic("///pinpoint_heading")
        )
        assertEquals(
            "Custom/Diagnostic",
            TelemetryTopicNormalizer.normalizeTopic("////Custom/Diagnostic")
        )
    }
}
