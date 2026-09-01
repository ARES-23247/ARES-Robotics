package com.areslib.subsystem

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.kinematics.MecanumKinematics
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.reducer.rootReducer
import com.areslib.state.RobotState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FieldCentricRotationE2ETest {

    private val anglesDeg = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)

    @Test
    fun testFieldCentricDriveAtAllRotations() {
        val kinematics = MecanumKinematics(0.45, 0.45)

        for (angleDeg in anglesDeg) {
            val angleRad = Math.toRadians(angleDeg)
            val store = Store(RobotState(), ::rootReducer)
            val facade = MecanumDriveFacade(store)

            // Set the robot pose heading to angleRad
            store.dispatch(RobotAction.PoseUpdate(
                xMeters = 0.0,
                yMeters = 0.0,
                headingRadians = angleRad,
                timestampMs = System.currentTimeMillis(),
                isReset = true
            ))

            val expectedHeading = com.areslib.math.wrapAngle(angleRad)
            val currentHeading = facade.pose.heading.radians
            assertEquals(expectedHeading, currentHeading, 1e-6, "Pose heading should match initialized angle $angleDeg°")

            // 1. Test Field Forward effort (vx = 0.0, vy = 1.0) -> MUST result in fieldVy = 1.0 and fieldVx = 0.0
            var dispatchedIntent: RobotAction.JoystickDriveIntent? = null
            val storeSpy = Store(store.state) { state, action ->
                if (action is RobotAction.JoystickDriveIntent) {
                    dispatchedIntent = action
                }
                rootReducer(state, action)
            }
            val facadeSpy = MecanumDriveFacade(storeSpy)

            facadeSpy.driveFieldRelativeNormalized(vx = 0.0, vy = 1.0, omega = 0.0)
            assertNotNull(dispatchedIntent, "JoystickDriveIntent must be dispatched for angle $angleDeg°")

            val intent = requireNotNull(dispatchedIntent)
            val robotVx = intent.targetXVelocity
            val robotVy = intent.targetYVelocity

            // Re-project robot velocities back to field frame:
            // fieldVx = robotVx * cos(heading) - robotVy * sin(heading)
            // fieldVy = robotVx * sin(heading) + robotVy * cos(heading)
            val cosH = kotlin.math.cos(angleRad)
            val sinH = kotlin.math.sin(angleRad)

            val fieldVx = robotVx * cosH - robotVy * sinH
            val fieldVy = robotVx * sinH + robotVy * cosH

            assertEquals(0.0, fieldVx, 1e-5, "At angle $angleDeg°, Forward command must yield 0 field X velocity")
            assertEquals(facadeSpy.maxSpeedMps, fieldVy, 1e-5, "At angle $angleDeg°, full input must yield max field Y velocity")

            // 2. Verify wheel kinematics produces pure field velocity without diagonal drift
            val speeds = DoubleArray(4)
            kinematics.toWheelSpeeds(robotVx, robotVy, 0.0, speeds)

            val fl = speeds[0]
            val fr = speeds[1]
            val rl = speeds[2]
            val rr = speeds[3]

            val rawVx = (fl + fr + rl + rr) / 4.0
            val rawVy = (-fl + fr + rl - rr) / 4.0

            val reconstructedFieldVx = rawVx * cosH - rawVy * sinH
            val reconstructedFieldVy = rawVx * sinH + rawVy * cosH

            assertEquals(0.0, reconstructedFieldVx, 1e-5, "Reconstructed field X velocity at angle $angleDeg° must be 0.0")
            assertEquals(facadeSpy.maxSpeedMps, reconstructedFieldVy, 1e-5, "Reconstructed field Y velocity must equal max speed at $angleDeg°")

            // 3. Test Field Right effort (vx = 1.0, vy = 0.0) -> MUST result in fieldVx = 1.0 and fieldVy = 0.0
            dispatchedIntent = null
            facadeSpy.driveFieldRelativeNormalized(vx = 1.0, vy = 0.0, omega = 0.0)
            assertNotNull(dispatchedIntent)

            val rightRobotVx = dispatchedIntent!!.targetXVelocity
            val rightRobotVy = dispatchedIntent!!.targetYVelocity

            val rightFieldVx = rightRobotVx * cosH - rightRobotVy * sinH
            val rightFieldVy = rightRobotVx * sinH + rightRobotVy * cosH

            assertEquals(facadeSpy.maxSpeedMps, rightFieldVx, 1e-5, "At angle $angleDeg°, full input must yield max field X velocity")
            assertEquals(0.0, rightFieldVy, 1e-5, "At angle $angleDeg°, Right command must yield 0 field Y velocity")
        }

        println("[E2E Rotation Test] PASSED 100% across all 8 cardinal & intercardinal headings (0°, 45°, 90°, 135°, 180°, 225°, 270°, 315°).")
    }
}
