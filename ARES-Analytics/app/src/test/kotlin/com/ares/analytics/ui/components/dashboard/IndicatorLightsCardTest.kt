package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.RobotLightingKind
import com.ares.analytics.service.RobotLightingReading
import com.ares.analytics.service.robotLightingReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndicatorLightsCardTest {
    @Test
    fun `generated indicator output is discovered without a legacy duplicate topic`() {
        assertEquals(
            RobotLightingReading(
                "indicator-lights/primaryIndicator",
                RobotLightingKind.INDICATOR,
                0.472,
            ),
            robotLightingReading(
                "Subsystems/indicator-lights/AppliedOutputs/primaryIndicator/INDICATOR_LIGHT",
                0.472,
            ),
        )
    }

    @Test
    fun `generated Prism output remains distinguishable from indicator positions`() {
        assertEquals(
            RobotLightingReading("prism/prismDriver", RobotLightingKind.PRISM, 1005.0),
            robotLightingReading("Subsystems/prism/AppliedOutputs/prismDriver/PRISM_DRIVER", 1005.0),
        )
    }

    @Test
    fun `legacy indicator recordings remain readable`() {
        assertEquals(
            RobotLightingReading("left", RobotLightingKind.INDICATOR, 0.611),
            robotLightingReading("Superstructure/IndicatorLight/left", 0.611),
        )
    }

    @Test
    fun `unrelated telemetry is ignored`() {
        assertNull(robotLightingReading("Drive/Pose_X", 1.0))
    }

    @Test
    fun `generated hardware IDs become novice-facing Lightbot labels`() {
        assertEquals("Left indicator", lightingDisplayName("indicator-lights/primaryIndicator"))
        assertEquals("Right indicator", lightingDisplayName("indicator-lights/secondaryIndicator"))
        assertEquals("Prism underbody", lightingDisplayName("prism/prismDriver"))
        assertEquals("Status Light", lightingDisplayName("custom/statusLight"))
    }
}
