package com.areslib.frc.marvin

import com.areslib.Store
import com.areslib.control.assist.ShotResult
import com.areslib.hardware.actuator.ClimberIO
import com.areslib.hardware.actuator.CowlIO
import com.areslib.hardware.actuator.FeederIO
import com.areslib.hardware.actuator.FloorIO
import com.areslib.hardware.actuator.FlywheelIO
import com.areslib.hardware.actuator.IntakeIO
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.state.DriveState
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MarvinShooterSubsystemTest {

    private class RecordingFlywheelIO : FlywheelIO {
        var velocityRpmCommand = Double.NaN
        var appliedVoltageCommand = Double.NaN
        var measuredVelocityRpm: Double = 0.0
        var measuredVelocityValid: Boolean = true
        var measuredCurrentAmps: Double = 0.0
        var measuredTempCelsius: Double = 25.0

        override val velocityRpm: Double get() = measuredVelocityRpm
        override val velocityValid: Boolean get() = measuredVelocityValid
        override val currentAmps: Double get() = measuredCurrentAmps
        override val tempCelsius: Double get() = measuredTempCelsius

        override fun setVelocityRpm(rpm: Double) {
            velocityRpmCommand = rpm
        }

        override fun setAppliedVoltage(volts: Double) {
            appliedVoltageCommand = volts
        }
    }

    private class RecordingCowlIO : CowlIO {
        var angleCommand = Double.NaN
        var effortScale = Double.NaN
        var voltageCommand = Double.NaN
        override var angleRotations: Double = 0.0
        override var angleValid: Boolean = true
        override var currentAmps: Double = 0.0

        override fun setTargetAngle(rotations: Double) {
            angleCommand = rotations
        }

        override fun setTargetAngle(rotations: Double, maxEffortScale: Double) {
            angleCommand = rotations
            effortScale = maxEffortScale
        }

        override fun setAppliedVoltage(volts: Double) {
            voltageCommand = volts
        }
    }

    private class RecordingIntakeIO : IntakeIO {
        var pivotAngleCommand = Double.NaN
        var rollerVelocityCommand = Double.NaN
        override var pivotAngleDegrees: Double = 0.0
        override var pivotAngleValid: Boolean = true
        override var currentAmps: Double = 0.0

        override fun setPivotAngle(degrees: Double) {
            pivotAngleCommand = degrees
        }

        override fun setPivotAngle(degrees: Double, maxEffortScale: Double) {
            pivotAngleCommand = degrees
        }

        override fun setPivotVoltage(volts: Double) = Unit
        override fun setRollerVoltage(volts: Double) = Unit
        override fun setRollerVelocityRps(rps: Double) {
            rollerVelocityCommand = rps
        }
    }

    private class RecordingFeederIO : FeederIO {
        var voltageCommand = Double.NaN
        override var isBeamBroken: Boolean = false
        override var pieceDetectionValid: Boolean = false
        override var currentAmps: Double = 0.0

        override fun setAppliedVoltage(volts: Double) {
            voltageCommand = volts
        }
    }

    private class RecordingFloorIO : FloorIO {
        var voltageCommand = Double.NaN
        override var velocityRps: Double = 0.0
        override var currentAmps: Double = 0.0

        override fun setAppliedVoltage(volts: Double) {
            voltageCommand = volts
        }
    }

    private class RecordingClimberIO : ClimberIO {
        var voltageCommand = Double.NaN
        var positionCommandRotations = Double.NaN
        override var positionRotations: Double = 0.0
        override var positionValid: Boolean = true
        override var currentAmps: Double = 0.0

        override fun setTargetPositionRotations(rotations: Double) {
            positionCommandRotations = rotations
        }

        override fun setTargetPositionRotations(rotations: Double, maxEffortScale: Double) {
            positionCommandRotations = rotations
        }

        override fun setAppliedVoltage(volts: Double) {
            voltageCommand = volts
        }
    }

    @BeforeEach
    fun useMockClock() = RobotClock.useMockTime(1_000L)

    @AfterEach
    fun restoreClock() = RobotClock.useSystemTime()

    @Test
    fun `setting target velocity dispatches to IO and respects flywheel speed tolerances`() {
        val flywheelIO = RecordingFlywheelIO()
        val cowlIO = RecordingCowlIO()
        val intakeIO = RecordingIntakeIO()
        val feederIO = RecordingFeederIO()
        val floorIO = RecordingFloorIO()
        val climberIO = RecordingClimberIO()

        val store = Store(
            RobotState(
                drive = DriveState(measuredMotionValid = true),
                superstructure = SuperstructureState(custom = MarvinState())
            )
        ) { state, action -> MarvinReducer.reduce(state, action) }

        val superstructure = MarvinSuperstructure(
            flywheelIO, cowlIO, intakeIO, feederIO, floorIO, climberIO
        )
        val shooter = MarvinShooterSubsystem(store)

        val target = Translation2d(0.0, 5.547868)
        val pose = Pose2d(0.0, 0.0, Rotation2d(-Math.PI / 2.0)) // Pointed rearward toward target at (0, 5.547868)

        // 1. Initial execution computes shot and dispatches target RPM to store and IO
        shooter.updateStaticShoot(pose, target)

        val targetRpm = store.state.superstructure.marvin.flywheel.targetVelocityRpm
        assertTrue(targetRpm > 100.0, "Target RPM must be positive and nontrivial (was $targetRpm)")
        assertTrue(store.state.superstructure.marvin.flywheelActive, "Flywheel must be activated")

        superstructure.writeOutputs(store.state, scale = 1.0)
        assertEquals(targetRpm, flywheelIO.velocityRpmCommand, 1e-4, "Target velocity must dispatch to IO")

        // Verify brownout effort scaling on target velocity dispatch
        superstructure.writeOutputs(store.state, scale = 0.5)
        assertEquals(targetRpm * 0.5, flywheelIO.velocityRpmCommand, 1e-4, "Effort scale must scale IO target velocity")

        // 2. Flywheel outside tolerance (error >= 150 RPM) -> feeding is blocked
        flywheelIO.measuredVelocityRpm = targetRpm - 200.0
        flywheelIO.measuredVelocityValid = true
        cowlIO.angleRotations = store.state.superstructure.marvin.cowl.targetAngleRotations
        cowlIO.angleValid = true

        superstructure.readSensors(store, 1_000L)
        assertFalse(store.state.superstructure.marvin.flywheel.allMotorsAtTarget, "Flywheel not at target when delta is 200 RPM")
        assertFalse(store.state.superstructure.marvin.isFlywheelAtSpeed, "isFlywheelAtSpeed must be false outside tolerance")

        shooter.updateStaticShoot(pose, target)
        assertFalse(store.state.superstructure.marvin.transferActive, "Transfer must remain blocked when flywheel is outside tolerance")
        assertEquals(0.0, store.state.superstructure.marvin.feeder.targetVelocityRps, "Feeder speed must remain 0.0")

        // 3. Flywheel within tolerance (error < 150 RPM) -> feeding is authorized
        flywheelIO.measuredVelocityRpm = targetRpm - 50.0
        superstructure.readSensors(store, 1_020L)
        assertTrue(store.state.superstructure.marvin.flywheel.allMotorsAtTarget, "Flywheel at target when delta is 50 RPM")
        assertTrue(store.state.superstructure.marvin.isFlywheelAtSpeed, "isFlywheelAtSpeed must be true within tolerance")

        shooter.updateStaticShoot(pose, target)
        assertTrue(store.state.superstructure.marvin.transferActive, "Transfer must be active once speed tolerance is satisfied")
        assertEquals(MarvinConfig.FEEDER_SHOOT_SPEED_RPS, store.state.superstructure.marvin.feeder.targetVelocityRps)

        superstructure.writeOutputs(store.state, scale = 1.0)
        assertEquals(0.12 * MarvinConfig.FEEDER_SHOOT_SPEED_RPS, feederIO.voltageCommand, 1e-4, "Feeder IO must receive voltage command")
    }

    @Test
    fun `flywheel speed tolerance respects boundary conditions and invalid sensor state`() {
        val flywheelIO = RecordingFlywheelIO()
        val cowlIO = RecordingCowlIO()
        val intakeIO = RecordingIntakeIO()
        val feederIO = RecordingFeederIO()
        val floorIO = RecordingFloorIO()
        val climberIO = RecordingClimberIO()

        val store = Store(
            RobotState(
                drive = DriveState(measuredMotionValid = true),
                superstructure = SuperstructureState(custom = MarvinState())
            )
        ) { state, action -> MarvinReducer.reduce(state, action) }

        val superstructure = MarvinSuperstructure(
            flywheelIO, cowlIO, intakeIO, feederIO, floorIO, climberIO
        )
        val shooter = MarvinShooterSubsystem(store)

        val target = Translation2d(0.0, 5.547868)
        val pose = Pose2d(0.0, 0.0, Rotation2d(-Math.PI / 2.0))

        shooter.updateStaticShoot(pose, target)
        val targetRpm = store.state.superstructure.marvin.flywheel.targetVelocityRpm

        // Boundary test: exactly 150.0 RPM error is NOT within tolerance (< 150.0 required)
        flywheelIO.measuredVelocityRpm = targetRpm - 150.0
        flywheelIO.measuredVelocityValid = true
        superstructure.readSensors(store, 1_000L)
        assertFalse(store.state.superstructure.marvin.flywheel.allMotorsAtTarget, "Exact 150 RPM error must fail closed")
        assertFalse(store.state.superstructure.marvin.isFlywheelAtSpeed)

        // Just inside boundary: 149.9 RPM error is within tolerance
        flywheelIO.measuredVelocityRpm = targetRpm - 149.9
        flywheelIO.measuredVelocityValid = true
        superstructure.readSensors(store, 1_020L)
        assertTrue(store.state.superstructure.marvin.flywheel.allMotorsAtTarget, "149.9 RPM error is within 150 RPM tolerance")
        assertTrue(store.state.superstructure.marvin.isFlywheelAtSpeed)

        // Invalid sensor reading fails closed even if numeric RPM matches target perfectly
        flywheelIO.measuredVelocityRpm = targetRpm
        flywheelIO.measuredVelocityValid = false
        superstructure.readSensors(store, 1_040L)
        assertFalse(store.state.superstructure.marvin.flywheel.velocityValid)
        assertEquals(0.0, store.state.superstructure.marvin.flywheel.velocityRpm, "Invalid velocity is zeroed by reducer")
        assertFalse(store.state.superstructure.marvin.isFlywheelAtSpeed)
    }

    @Test
    fun `shoot on the move dispatches target velocity to IO and stops when motion is invalid`() {
        val flywheelIO = RecordingFlywheelIO()
        val cowlIO = RecordingCowlIO()
        val intakeIO = RecordingIntakeIO()
        val feederIO = RecordingFeederIO()
        val floorIO = RecordingFloorIO()
        val climberIO = RecordingClimberIO()

        val store = Store(
            RobotState(
                drive = DriveState(
                    xVelocityMetersPerSecond = 0.0,
                    yVelocityMetersPerSecond = 0.0,
                    angularVelocityRadiansPerSecond = 0.0,
                    measuredMotionValid = true
                ),
                superstructure = SuperstructureState(custom = MarvinState())
            )
        ) { state, action -> MarvinReducer.reduce(state, action) }

        val superstructure = MarvinSuperstructure(
            flywheelIO, cowlIO, intakeIO, feederIO, floorIO, climberIO
        )
        val shooter = MarvinShooterSubsystem(store)
        val shotResult = ShotResult()

        val target = Translation2d(0.0, 5.547868)
        val pose = Pose2d(0.0, 0.0, Rotation2d(-Math.PI / 2.0))

        shooter.updateShootOnTheMove(pose, target, shotResult)
        val targetRpm = shotResult.targetFlywheelRpm
        assertTrue(targetRpm > 100.0)
        assertEquals(targetRpm, store.state.superstructure.marvin.flywheel.targetVelocityRpm)

        superstructure.writeOutputs(store.state, scale = 1.0)
        assertEquals(targetRpm, flywheelIO.velocityRpmCommand, 1e-4)

        // When measured motion becomes invalid, SOTM stops flywheel and cancels transfer
        store.dispatch(
            com.areslib.action.RobotAction.PoseUpdate(
                xMeters = 0.0,
                yMeters = 0.0,
                headingRadians = 0.0,
                timestampMs = 1_020L,
                motionMeasurementsValid = false,
                imuMeasurementsValid = true,
                isExternalEstimate = true
            )
        )

        shooter.updateShootOnTheMove(pose, target, shotResult)
        assertFalse(store.state.superstructure.marvin.flywheelActive)
        assertEquals(0.0, store.state.superstructure.marvin.flywheel.targetVelocityRpm)

        superstructure.writeOutputs(store.state, scale = 1.0)
        assertEquals(0.0, flywheelIO.velocityRpmCommand, 1e-4, "IO velocity command must be 0.0 when stopped")
    }

    @Test
    fun `cancelTransfer clears feeder targets while maintaining flywheel target velocity`() {
        val flywheelIO = RecordingFlywheelIO()
        val cowlIO = RecordingCowlIO()
        val intakeIO = RecordingIntakeIO()
        val feederIO = RecordingFeederIO()
        val floorIO = RecordingFloorIO()
        val climberIO = RecordingClimberIO()

        val store = Store(
            RobotState(
                drive = DriveState(measuredMotionValid = true),
                superstructure = SuperstructureState(custom = MarvinState())
            )
        ) { state, action -> MarvinReducer.reduce(state, action) }

        val superstructure = MarvinSuperstructure(
            flywheelIO, cowlIO, intakeIO, feederIO, floorIO, climberIO
        )
        val shooter = MarvinShooterSubsystem(store)

        val target = Translation2d(0.0, 5.547868)
        val pose = Pose2d(0.0, 0.0, Rotation2d(-Math.PI / 2.0))

        shooter.updateStaticShoot(pose, target)
        val targetRpm = store.state.superstructure.marvin.flywheel.targetVelocityRpm

        // Satisfy tolerances to start transfer
        flywheelIO.measuredVelocityRpm = targetRpm
        flywheelIO.measuredVelocityValid = true
        cowlIO.angleRotations = store.state.superstructure.marvin.cowl.targetAngleRotations
        cowlIO.angleValid = true
        superstructure.readSensors(store, 1_000L)

        shooter.updateStaticShoot(pose, target)
        assertTrue(store.state.superstructure.marvin.transferActive)
        assertEquals(MarvinConfig.FEEDER_SHOOT_SPEED_RPS, store.state.superstructure.marvin.feeder.targetVelocityRps)

        // Cancel transfer
        shooter.cancelTransfer()
        assertFalse(store.state.superstructure.marvin.transferActive)
        assertEquals(0.0, store.state.superstructure.marvin.feeder.targetVelocityRps)
        assertEquals(targetRpm, store.state.superstructure.marvin.flywheel.targetVelocityRpm, "Flywheel target RPM preserved")
        assertTrue(store.state.superstructure.marvin.flywheelActive, "Flywheel remains active")

        superstructure.writeOutputs(store.state, scale = 1.0)
        assertEquals(targetRpm, flywheelIO.velocityRpmCommand, 1e-4)
        assertEquals(0.0, feederIO.voltageCommand, 1e-4)
    }
}
