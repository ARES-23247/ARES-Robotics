package org.aresfirst.marvin

import com.areslib.Store
import com.areslib.control.assist.ShotResult
import com.areslib.control.assist.ShotSetup
import org.aresfirst.marvin.marvin.MarvinConfig
import org.aresfirst.marvin.marvin.MarvinCowlController
import org.aresfirst.marvin.marvin.MarvinReducer
import org.aresfirst.marvin.marvin.MarvinState
import org.aresfirst.marvin.marvin.marvin
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AresFrcRemediationTest {
    @Test
    fun testShooterRearwardHeading() {
        val rearwardResult = ShotResult()
        val pose = Pose2d(0.0, 0.0, Rotation2d(0.0))
        val speeds = ChassisSpeeds(0.0, 0.0, 0.0)
        val target = Translation2d(1.0, 1.0)

        ShotSetup(MarvinConfig.SHOT_CONFIG).calculate(
            pose,
            speeds,
            target,
            rearwardResult,
        )
        assertTrue(MarvinConfig.SHOT_CONFIG.shooterFacesRearward)
        val forwardBearing = kotlin.math.atan2(
            target.y - MarvinConfig.SHOT_CONFIG.shooterOffsetY,
            target.x - MarvinConfig.SHOT_CONFIG.shooterOffsetX,
        )
        val expectedRearwardHeading = com.areslib.math.wrapAngle(forwardBearing + Math.PI)
        assertEquals(expectedRearwardHeading, rearwardResult.robotTargetHeadingRad, 1e-9)
    }

    @Test
    fun testCowlAngleUsesRotations() {
        val store = Store(
            initialState = RobotState(
                superstructure = SuperstructureState(custom = MarvinState())
            ),
            reducer = MarvinReducer::reduce,
        )

        MarvinCowlController(store).setCowlAngleRotations(MarvinConfig.cowlMaxRotations + 1.0)

        assertEquals(
            MarvinConfig.cowlMaxRotations,
            store.state.superstructure.marvin.cowl.targetAngleRotations,
            1e-9,
        )
    }
}
