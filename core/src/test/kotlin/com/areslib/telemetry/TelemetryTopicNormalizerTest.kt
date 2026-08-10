package com.areslib.telemetry

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TelemetryTopicNormalizerTest {

    @Test
    fun `legacy aliases normalize to canonical pose vision odometry and sysid topics`() {
        val cases = mapOf(
            "Drive/Drive_Heading" to TelemetryTopicConstants.DRIVE_POSE_HEADING,
            "/Drive/Drive_Heading" to TelemetryTopicConstants.DRIVE_POSE_HEADING,
            "pinpoint_x" to TelemetryTopicConstants.DRIVE_ODOM_X,
            "pinpoint/x" to TelemetryTopicConstants.DRIVE_ODOM_X,
            "pinpoint_y" to TelemetryTopicConstants.DRIVE_ODOM_Y,
            "pinpoint/y" to TelemetryTopicConstants.DRIVE_ODOM_Y,
            "pinpoint_heading" to TelemetryTopicConstants.DRIVE_ODOM_HEADING,
            "pinpoint/heading" to TelemetryTopicConstants.DRIVE_ODOM_HEADING,
            "Vision/Pose/X" to TelemetryTopicConstants.VISION_POSE_X,
            "Vision/Pose/Y" to TelemetryTopicConstants.VISION_POSE_Y,
            "Vision/Pose/Heading" to TelemetryTopicConstants.VISION_POSE_HEADING,
            "SysId_Data" to "SysId/Data",
            "sysid_data" to "SysId/Data"
        )

        for ((input, expected) in cases) {
            assertEquals(expected, TelemetryTopicNormalizer.normalizeTopic(input), input)
        }
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
            TelemetryTopicNormalizer.normalizeTopic("////Drive/Drive_Heading")
        )
        assertEquals(
            TelemetryTopicConstants.DRIVE_ODOM_HEADING,
            TelemetryTopicNormalizer.normalizeTopic("///pinpoint_heading")
        )
        assertEquals(
            "Custom/Diagnostic",
            TelemetryTopicNormalizer.normalizeTopic("////Custom/Diagnostic")
        )
    }
}
