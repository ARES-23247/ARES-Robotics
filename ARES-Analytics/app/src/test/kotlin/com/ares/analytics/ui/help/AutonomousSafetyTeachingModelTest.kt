package com.ares.analytics.ui.help

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutonomousSafetyTeachingModelTest {
    @Test
    fun `safe example is preview ready with explicit margin and resources`() {
        val result = evaluateAutonomousSafetyTeaching(AutonomousSafetyTeachingInput())

        assertTrue(result.startPoseInBounds)
        assertTrue(result.targetPoseInBounds)
        assertTrue(result.resourcesCompatible)
        assertTrue(result.timeoutHasMargin)
        assertTrue(result.previewReady)
        assertTrue(result.reasons.isEmpty())
        assertNotNull(result.estimatedDriveSeconds)
    }

    @Test
    fun `robot footprint blocks center points too close to field edge`() {
        val result = evaluateAutonomousSafetyTeaching(
            AutonomousSafetyTeachingInput(startXMeters = 0.1, targetYMeters = 8.1),
        )

        assertFalse(result.startPoseInBounds)
        assertFalse(result.targetPoseInBounds)
        assertFalse(result.previewReady)
        assertTrue(result.reasons.any { it.contains("starting pose") })
        assertTrue(result.reasons.any { it.contains("target pose") })
    }

    @Test
    fun `footprint bounds account for pose heading`() {
        val longRobot = AutonomousSafetyTeachingInput(
            robotLengthMeters = 1.0,
            robotWidthMeters = 0.2,
            startXMeters = 0.3,
            startYMeters = 1.0,
            startHeadingDegrees = 0.0,
        )

        assertFalse(evaluateAutonomousSafetyTeaching(longRobot).startPoseInBounds)
        assertTrue(evaluateAutonomousSafetyTeaching(longRobot.copy(startHeadingDegrees = 90.0)).startPoseInBounds)
    }

    @Test
    fun `timeout must include margin beyond idealized distance over speed`() {
        val result = evaluateAutonomousSafetyTeaching(
            AutonomousSafetyTeachingInput(
                startXMeters = 1.0,
                targetXMeters = 3.0,
                maxSpeedMetersPerSecond = 1.0,
                timeoutSeconds = 2.4,
            ),
        )

        assertFalse(result.timeoutHasMargin)
        assertFalse(result.previewReady)
        assertTrue(result.reasons.any { it.contains("25% margin") })
    }

    @Test
    fun `parallel branches fail closed when both claim drivebase`() {
        val result = evaluateAutonomousSafetyTeaching(
            AutonomousSafetyTeachingInput(mechanismClaimsDrivebase = true),
        )

        assertFalse(result.resourcesCompatible)
        assertFalse(result.previewReady)
        assertTrue(result.reasons.any { it.contains("parallel branches") })
    }

    @Test
    fun `missing catalog action and condition source are separate blockers`() {
        val result = evaluateAutonomousSafetyTeaching(
            AutonomousSafetyTeachingInput(
                mechanismActionAvailable = false,
                condition = TeachingAutoCondition.MISSING,
            ),
        )

        assertFalse(result.conditionUsable)
        assertFalse(result.previewReady)
        assertTrue(result.reasons.any { it.contains("action catalog") })
        assertTrue(result.reasons.any { it.contains("condition") })
    }

    @Test
    fun `continue on failure requires an explicitly optional action`() {
        val blocked = evaluateAutonomousSafetyTeaching(
            AutonomousSafetyTeachingInput(
                mechanismActionOptional = false,
                failurePolicy = TeachingAutoFailurePolicy.CONTINUE_OPTIONAL,
            ),
        )
        val ready = evaluateAutonomousSafetyTeaching(
            AutonomousSafetyTeachingInput(
                mechanismActionOptional = true,
                failurePolicy = TeachingAutoFailurePolicy.CONTINUE_OPTIONAL,
            ),
        )

        assertFalse(blocked.failureBehaviorValid)
        assertFalse(blocked.previewReady)
        assertTrue(ready.failureBehaviorValid)
        assertTrue(ready.previewReady)
    }

    @Test
    fun `false but available condition skips action without pretending validation failed`() {
        val result = evaluateAutonomousSafetyTeaching(
            AutonomousSafetyTeachingInput(condition = TeachingAutoCondition.NOT_READY),
        )

        assertTrue(result.conditionUsable)
        assertTrue(result.previewReady)
        assertTrue(result.planSummary.any { it.contains("action is skipped") })
    }
}
