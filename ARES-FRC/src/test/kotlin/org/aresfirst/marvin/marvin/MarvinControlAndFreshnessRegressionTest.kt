package org.aresfirst.marvin.marvin

import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarvinControlAndFreshnessRegressionTest {

    private fun newStore(): Store = Store(
        RobotState(superstructure = SuperstructureState(custom = MarvinState()))
    ) { state, action -> MarvinReducer.reduce(state, action) }

    @Test
    fun `climber mode transitions preserve explicit targets and last command selects output mode`() {
        val initial = RobotState(superstructure = SuperstructureState(custom = MarvinState()))

        val positionMode = MarvinReducer.reduce(
            initial,
            SetClimberPositionRotations(rotations = 0.75, timestampMs = 1_000L)
        )
        assertEquals(ClimberControlMode.POSITION_ROTATIONS, positionMode.superstructure.marvin.climber.controlMode)
        assertEquals(0.75, positionMode.superstructure.marvin.climber.targetPositionRotations)

        val voltageMode = MarvinReducer.reduce(
            positionMode,
            SetClimberVoltage(volts = -4.0, timestampMs = 1_020L)
        )
        assertEquals(ClimberControlMode.VOLTAGE, voltageMode.superstructure.marvin.climber.controlMode)
        assertEquals(-4.0, voltageMode.superstructure.marvin.climber.targetVoltage)
        assertEquals(
            0.75,
            voltageMode.superstructure.marvin.climber.targetPositionRotations,
            "Changing mode must not reinterpret or erase the calibrated mechanism-rotation target"
        )

        val positionModeAgain = MarvinReducer.reduce(
            voltageMode,
            SetClimberPositionRotations(rotations = 0.25, timestampMs = 1_040L)
        )
        assertEquals(ClimberControlMode.POSITION_ROTATIONS, positionModeAgain.superstructure.marvin.climber.controlMode)
        assertEquals(0.25, positionModeAgain.superstructure.marvin.climber.targetPositionRotations)
        assertEquals(-4.0, positionModeAgain.superstructure.marvin.climber.targetVoltage)
    }

    @Test
    fun `flywheel freshness and heading interlocks fail closed then recover`() {
        val store = newStore()
        val flywheel = MarvinFlywheelController(store)
        val cowl = MarvinCowlController(store)
        val feeder = MarvinFeederController(store)

        store.dispatch(SetFlywheelSpeed(4_000.0, 1_000L))
        store.dispatch(SetCowlAngle(0.5, 1_000L))
        store.dispatch(
            SuperstructureSensorUpdate(
                flywheelRpm = 4_000.0,
                cowlAngleRotations = 0.5,
                intakeAngle = 0.0,
                pieceDetected = false,
                flywheelVelocityValid = true,
                flywheelAllMotorsAtTarget = true,
                cowlAngleValid = false,
                timestampMs = 1_000L
            )
        )

        assertTrue(flywheel.isRpmAligned(4_000.0))
        assertFalse(cowl.isAngleAligned(0.5))
        feeder.updateFeeders(rpmAligned = true, headingAligned = true, cowlReady = false, runFloorRollers = true)
        assertEquals(0.0, store.state.superstructure.marvin.feeder.targetVelocityRps)
        assertEquals(0.0, store.state.superstructure.marvin.floor.targetVelocityRps)

        store.dispatch(
            SuperstructureSensorUpdate(
                flywheelRpm = 4_000.0,
                cowlAngleRotations = 0.5,
                intakeAngle = 0.0,
                pieceDetected = false,
                flywheelVelocityValid = true,
                flywheelAllMotorsAtTarget = true,
                cowlAngleValid = true,
                timestampMs = 1_020L
            )
        )
        assertTrue(cowl.isAngleAligned(0.5))
        feeder.updateFeeders(rpmAligned = true, headingAligned = true, cowlReady = true, runFloorRollers = true)
        assertTrue(store.state.superstructure.marvin.transferActive)
        assertEquals(MarvinConfig.FEEDER_SHOOT_SPEED_RPS, store.state.superstructure.marvin.feeder.targetVelocityRps)
        assertEquals(MarvinConfig.FEEDER_SHOOT_SPEED_RPS, store.state.superstructure.marvin.floor.targetVelocityRps)

        // Once authorized, a transfer finishes even if alignment moves out of tolerance.
        feeder.updateFeeders(rpmAligned = false, headingAligned = false, cowlReady = false, runFloorRollers = true)
        assertEquals(MarvinConfig.FEEDER_SHOOT_SPEED_RPS, store.state.superstructure.marvin.feeder.targetVelocityRps)
        feeder.cancelTransfer()
        assertFalse(store.state.superstructure.marvin.transferActive)
        assertEquals(0.0, store.state.superstructure.marvin.feeder.targetVelocityRps)

        // A failed refresh can carry the same numeric sample as the last good loop.
        // Validity must still force the observation to zero and close the feeder gate.
        store.dispatch(
            SuperstructureSensorUpdate(
                flywheelRpm = 4_000.0,
                cowlAngleRotations = 0.5,
                intakeAngle = 0.0,
                pieceDetected = false,
                flywheelVelocityValid = false,
                cowlAngleValid = true,
                timestampMs = 1_040L
            )
        )

        assertFalse(store.state.superstructure.marvin.flywheel.velocityValid)
        assertEquals(0.0, store.state.superstructure.marvin.flywheel.velocityRpm)
        assertFalse(flywheel.isRpmAligned(4_000.0))
        feeder.updateFeeders(
            rpmAligned = flywheel.isRpmAligned(4_000.0),
            headingAligned = true,
            cowlReady = cowl.isAngleAligned(0.5),
            runFloorRollers = true
        )
        assertEquals(0.0, store.state.superstructure.marvin.feeder.targetVelocityRps)
        assertEquals(0.0, store.state.superstructure.marvin.floor.targetVelocityRps)

        store.dispatch(
            SuperstructureSensorUpdate(
                flywheelRpm = 4_000.0,
                cowlAngleRotations = 0.5,
                intakeAngle = 0.0,
                pieceDetected = false,
                flywheelVelocityValid = true,
                flywheelAllMotorsAtTarget = true,
                cowlAngleValid = true,
                timestampMs = 1_060L
            )
        )
        assertTrue(flywheel.isRpmAligned(4_000.0))
        feeder.updateFeeders(
            rpmAligned = flywheel.isRpmAligned(4_000.0),
            headingAligned = true,
            cowlReady = cowl.isAngleAligned(0.5),
            runFloorRollers = false
        )
        assertEquals(MarvinConfig.FEEDER_SHOOT_SPEED_RPS, store.state.superstructure.marvin.feeder.targetVelocityRps)
        assertEquals(0.0, store.state.superstructure.marvin.floor.targetVelocityRps)
    }

    @Test
    fun `latched all stop atomically zeros and blocks mechanism rearming until cleared`() {
        val store = newStore()
        store.dispatch(SetFlywheelSpeed(4_000.0))
        store.dispatch(SetFlywheelActive(true))
        store.dispatch(SetCowlAngle(1.0))
        store.dispatch(SetIntakePivot(true))
        store.dispatch(SetIntakeRollers(10.0))
        store.dispatch(SetFeederSpeed(8.0))
        store.dispatch(SetFloorSpeed(8.0))
        store.dispatch(SetClimberVoltage(6.0))
        store.dispatch(StartTransfer())
        store.dispatch(com.areslib.action.RobotAction.JoystickDriveIntent(2.0, -1.0, 0.5))

        store.dispatch(SetMechanismSafetyInhibit(true))

        val stopped = store.state.superstructure.marvin
        assertTrue(stopped.mechanismSafetyInhibited)
        assertFalse(stopped.flywheelActive)
        assertFalse(stopped.transferActive)
        assertFalse(stopped.intake.isDeployed)
        assertEquals(0.0, stopped.flywheel.targetVelocityRpm)
        assertEquals(0.0, stopped.intake.targetRollerVelocityRps)
        assertEquals(0.0, stopped.feeder.targetVelocityRps)
        assertEquals(0.0, stopped.floor.targetVelocityRps)
        assertEquals(0.0, stopped.climber.targetVoltage)
        assertEquals(0.0, store.state.drive.xVelocityMetersPerSecond)
        assertEquals(0.0, store.state.drive.yVelocityMetersPerSecond)
        assertEquals(0.0, store.state.drive.angularVelocityRadiansPerSecond)
        assertEquals(com.areslib.state.DriveMode.X_BRAKE, store.state.drive.driveMode)
        assertTrue(store.state.drive.isXLock)

        store.dispatch(SetFlywheelSpeed(5_000.0))
        store.dispatch(SetFlywheelActive(true))
        store.dispatch(SetFeederSpeed(10.0))
        store.dispatch(com.areslib.action.RobotAction.JoystickDriveIntent(3.0, 2.0, 1.0))
        assertEquals(0.0, store.state.superstructure.marvin.flywheel.targetVelocityRpm)
        assertFalse(store.state.superstructure.marvin.flywheelActive)
        assertEquals(0.0, store.state.superstructure.marvin.feeder.targetVelocityRps)
        assertEquals(0.0, store.state.drive.xVelocityMetersPerSecond)
        assertEquals(0.0, store.state.drive.yVelocityMetersPerSecond)
        assertEquals(0.0, store.state.drive.angularVelocityRadiansPerSecond)

        store.dispatch(SetMechanismSafetyInhibit(false))
        store.dispatch(SetFlywheelSpeed(5_000.0))
        assertFalse(store.state.superstructure.marvin.mechanismSafetyInhibited)
        assertEquals(5_000.0, store.state.superstructure.marvin.flywheel.targetVelocityRpm)
    }

    @Test
    fun `persistent mechanism fault survives temporary mode clear until explicit recovery`() {
        val store = newStore()
        store.dispatch(LatchMechanismSafetyFault("controller failed"))

        store.dispatch(SetMechanismSafetyInhibit(false))
        var marvin = store.state.superstructure.marvin
        assertTrue(marvin.mechanismSafetyInhibited)
        assertTrue(marvin.mechanismSafetyFaultLatched)
        assertEquals("controller failed", marvin.mechanismSafetyFaultReason)

        store.dispatch(ClearMechanismSafetyFault("disabled dual-operator recovery"))
        marvin = store.state.superstructure.marvin
        assertTrue(marvin.mechanismSafetyInhibited, "fault clear alone must not enable outputs")
        assertFalse(marvin.mechanismSafetyFaultLatched)
        assertEquals("", marvin.mechanismSafetyFaultReason)

        store.dispatch(SetMechanismSafetyInhibit(false))
        assertFalse(store.state.superstructure.marvin.mechanismSafetyInhibited)
    }

    @Test
    fun `position freshness clears stale collision observations and recovers`() {
        val store = newStore()

        store.dispatch(SuperstructureSensorUpdate(
            flywheelRpm = 0.0,
            cowlAngleRotations = 1.0,
            cowlAngleValid = true,
            intakeAngle = 45.0,
            intakeAngleValid = true,
            pieceDetected = false,
            climberPositionRotations = 0.5,
            climberPositionValid = true,
            timestampMs = 1_000L
        ))
        assertTrue(store.state.superstructure.marvin.cowl.angleValid)
        assertTrue(store.state.superstructure.marvin.intake.pivotAngleValid)
        assertTrue(store.state.superstructure.marvin.climber.positionValid)

        store.dispatch(SuperstructureSensorUpdate(
            flywheelRpm = 0.0,
            cowlAngleRotations = Double.NaN,
            cowlAngleValid = true,
            intakeAngle = 45.0,
            intakeAngleValid = false,
            pieceDetected = false,
            climberPositionRotations = 0.5,
            climberPositionValid = false,
            timestampMs = 1_020L
        ))

        val stale = store.state.superstructure.marvin
        assertFalse(stale.cowl.angleValid)
        assertFalse(stale.intake.pivotAngleValid)
        assertFalse(stale.climber.positionValid)
        assertEquals(0.0, stale.cowl.angleRotations)
        assertEquals(0.0, stale.intake.pivotAngleDegrees)
        assertEquals(0.0, stale.climber.positionRotations)
    }
}
