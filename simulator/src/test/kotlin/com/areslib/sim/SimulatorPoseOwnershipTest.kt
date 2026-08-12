package com.areslib.sim

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.sim.opmode.SimOpModeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimulatorPoseOwnershipTest {
    @Test
    fun `autonomous origin is explicit and synchronizes physics pinpoint and redux`() {
        val authoredOrigin = Pose2d(0.0, 0.0, Rotation2d(0.0))
        val allianceSpawn = Pose2d(1.25, -0.75, Rotation2d(Math.PI / 2.0))
        var physicsApplied: Pose2d? = null
        var pinpointApplied: Pose2d? = null
        var reduxApplied: Pose2d? = null

        val selected = synchronizeSimulatorStartPose(
            modeKind = SimOpModeKind.AUTONOMOUS,
            opModePose = authoredOrigin,
            physicsPose = allianceSpawn,
            applyPhysicsPose = { physicsApplied = it },
            initializePinpoint = { pinpointApplied = it },
            resetReduxPose = { reduxApplied = it },
        )

        assertPose(authoredOrigin, selected)
        assertPose(authoredOrigin, requireNotNull(physicsApplied))
        assertPose(authoredOrigin, requireNotNull(pinpointApplied))
        assertPose(authoredOrigin, requireNotNull(reduxApplied))
    }

    @Test
    fun `teleop retains configured alliance physics spawn`() {
        val staleLocalization = Pose2d(0.0, 0.0, Rotation2d(0.0))
        val allianceSpawn = Pose2d(-1.1, 0.9, Rotation2d(-Math.PI / 2.0))
        var physicsApplied: Pose2d? = null
        var pinpointApplied: Pose2d? = null
        var reduxApplied: Pose2d? = null

        val selected = synchronizeSimulatorStartPose(
            modeKind = SimOpModeKind.TELEOP,
            opModePose = staleLocalization,
            physicsPose = allianceSpawn,
            applyPhysicsPose = { physicsApplied = it },
            initializePinpoint = { pinpointApplied = it },
            resetReduxPose = { reduxApplied = it },
        )

        assertPose(allianceSpawn, selected)
        assertNull("TeleOp must not move the configured physics spawn", physicsApplied)
        assertPose(allianceSpawn, requireNotNull(pinpointApplied))
        assertPose(allianceSpawn, requireNotNull(reduxApplied))
    }

    private fun assertPose(expected: Pose2d, actual: Pose2d) {
        assertEquals(expected.x, actual.x, 0.0)
        assertEquals(expected.y, actual.y, 0.0)
        assertEquals(expected.heading.radians, actual.heading.radians, 0.0)
    }
}
