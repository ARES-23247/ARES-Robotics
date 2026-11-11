package com.ares.analytics.ui.components.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryTopicQuickPickerTest {

    @Test
    fun `applySignalPreset populates topic label unit bounds and warning thresholds`() {
        val loopPreset = commonTelemetrySignalPresets.first { it.id == "loop-time" }
        val config = applySignalPreset(loopPreset)

        assertEquals("Robot/LoopTimeMs", config.topic)
        assertEquals("Loop Cycle Time", config.label)
        assertEquals("ms", config.unit)
        assertEquals("bar", config.displayMode)
        assertEquals(0.0, config.minimum)
        assertEquals(40.0, config.maximum)
        assertEquals(null, config.warningLow)
        assertEquals(25.0, config.warningHigh)
    }

    @Test
    fun `commonTelemetrySignalPresets have valid bounds and non-blank topics`() {
        assertTrue(commonTelemetrySignalPresets.isNotEmpty())
        commonTelemetrySignalPresets.forEach { preset ->
            assertTrue(preset.topic.isNotBlank(), "Preset ${preset.id} topic must not be blank")
            assertTrue(preset.label.isNotBlank(), "Preset ${preset.id} label must not be blank")
            assertTrue(preset.minimum < preset.maximum, "Preset ${preset.id} minimum must be less than maximum")
        }
    }

    @Test
    fun `filterTelemetryTopicSuggestions normalizes deduplicates and filters case-insensitively`() {
        val rawTopics = listOf(
            "/Robot/LoopTimeMs",
            "Robot/LoopTimeMs",
            "/Drive/Pose_X",
            "Drive/Pose_Y",
            "Drive/Pose_Heading",
            "   ",
        )

        val allNormalized = filterTelemetryTopicSuggestions(rawTopics, query = "")
        assertEquals(
            listOf(
                "Drive/Pose_Heading",
                "Drive/Pose_X",
                "Drive/Pose_Y",
                "Robot/LoopTimeMs",
            ),
            allNormalized,
        )

        val poseMatches = filterTelemetryTopicSuggestions(rawTopics, query = "pose_")
        assertEquals(
            listOf(
                "Drive/Pose_Heading",
                "Drive/Pose_X",
                "Drive/Pose_Y",
            ),
            poseMatches,
        )
    }

    @Test
    fun `chartStarterBundles provide ready-to-plot multi-topic groups`() {
        assertEquals(3, chartStarterBundles.size)
        chartStarterBundles.forEach { bundle ->
            assertTrue(bundle.title.isNotBlank())
            assertTrue(bundle.topics.size >= 2, "Bundle ${bundle.id} should plot at least two topics")
        }
    }
}
