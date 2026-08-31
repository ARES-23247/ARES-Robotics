package org.aresfirst.marvin.marvin

import com.areslib.hardware.actuator.ClimberIO
import com.areslib.hardware.actuator.CowlIO
import com.areslib.hardware.actuator.FeederIO
import com.areslib.hardware.actuator.FloorIO
import com.areslib.hardware.actuator.FlywheelIO
import com.areslib.hardware.actuator.IntakeIO
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarvinSuperstructureSafetyTest {

    private class RecordingFlywheelIO : FlywheelIO {
        var velocityRpmCommand = Double.NaN
        var effortScale = Double.NaN
        override fun setVelocityRpm(rpm: Double, maxEffortScale: Double) {
            velocityRpmCommand = rpm
            effortScale = maxEffortScale
        }
        override fun setAppliedVoltage(volts: Double) = Unit
    }

    private class RecordingCowlIO : CowlIO {
        var angleCommand = Double.NaN
        var effortScale = Double.NaN
        var voltageCommand = Double.NaN
        override fun setTargetAngle(rotations: Double, maxEffortScale: Double) {
            angleCommand = rotations
            effortScale = maxEffortScale
        }
        override fun setAppliedVoltage(volts: Double) { voltageCommand = volts }
    }

    private class RecordingIntakeIO : IntakeIO {
        var pivotAngleCommand = Double.NaN
        var rollerVelocityCommand = Double.NaN
        var pivotEffortScale = Double.NaN
        var pivotVoltageCommand = Double.NaN
        override fun setPivotAngle(degrees: Double, maxEffortScale: Double) {
            pivotAngleCommand = degrees
            pivotEffortScale = maxEffortScale
        }
        override fun setPivotVoltage(volts: Double) { pivotVoltageCommand = volts }
        override fun setRollerVoltage(volts: Double) = Unit
        override fun setRollerVelocityRps(rps: Double) { rollerVelocityCommand = rps }
    }

    private class RecordingFeederIO : FeederIO {
        var voltageCommand = Double.NaN
        override fun setAppliedVoltage(volts: Double) { voltageCommand = volts }
    }

    private class RecordingFloorIO : FloorIO {
        var voltageCommand = Double.NaN
        override fun setAppliedVoltage(volts: Double) { voltageCommand = volts }
    }

    private class RecordingClimberIO : ClimberIO {
        var voltageCommand = Double.NaN
        var positionCommandRotations = Double.NaN
        var effortScale = Double.NaN
        override fun setTargetPositionRotations(rotations: Double, maxEffortScale: Double) {
            positionCommandRotations = rotations
            effortScale = maxEffortScale
        }
        override fun setAppliedVoltage(volts: Double) { voltageCommand = volts }
    }

    @Test
    fun brownoutPreservesStateTargetsWhileCollisionArbitrationStowsIntake() {
        val flywheel = RecordingFlywheelIO()
        val cowl = RecordingCowlIO()
        val intake = RecordingIntakeIO()
        val feeder = RecordingFeederIO()
        val floor = RecordingFloorIO()
        val climber = RecordingClimberIO()
        val subsystem = MarvinSuperstructure(flywheel, cowl, intake, feeder, floor, climber)
        val state = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(
                    flywheel = FlywheelState(targetVelocityRpm = 4_000.0),
                    flywheelActive = true,
                    cowl = CowlState(
                        angleRotations = 1.25,
                        angleValid = true,
                        targetAngleRotations = 1.25
                    ),
                    intake = IntakeState(
                        pivotAngleValid = true,
                        targetAngleDegrees = 90.0,
                        targetRollerVelocityRps = 10.0
                    ),
                    feeder = FeederState(targetVelocityRps = 8.0),
                    floor = FloorState(targetVelocityRps = 6.0),
                    climber = ClimberState(positionValid = true, targetVoltage = 10.0)
                )
            )
        )

        subsystem.writeOutputs(state, 0.4)

        assertEquals(1.25, cowl.angleCommand)
        assertEquals(0.4, cowl.effortScale)
        assertEquals(0.0, intake.pivotAngleCommand)
        assertEquals(0.4, intake.pivotEffortScale)
        assertEquals(4_000.0, flywheel.velocityRpmCommand)
        assertEquals(0.4, flywheel.effortScale)
        assertEquals(4.0, intake.rollerVelocityCommand)
        assertEquals(0.384, feeder.voltageCommand)
        assertEquals(0.288, floor.voltageCommand)
        assertEquals(4.0, climber.voltageCommand)

        assertEquals(1.25, state.superstructure.marvin.cowl.targetAngleRotations)
        assertEquals(90.0, state.superstructure.marvin.intake.targetAngleDegrees)
    }

    @Test
    fun climberPositionModeKeepsRotationsExplicitAndLimitsEffort() {
        val climber = RecordingClimberIO()
        val subsystem = MarvinSuperstructure(
            RecordingFlywheelIO(), RecordingCowlIO(), RecordingIntakeIO(),
            RecordingFeederIO(), RecordingFloorIO(), climber
        )
        val state = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(
                    climber = ClimberState(
                        positionValid = true,
                        controlMode = ClimberControlMode.POSITION_ROTATIONS,
                        targetPositionRotations = 7.5
                    ),
                    intake = IntakeState(pivotAngleValid = true)
                )
            )
        )

        subsystem.writeOutputs(state, 0.35)

        assertEquals(MarvinConfig.MechanismLimits.climberMaxRotations, climber.positionCommandRotations)
        assertEquals(0.35, climber.effortScale)
        assertTrue(climber.voltageCommand.isNaN())
    }

    @Test
    fun climberWaitsForMeasuredIntakeClearanceThenRunsWithoutMutatingRequestedIntake() {
        val intake = RecordingIntakeIO()
        val climber = RecordingClimberIO()
        val subsystem = MarvinSuperstructure(
            RecordingFlywheelIO(), RecordingCowlIO(), intake,
            RecordingFeederIO(), RecordingFloorIO(), climber
        )
        val extendedIntakeState = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(
                    intake = IntakeState(
                        targetAngleDegrees = 90.0,
                        pivotAngleDegrees = 90.0,
                        pivotAngleValid = true
                    ),
                    climber = ClimberState(positionValid = true, targetVoltage = 8.0)
                )
            )
        )

        subsystem.writeOutputs(extendedIntakeState, 1.0)

        assertEquals(0.0, intake.pivotAngleCommand)
        assertEquals(0.0, climber.voltageCommand)
        assertEquals(90.0, extendedIntakeState.superstructure.marvin.intake.targetAngleDegrees)

        val clearedState = extendedIntakeState.copy(
            superstructure = extendedIntakeState.superstructure.copy(
                custom = extendedIntakeState.superstructure.marvin.copy(
                    intake = extendedIntakeState.superstructure.marvin.intake.copy(pivotAngleDegrees = 0.0)
                )
            )
        )
        subsystem.writeOutputs(clearedState, 1.0)

        assertEquals(8.0, climber.voltageCommand)
    }

    @Test
    fun climberMotionBlockedWhenIntakeDeployedBeyondClearanceThreshold() {
        val climber = RecordingClimberIO()
        val subsystem = MarvinSuperstructure(
            RecordingFlywheelIO(), RecordingCowlIO(), RecordingIntakeIO(),
            RecordingFeederIO(), RecordingFloorIO(), climber
        )

        // Verify voltage command is zeroed when intake pivot is beyond clearance threshold (> 10.0 deg)
        val voltageModeState = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(
                    intake = IntakeState(
                        pivotAngleDegrees = 15.0,
                        pivotAngleValid = true
                    ),
                    climber = ClimberState(
                        positionRotations = 0.0,
                        positionValid = true,
                        targetVoltage = 8.0,
                        controlMode = ClimberControlMode.VOLTAGE
                    )
                )
            )
        )
        subsystem.writeOutputs(voltageModeState, 1.0)

        assertEquals(0.0, climber.voltageCommand)
        assertTrue(climber.positionCommandRotations.isNaN())

        // Verify position command is blocked/zeroed when intake pivot is beyond clearance threshold (> 10.0 deg)
        val climberPositionIO = RecordingClimberIO()
        val subsystemPosition = MarvinSuperstructure(
            RecordingFlywheelIO(), RecordingCowlIO(), RecordingIntakeIO(),
            RecordingFeederIO(), RecordingFloorIO(), climberPositionIO
        )
        val positionModeState = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(
                    intake = IntakeState(
                        pivotAngleDegrees = 15.0,
                        pivotAngleValid = true
                    ),
                    climber = ClimberState(
                        positionRotations = 0.0,
                        positionValid = true,
                        targetPositionRotations = 1.0,
                        controlMode = ClimberControlMode.POSITION_ROTATIONS
                    )
                )
            )
        )
        subsystemPosition.writeOutputs(positionModeState, 1.0)

        assertEquals(0.0, climberPositionIO.voltageCommand)
        assertTrue(climberPositionIO.positionCommandRotations.isNaN())
    }

    @Test
    fun stalePositionFeedbackZerosPositionOutputsUntilFresh() {
        val cowl = RecordingCowlIO()
        val intake = RecordingIntakeIO()
        val climber = RecordingClimberIO()
        val subsystem = MarvinSuperstructure(
            RecordingFlywheelIO(), cowl, intake,
            RecordingFeederIO(), RecordingFloorIO(), climber
        )
        val staleState = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(
                    cowl = CowlState(targetAngleRotations = 1.0, angleValid = false),
                    intake = IntakeState(targetAngleDegrees = 90.0, pivotAngleValid = false),
                    climber = ClimberState(
                        controlMode = ClimberControlMode.POSITION_ROTATIONS,
                        targetPositionRotations = 1.0,
                        positionRotations = 0.0,
                        positionValid = false
                    )
                )
            )
        )

        subsystem.writeOutputs(staleState, 1.0)

        assertTrue(cowl.angleCommand.isNaN())
        assertEquals(0.0, cowl.voltageCommand)
        assertTrue(intake.pivotAngleCommand.isNaN())
        assertEquals(0.0, intake.pivotVoltageCommand)
        assertEquals(0.0, climber.voltageCommand)
        assertTrue(climber.positionCommandRotations.isNaN())
    }

    @Test
    fun staleClimberPositionFeedbackAlsoBlocksOpenLoopVoltage() {
        val climber = RecordingClimberIO()
        val subsystem = MarvinSuperstructure(
            RecordingFlywheelIO(), RecordingCowlIO(), RecordingIntakeIO(),
            RecordingFeederIO(), RecordingFloorIO(), climber
        )
        val staleState = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(
                    intake = IntakeState(
                        pivotAngleDegrees = MarvinConfig.MechanismLimits.intakeStowedDegrees,
                        pivotAngleValid = true
                    ),
                    climber = ClimberState(
                        positionRotations = 0.5,
                        positionValid = false,
                        targetVoltage = 6.0,
                        controlMode = ClimberControlMode.VOLTAGE
                    )
                )
            )
        )

        subsystem.writeOutputs(staleState, 1.0)

        assertEquals(0.0, climber.voltageCommand)
    }

    @Test
    fun latchedSafetyInhibitWritesOnlyZeroEffortOutputs() {
        val flywheel = RecordingFlywheelIO()
        val cowl = RecordingCowlIO()
        val intake = RecordingIntakeIO()
        val feeder = RecordingFeederIO()
        val floor = RecordingFloorIO()
        val climber = RecordingClimberIO()
        val subsystem = MarvinSuperstructure(flywheel, cowl, intake, feeder, floor, climber)
        val state = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(mechanismSafetyInhibited = true)
            )
        )

        subsystem.writeOutputs(state, 1.0)

        assertEquals(0.0, cowl.voltageCommand)
        assertEquals(0.0, intake.pivotVoltageCommand)
        assertEquals(0.0, feeder.voltageCommand)
        assertEquals(0.0, floor.voltageCommand)
        assertEquals(0.0, climber.voltageCommand)
    }

    @Test
    fun flywheelCommandDispatchesTargetRpmWhenValid() {
        val flywheel = RecordingFlywheelIO()
        val cowl = RecordingCowlIO()
        val intake = RecordingIntakeIO()
        val feeder = RecordingFeederIO()
        val floor = RecordingFloorIO()
        val climber = RecordingClimberIO()
        val subsystem = MarvinSuperstructure(flywheel, cowl, intake, feeder, floor, climber)
        val state = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(
                    flywheelActive = true,
                    flywheel = FlywheelState(
                        targetVelocityRpm = 4500.0
                    )
                )
            )
        )

        subsystem.writeOutputs(state, 1.0)

        assertEquals(4500.0, flywheel.velocityRpmCommand, 1e-4)
    }

    @Test
    fun cowlCommandDispatchesTargetAngleWhenValid() {
        val flywheel = RecordingFlywheelIO()
        val cowl = RecordingCowlIO()
        val intake = RecordingIntakeIO()
        val feeder = RecordingFeederIO()
        val floor = RecordingFloorIO()
        val climber = RecordingClimberIO()
        val subsystem = MarvinSuperstructure(flywheel, cowl, intake, feeder, floor, climber)
        val state = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(
                    cowl = CowlState(
                        angleRotations = 1.0,
                        angleValid = true,
                        targetAngleRotations = 1.5
                    )
                )
            )
        )

        subsystem.writeOutputs(state, 1.0)

        assertEquals(1.5, cowl.angleCommand, 1e-4)
        assertEquals(1.0, cowl.effortScale, 1e-4)
    }

    @Test
    fun climberCommandDispatchesTargetVoltageWhenIntakeClear() {
        val flywheel = RecordingFlywheelIO()
        val cowl = RecordingCowlIO()
        val intake = RecordingIntakeIO()
        val feeder = RecordingFeederIO()
        val floor = RecordingFloorIO()
        val climber = RecordingClimberIO()
        val subsystem = MarvinSuperstructure(flywheel, cowl, intake, feeder, floor, climber)
        val state = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(
                    intake = IntakeState(
                        pivotAngleDegrees = 0.0,
                        pivotAngleValid = true
                    ),
                    climber = ClimberState(
                        positionRotations = 0.2,
                        positionValid = true,
                        targetVoltage = 8.0,
                        controlMode = ClimberControlMode.VOLTAGE
                    )
                )
            )
        )

        subsystem.writeOutputs(state, 1.0)

        assertEquals(8.0, climber.voltageCommand, 1e-4)
    }

    @Test
    fun climberCommandDispatchesTargetPositionWhenIntakeClear() {
        val flywheel = RecordingFlywheelIO()
        val cowl = RecordingCowlIO()
        val intake = RecordingIntakeIO()
        val feeder = RecordingFeederIO()
        val floor = RecordingFloorIO()
        val climber = RecordingClimberIO()
        val subsystem = MarvinSuperstructure(flywheel, cowl, intake, feeder, floor, climber)
        val state = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(
                    intake = IntakeState(
                        pivotAngleDegrees = 0.0,
                        pivotAngleValid = true
                    ),
                    climber = ClimberState(
                        positionRotations = 0.2,
                        positionValid = true,
                        targetPositionRotations = 1.5,
                        controlMode = ClimberControlMode.POSITION_ROTATIONS
                    )
                )
            )
        )

        subsystem.writeOutputs(state, 0.9)

        assertEquals(1.5, climber.positionCommandRotations, 1e-4)
        assertEquals(0.9, climber.effortScale, 1e-4)
    }

    @Test
    fun feederAndFloorVoltageCalculationsScaleLinearlyWithKvAndEffort() {
        val flywheel = RecordingFlywheelIO()
        val cowl = RecordingCowlIO()
        val intake = RecordingIntakeIO()
        val feeder = RecordingFeederIO()
        val floor = RecordingFloorIO()
        val climber = RecordingClimberIO()
        val subsystem = MarvinSuperstructure(flywheel, cowl, intake, feeder, floor, climber)
        val state = RobotState(
            superstructure = SuperstructureState(
                custom = MarvinState(
                    feeder = FeederState(targetVelocityRps = 50.0),
                    floor = FloorState(targetVelocityRps = 25.0)
                )
            )
        )

        // With effortScale = 0.5:
        // feeder voltage = 0.12 * 50.0 * 0.5 = 3.0V
        // floor voltage = 0.12 * 25.0 * 0.5 = 1.5V
        subsystem.writeOutputs(state, 0.5)

        assertEquals(3.0, feeder.voltageCommand, 1e-4)
        assertEquals(1.5, floor.voltageCommand, 1e-4)
    }
}
