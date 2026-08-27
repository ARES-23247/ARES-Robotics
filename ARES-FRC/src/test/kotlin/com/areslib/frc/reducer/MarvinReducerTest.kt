package com.areslib.frc.reducer

import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import com.areslib.frc.marvin.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MarvinReducerTest {

    @Test
    fun `SetClimberPositionRotations stores explicit mechanism rotations independent of intake`() {
        val initialState = RobotState(
            superstructure = SuperstructureState(custom = MarvinState())
        )
        
        val statePivotStowed = MarvinReducer.reduce(
            initialState,
            SetClimberPositionRotations(0.25, 1000L)
        )
        assertEquals(0.25, statePivotStowed.superstructure.marvin.climber.targetPositionRotations, "Reducer stores rotations without an uncalibrated distance conversion")
        assertEquals(ClimberControlMode.POSITION_ROTATIONS, statePivotStowed.superstructure.marvin.climber.controlMode)

        val statePivotDeployed = RobotState(
            superstructure = SuperstructureState().copy(
                custom = MarvinState(
                    intake = IntakeState(pivotAngleDegrees = 90.0, targetAngleDegrees = 90.0)
                )
            )
        )
        val statePivotDeployedUpdated = MarvinReducer.reduce(
            statePivotDeployed,
            SetClimberPositionRotations(0.25, 1000L)
        )
        assertEquals(0.25, statePivotDeployedUpdated.superstructure.marvin.climber.targetPositionRotations, "Climber rotations should set correctly when pivot is deployed")
    }

    @Test
    fun `reducer does not implicitly rewrite cross-mechanism commands`() {
        val statePivotStowed = RobotState(
            superstructure = SuperstructureState().copy(
                custom = MarvinState(
                    intake = IntakeState(pivotAngleDegrees = 0.0, targetAngleDegrees = 0.0)
                )
            )
        )

        val stateExtendedSensor = MarvinReducer.reduce(
            statePivotStowed,
            SuperstructureSensorUpdate(
                flywheelRpm = 0.0,
                cowlAngleRotations = 0.0,
                intakeAngle = 0.0,
                pieceDetected = false,
                climberPositionRotations = 0.03,
                timestampMs = 1000L
            )
        )
        // Sensor observations update measurements, not requested mechanism geometry.
        assertEquals(0.0, stateExtendedSensor.superstructure.marvin.intake.targetAngleDegrees)
        assertFalse(stateExtendedSensor.superstructure.marvin.intake.isDeployed)

        val statePivotDeployed = RobotState(
            superstructure = SuperstructureState().copy(
                custom = MarvinState(
                    intake = IntakeState(pivotAngleDegrees = 90.0, targetAngleDegrees = 90.0)
                )
            )
        )
        val stateClimberTargetExtended = MarvinReducer.reduce(
            statePivotDeployed,
            SetClimberPositionRotations(0.1, 1000L)
        )
        val statePivotStowAction = MarvinReducer.reduce(
            stateClimberTargetExtended,
            SetIntakePivot(deployed = false, 1100L)
        )
        assertEquals(0.0, statePivotStowAction.superstructure.marvin.intake.targetAngleDegrees)
        assertFalse(statePivotStowAction.superstructure.marvin.intake.isDeployed)
    }

    @Test
    fun `test all basic marvin setter actions`() {
        val initialState = RobotState(
            superstructure = SuperstructureState(custom = MarvinState())
        )

        // SetFlywheelSpeed
        val stateFlywheel = MarvinReducer.reduce(initialState, SetFlywheelSpeed(3500.0, 1000L))
        assertEquals(3500.0, stateFlywheel.superstructure.marvin.flywheel.targetVelocityRpm)

        // SetCowlAngle stores mechanism rotations; the output controller applies its travel clamp.
        val stateCowl = MarvinReducer.reduce(initialState, SetCowlAngle(15.0, 1000L))
        assertEquals(15.0, stateCowl.superstructure.marvin.cowl.targetAngleRotations)

        // SetIntakePivot
        val stateIntakePivot = MarvinReducer.reduce(initialState, SetIntakePivot(true, 1000L))
        assertTrue(stateIntakePivot.superstructure.marvin.intake.isDeployed)
        assertEquals(90.0, stateIntakePivot.superstructure.marvin.intake.targetAngleDegrees)

        // SetIntakeRollers
        val stateIntakeRollers = MarvinReducer.reduce(initialState, SetIntakeRollers(12.5, 1000L))
        assertEquals(12.5, stateIntakeRollers.superstructure.marvin.intake.targetRollerVelocityRps)

        // SetFeederSpeed
        val stateFeeder = MarvinReducer.reduce(initialState, SetFeederSpeed(8.0, 1000L))
        assertEquals(8.0, stateFeeder.superstructure.marvin.feeder.targetVelocityRps)

        // SetFloorSpeed
        val stateFloor = MarvinReducer.reduce(initialState, SetFloorSpeed(9.5, 1000L))
        assertEquals(9.5, stateFloor.superstructure.marvin.floor.targetVelocityRps)

        // SetClimberVoltage
        val stateClimberVoltage = MarvinReducer.reduce(initialState, SetClimberVoltage(11.0, 1000L))
        assertEquals(11.0, stateClimberVoltage.superstructure.marvin.climber.targetVoltage)
        assertEquals(ClimberControlMode.VOLTAGE, stateClimberVoltage.superstructure.marvin.climber.controlMode)
    }

    @Test
    fun `test slamtake state machine transitions`() {
        val initialState = RobotState(
            superstructure = SuperstructureState(custom = MarvinState())
        )

        // Start slamtake.
        val stateSlamtakeStart = MarvinReducer.reduce(initialState, StartSlamtake(1000L))
        assertTrue(stateSlamtakeStart.superstructure.marvin.slamtakeActive)
        assertEquals(1000L, stateSlamtakeStart.superstructure.marvin.slamtakeStartTimeMs)

        // A valid sensor update before the first phase transition keeps intake running.
        val stateElapsed0_2 = MarvinReducer.reduce(
            stateSlamtakeStart,
            SuperstructureSensorUpdate(
                flywheelRpm = 0.0,
                cowlAngleRotations = 0.0,
                intakeAngle = 90.0,
                pieceDetected = false,
                climberPositionRotations = 0.0,
                timestampMs = 1200L
            )
        )
        assertTrue(stateElapsed0_2.superstructure.marvin.slamtakeActive)
        assertTrue(stateElapsed0_2.superstructure.marvin.intake.isDeployed)
        assertEquals(90.0, stateElapsed0_2.superstructure.marvin.intake.targetAngleDegrees)
        assertEquals(10.0, stateElapsed0_2.superstructure.marvin.intake.targetRollerVelocityRps)
        assertEquals(10.0, stateElapsed0_2.superstructure.marvin.floor.targetVelocityRps)
        assertEquals(0.0, stateElapsed0_2.superstructure.marvin.feeder.targetVelocityRps)

        // Phase 1 retracts the pivot while continuing rollers/floor.
        val stateElapsed1_0 = MarvinReducer.reduce(
            stateSlamtakeStart,
            SlamtakeTimerExpired(1, 2000L)
        )
        assertTrue(stateElapsed1_0.superstructure.marvin.slamtakeActive)
        assertFalse(stateElapsed1_0.superstructure.marvin.intake.isDeployed)
        assertEquals(0.0, stateElapsed1_0.superstructure.marvin.intake.targetAngleDegrees)
        assertEquals(10.0, stateElapsed1_0.superstructure.marvin.intake.targetRollerVelocityRps)
        assertEquals(10.0, stateElapsed1_0.superstructure.marvin.floor.targetVelocityRps)

        // Phase 2 completes the bounded sequence and stops rollers/floor.
        val stateElapsed2_0 = MarvinReducer.reduce(
            stateSlamtakeStart,
            SlamtakeTimerExpired(2, 3000L)
        )
        assertFalse(stateElapsed2_0.superstructure.marvin.slamtakeActive, "Slamtake should be disabled after timeout")
        assertEquals(0.0, stateElapsed2_0.superstructure.marvin.intake.targetRollerVelocityRps)
        assertEquals(0.0, stateElapsed2_0.superstructure.marvin.floor.targetVelocityRps)

        // Explicit cancellation also clears the active flag.
        val stateSlamtakeStop = MarvinReducer.reduce(stateSlamtakeStart, StopSlamtake(1500L))
        assertFalse(stateSlamtakeStop.superstructure.marvin.slamtakeActive)
        assertEquals(0.0, stateSlamtakeStop.superstructure.marvin.intake.targetRollerVelocityRps)
        assertEquals(0.0, stateSlamtakeStop.superstructure.marvin.floor.targetVelocityRps)
        assertEquals(0.0, stateSlamtakeStop.superstructure.marvin.feeder.targetVelocityRps)
        assertFalse(stateSlamtakeStop.superstructure.marvin.transferActive)
    }

    @Test
    fun `invalid feeder detector cannot claim inventory or end slamtake`() {
        val initial = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(inventoryCount = 1, slamtakeActive = true)
            )
        )

        val invalidReading = MarvinReducer.reduce(
            initial,
            SuperstructureSensorUpdate(
                flywheelRpm = 0.0,
                cowlAngleRotations = 0.0,
                intakeAngle = 90.0,
                pieceDetected = true,
                pieceDetectionValid = false,
                timestampMs = 1000L
            )
        )

        assertFalse(invalidReading.superstructure.marvin.feeder.pieceDetectionValid)
        assertFalse(invalidReading.superstructure.marvin.feeder.gamePieceDetected)
        assertEquals(1, invalidReading.superstructure.marvin.inventoryCount)
        assertTrue(invalidReading.superstructure.marvin.slamtakeActive)
    }

    @Test
    fun `detector validity recovery does not count the same held piece twice`() {
        val detected = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(
                    inventoryCount = 1,
                    feeder = FeederState(
                        gamePieceDetected = true,
                        previousGamePieceDetected = false,
                        pieceDetectionValid = true
                    )
                )
            )
        )
        val invalid = MarvinReducer.reduce(
            detected,
            SuperstructureSensorUpdate(0.0, 0.0, 0.0, true, pieceDetectionValid = false)
        )
        val recovered = MarvinReducer.reduce(
            invalid,
            SuperstructureSensorUpdate(0.0, 0.0, 0.0, true, pieceDetectionValid = true)
        )

        assertEquals(1, recovered.superstructure.marvin.inventoryCount)
        assertTrue(recovered.superstructure.marvin.feeder.gamePieceDetected)
        assertTrue(recovered.superstructure.marvin.feeder.pieceDetectionValid)
    }

    @Test
    fun `invalid flywheel velocity cannot report ready`() {
        val initial = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(flywheel = FlywheelState(targetVelocityRpm = 4000.0))
            )
        )

        val invalidReading = MarvinReducer.reduce(
            initial,
            SuperstructureSensorUpdate(
                flywheelRpm = 4000.0,
                cowlAngleRotations = 0.0,
                intakeAngle = 0.0,
                pieceDetected = false,
                flywheelVelocityValid = false,
                timestampMs = 1000L
            )
        )

        assertFalse(invalidReading.superstructure.marvin.flywheel.velocityValid)
        assertFalse(invalidReading.superstructure.marvin.isFlywheelAtSpeed)
    }

    @Test
    fun `fresh average without per-motor readiness proof cannot report ready`() {
        val initial = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(flywheel = FlywheelState(targetVelocityRpm = 4000.0))
            )
        )

        val unprovenReading = MarvinReducer.reduce(
            initial,
            SuperstructureSensorUpdate(
                flywheelRpm = 4000.0,
                cowlAngleRotations = 0.0,
                intakeAngle = 0.0,
                pieceDetected = false,
                flywheelVelocityValid = true,
                timestampMs = 1000L
            )
        )

        assertTrue(unprovenReading.superstructure.marvin.flywheel.velocityValid)
        assertFalse(unprovenReading.superstructure.marvin.flywheel.allMotorsAtTarget)
        assertFalse(unprovenReading.superstructure.marvin.isFlywheelAtSpeed)
    }
}
