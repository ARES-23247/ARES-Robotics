package org.firstinspires.ftc.teamcode.opmodes

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.math.estimation.PoseEstimator
import com.areslib.pathing.NamedCommands
import com.areslib.state.RobotFieldManager
import com.areslib.tuning.TuningApplyContext
import com.areslib.tuning.TuningManager
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.Servo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*

/**
 * Validates that AresRobot wires its TuningApplyContext contextProvider to the real base
 * robot's calibration arming and stationary neutral output hold states.
 *
 * Verifies that outputsNeutralAndInhibited actively tracks isCalibrationNeutralOutputHoldActive,
 * allowing DISABLED_ONLY parameters to be applied only when an active STOP lease holds outputs neutral.
 */
class AresTuningOperatorAuditTest {

    @Test
    fun `contextProvider reflects real base arming and neutral output hold states`() {
        val field = RobotFieldManager.activeConfig
        val tags = PoseEstimator.activeTags
        val map = mock(HardwareMap::class.java)
        doReturn(mock(Servo::class.java)).`when`(map).get(eq(Servo::class.java), anyString())

        var capturedContextProvider: (() -> TuningApplyContext)? = null

        try {
            mockConstruction(TuningManager::class.java) { _, context ->
                @Suppress("UNCHECKED_CAST")
                capturedContextProvider = context.arguments()[2] as? () -> TuningApplyContext
            }.use {
                mockConstruction(FtcMecanumRobot::class.java, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)) { base, _ ->
                    doReturn(null).`when`(base).fatalUpdateFailure
                }.use { constructedBase ->
                    val robot = AresRobot(map)
                    try {
                        val base = constructedBase.constructed().single()
                        val contextProvider = requireNotNull(capturedContextProvider)

                        // 1. Unarmed state: session not armed, outputs not holding neutral
                        doReturn(false).`when`(base).isCalibrationModeArmed
                        doReturn(false).`when`(base).isCalibrationNeutralOutputHoldActive
                        val unarmed = contextProvider()
                        assertFalse("Unarmed session must report sessionArmed = false", unarmed.sessionArmed)
                        assertFalse("FTC OpMode must report robotDisabled = false", unarmed.robotDisabled)
                        assertFalse("Unarmed session must not hold neutral outputs", unarmed.outputsNeutralAndInhibited)

                        // 2. Armed during active routine: armed, but routine moving motors (hold inactive)
                        doReturn(true).`when`(base).isCalibrationModeArmed
                        doReturn(false).`when`(base).isCalibrationNeutralOutputHoldActive
                        val activeMotion = contextProvider()
                        assertTrue("Armed session must report sessionArmed = true", activeMotion.sessionArmed)
                        assertFalse("FTC OpMode must report robotDisabled = false", activeMotion.robotDisabled)
                        assertFalse("Active motion routine must inhibit disabled-only edits", activeMotion.outputsNeutralAndInhibited)

                        // 3. Armed stationary hold: fresh STOP lease holding neutral outputs
                        doReturn(true).`when`(base).isCalibrationModeArmed
                        doReturn(true).`when`(base).isCalibrationNeutralOutputHoldActive
                        val neutralHold = contextProvider()
                        assertTrue("Armed session must report sessionArmed = true", neutralHold.sessionArmed)
                        assertFalse("FTC OpMode must report robotDisabled = false", neutralHold.robotDisabled)
                        assertTrue("Active STOP lease must provide outputsNeutralAndInhibited", neutralHold.outputsNeutralAndInhibited)

                        // Re-read the base after disarm; do not retain the prior neutral hold.
                        doReturn(false).`when`(base).isCalibrationModeArmed
                        doReturn(false).`when`(base).isCalibrationNeutralOutputHoldActive
                        val disarmed = contextProvider()
                        assertFalse(disarmed.sessionArmed)
                        assertFalse(disarmed.outputsNeutralAndInhibited)
                    } finally {
                        robot.close()
                    }
                    verify(constructedBase.constructed().single(), times(1)).close()
                }
            }
        } finally {
            RobotFieldManager.setActiveConfig(field)
            PoseEstimator.activeTags = tags
            NamedCommands.clear()
        }
    }
}
