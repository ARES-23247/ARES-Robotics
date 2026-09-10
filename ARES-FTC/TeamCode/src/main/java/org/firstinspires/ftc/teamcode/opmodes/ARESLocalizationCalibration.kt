package org.firstinspires.ftc.teamcode.opmodes

import com.areslib.math.estimation.LocalizationCalibrationCheckpoint
import com.areslib.math.estimation.LocalizationCalibrationPlatform
import com.areslib.math.estimation.LocalizationCalibrationRecorder
import com.areslib.math.estimation.LocalizationCalibrationSample
import com.areslib.math.estimation.LocalizationCalibrationTestType
import com.areslib.math.estimation.StationaryCalibrationGate
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import org.firstinspires.ftc.teamcode.dsl.AresTeleOpBase

/**
 * Driver-operated localization calibration collector.
 *
 * Controls:
 * - B: cycle test type
 * - D-pad: adjust surveyed X/Y by 5 cm
 * - Bumpers: adjust surveyed heading by 5 degrees
 * - Back: zero surveyed pose
 * - Start: seed robot localization to surveyed pose
 * - A: toggle stationary/combined frame recording
 * - X/Y: record surveyed route START/END checkpoints
 */
@TeleOp(name = "ARES Localization Calibration", group = "Tuning")
class ARESLocalizationCalibration : AresTeleOpBase() {
    private var testType = LocalizationCalibrationTestType.VISION_STATIONARY
    private var truthX = 0.0
    private var truthY = 0.0
    private var truthHeading = 0.0
    private var runId = 1
    private var continuousRecording = false
    private var recorder: LocalizationCalibrationRecorder? = null
    private var pendingCheckpoint = LocalizationCalibrationCheckpoint.NONE
    private var pendingRunId = 0
    private var pendingSeed = false
    private var lastRecordedVisionTimestampMs = Long.MIN_VALUE
    private val stationaryGate = StationaryCalibrationGate()
    private var lastTelemetryMs = 0L

    override fun define() = teleOp {
        setup {
            recorder = LocalizationCalibrationRecorder(LocalizationCalibrationPlatform.FTC).also {
                robot.base.hardwareRegistry.registerCloseable(it)
            }
            robot.base.isLiveTuningEnabled = false
            robot.addTelemetry("Calibration", "Use surveyed poses; do not use Limelight as truth")
        }

        controls {
            driver.b.onPress("Cycle localization calibration test") {
                val values = LocalizationCalibrationTestType.entries
                testType = values[(testType.ordinal + 1) % values.size]
                continuousRecording = false
                cancelPendingAndRestartDwell()
            }
            driver.dpadRight.onPress("Increase surveyed X by 5 cm") { cancelPendingAndRestartDwell(); truthX += 0.05 }
            driver.dpadLeft.onPress("Decrease surveyed X by 5 cm") { cancelPendingAndRestartDwell(); truthX -= 0.05 }
            driver.dpadUp.onPress("Increase surveyed Y by 5 cm") { cancelPendingAndRestartDwell(); truthY += 0.05 }
            driver.dpadDown.onPress("Decrease surveyed Y by 5 cm") { cancelPendingAndRestartDwell(); truthY -= 0.05 }
            driver.rightBumper.onPress("Increase surveyed heading by 5 degrees") {
                cancelPendingAndRestartDwell()
                truthHeading += Math.toRadians(5.0)
            }
            driver.leftBumper.onPress("Decrease surveyed heading by 5 degrees") {
                cancelPendingAndRestartDwell()
                truthHeading -= Math.toRadians(5.0)
            }
            driver.back.onPress("Zero surveyed pose") {
                cancelPendingAndRestartDwell()
                truthX = 0.0
                truthY = 0.0
                truthHeading = 0.0
            }
            driver.start.onPress("Seed localization to surveyed pose") {
                cancelPendingAndRestartDwell()
                pendingSeed = true
            }
            driver.a.onPress("Toggle stationary calibration recording") {
                continuousRecording = !continuousRecording
                stationaryGate.reset()
            }
            driver.x.onPress("Record surveyed route start") {
                cancelPendingAndRestartDwell()
                pendingSeed = true
                pendingRunId = runId
                pendingCheckpoint = LocalizationCalibrationCheckpoint.START
            }
            driver.y.onPress("Record surveyed route end") {
                cancelPendingAndRestartDwell()
                pendingRunId = runId
                pendingCheckpoint = LocalizationCalibrationCheckpoint.END
            }
        }

        everyLoop {
            robot.driveWithGamepad(driver, useHeadingLock = true)
            val nowMs = RobotClock.currentTimeMillis()
            val driveState = robot.base.store.state.drive
            val driverNeutral = kotlin.math.abs(driver.leftStickX.value) <= DRIVER_NEUTRAL_DEADZONE &&
                kotlin.math.abs(driver.leftStickY.value) <= DRIVER_NEUTRAL_DEADZONE &&
                kotlin.math.abs(driver.rightStickX.value) <= DRIVER_NEUTRAL_DEADZONE
            val measuredTranslationMps = kotlin.math.hypot(
                driveState.measuredFieldXVelocityMetersPerSecond,
                driveState.measuredFieldYVelocityMetersPerSecond
            )
            val stationaryReady = stationaryGate.update(
                nowMs = nowMs,
                driverNeutral = driverNeutral && kotlin.math.hypot(driveState.xVelocityMetersPerSecond,
                    driveState.yVelocityMetersPerSecond) <= 0.03 &&
                    kotlin.math.abs(driveState.angularVelocityRadiansPerSecond) <= 0.05,
                translationMetersPerSecond = measuredTranslationMps,
                angularRadiansPerSecond = driveState.measuredAngularVelocityRadiansPerSecond,
                motionMeasurementsValid = driveState.measuredMotionValid,
                observationTimestampMs = driveState.poseEstimator.lastObservationTimestampMs,
            )
            val checkpoint = pendingCheckpoint
            val applyingAction = stationaryReady && (pendingSeed || checkpoint != LocalizationCalibrationCheckpoint.NONE)
            if (pendingSeed && stationaryReady) {
                robot.base.resetPose(Pose2d(truthX, truthY, Rotation2d(com.areslib.math.wrapAngle(truthHeading))))
                pendingSeed = false
            }
            if (checkpoint != LocalizationCalibrationCheckpoint.NONE && stationaryReady) {
                record(robot, checkpoint, pendingRunId, truthValid = true)
                if (checkpoint == LocalizationCalibrationCheckpoint.END) runId++
                pendingCheckpoint = LocalizationCalibrationCheckpoint.NONE
            }
            if (applyingAction) stationaryGate.reset()

            if (continuousRecording && stationaryReady && !applyingAction &&
                (testType == LocalizationCalibrationTestType.VISION_STATIONARY ||
                    testType == LocalizationCalibrationTestType.COMBINED_VALIDATION)) {
                var newestVisionTimestamp = Long.MIN_VALUE
                val measurements = robot.base.visionTracker.visionInputs.measurements
                for (index in measurements.indices) {
                    val measurement = measurements[index]
                    if (measurement.timestampMs > newestVisionTimestamp) {
                        newestVisionTimestamp = measurement.timestampMs
                    }
                }
                val age = nowMs - newestVisionTimestamp
                if (newestVisionTimestamp > lastRecordedVisionTimestampMs && newestVisionTimestamp <= nowMs &&
                    age >= 0L && age <= 250L) {
                    record(robot, LocalizationCalibrationCheckpoint.NONE, runId, truthValid = true)
                    lastRecordedVisionTimestampMs = newestVisionTimestamp
                }
            }

            if (nowMs - lastTelemetryMs >= TELEMETRY_PERIOD_MS) {
                lastTelemetryMs = nowMs
                robot.addTelemetry("Cal/Test", testType.name)
                robot.addTelemetry("Cal/Run", runId)
                robot.addTelemetry("Cal/Recording", continuousRecording && stationaryReady)
                robot.addTelemetry("Cal/Stationary", stationaryReady)
                robot.addTelemetry("Cal/Action Pending", pendingSeed || pendingCheckpoint != LocalizationCalibrationCheckpoint.NONE)
                robot.addTelemetry("Cal/Truth X", truthX)
                robot.addTelemetry("Cal/Truth Y", truthY)
                robot.addTelemetry("Cal/Truth Heading", Math.toDegrees(truthHeading))
                robot.addTelemetry("Cal/Dropped", recorder?.droppedSampleCount ?: 0L)
            }
        }
    }

    private fun cancelPendingAndRestartDwell() {
        pendingCheckpoint = LocalizationCalibrationCheckpoint.NONE
        pendingSeed = false
        stationaryGate.reset()
    }

    private fun record(
        robot: AresRobot,
        checkpoint: LocalizationCalibrationCheckpoint,
        sampleRunId: Int,
        truthValid: Boolean
    ) {
        recorder?.record(
            LocalizationCalibrationSample.capture(
                timestampMs = RobotClock.currentTimeMillis(),
                platform = LocalizationCalibrationPlatform.FTC,
                testType = testType,
                runId = sampleRunId,
                state = robot.base.store.state,
                measurements = if (checkpoint == LocalizationCalibrationCheckpoint.NONE)
                    robot.base.visionTracker.visionInputs.measurements else emptyList(),
                checkpoint = checkpoint,
                truthValid = truthValid,
                truthX = truthX,
                truthY = truthY,
                truthHeading = truthHeading,
                truthHeadingUnwrapped = true
            )
        )
    }

    private companion object {
        const val DRIVER_NEUTRAL_DEADZONE = 0.03f
        const val TELEMETRY_PERIOD_MS = 100L
    }
}
