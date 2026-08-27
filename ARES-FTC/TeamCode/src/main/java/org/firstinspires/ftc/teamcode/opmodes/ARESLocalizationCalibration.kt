package org.firstinspires.ftc.teamcode.opmodes

import com.areslib.hardware.HardwareRegistry
import com.areslib.math.estimation.LocalizationCalibrationCheckpoint
import com.areslib.math.estimation.LocalizationCalibrationPlatform
import com.areslib.math.estimation.LocalizationCalibrationRecorder
import com.areslib.math.estimation.LocalizationCalibrationSample
import com.areslib.math.estimation.LocalizationCalibrationTestType
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
    private var lastRecordedVisionTimestampMs = Long.MIN_VALUE
    private val stationaryGate = StationaryCalibrationGate()
    private var lastTelemetryMs = 0L

    override fun define() = teleOp {
        setup {
            recorder = LocalizationCalibrationRecorder(LocalizationCalibrationPlatform.FTC).also {
                HardwareRegistry.registerCloseable(it)
            }
            robot.base.isLiveTuningEnabled = false
            robot.addTelemetry("Calibration", "Use surveyed poses; do not use Limelight as truth")
        }

        controls {
            driver.b.onPress("Cycle localization calibration test") {
                val values = LocalizationCalibrationTestType.entries
                testType = values[(testType.ordinal + 1) % values.size]
                continuousRecording = false
            }
            driver.dpadRight.onPress("Increase surveyed X by 5 cm") { truthX += 0.05 }
            driver.dpadLeft.onPress("Decrease surveyed X by 5 cm") { truthX -= 0.05 }
            driver.dpadUp.onPress("Increase surveyed Y by 5 cm") { truthY += 0.05 }
            driver.dpadDown.onPress("Decrease surveyed Y by 5 cm") { truthY -= 0.05 }
            driver.rightBumper.onPress("Increase surveyed heading by 5 degrees") {
                truthHeading = com.areslib.math.wrapAngle(truthHeading + Math.toRadians(5.0))
            }
            driver.leftBumper.onPress("Decrease surveyed heading by 5 degrees") {
                truthHeading = com.areslib.math.wrapAngle(truthHeading - Math.toRadians(5.0))
            }
            driver.back.onPress("Zero surveyed pose") {
                truthX = 0.0
                truthY = 0.0
                truthHeading = 0.0
            }
            driver.start.onPress("Seed localization to surveyed pose") {
                robot.base.resetPose(Pose2d(truthX, truthY, Rotation2d(truthHeading)))
            }
            driver.a.onPress("Toggle stationary calibration recording") {
                continuousRecording = !continuousRecording
            }
            driver.x.onPress("Record surveyed route start") {
                robot.base.resetPose(Pose2d(truthX, truthY, Rotation2d(truthHeading)))
                pendingRunId = runId
                pendingCheckpoint = LocalizationCalibrationCheckpoint.START
            }
            driver.y.onPress("Record surveyed route end") {
                pendingRunId = runId
                pendingCheckpoint = LocalizationCalibrationCheckpoint.END
                runId++
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
                driverNeutral = driverNeutral,
                translationMetersPerSecond = measuredTranslationMps,
                angularRadiansPerSecond = driveState.measuredAngularVelocityRadiansPerSecond,
            )
            val checkpoint = pendingCheckpoint
            if (checkpoint != LocalizationCalibrationCheckpoint.NONE && stationaryReady) {
                record(robot, checkpoint, pendingRunId, truthValid = true)
                pendingCheckpoint = LocalizationCalibrationCheckpoint.NONE
            }

            if (continuousRecording && stationaryReady &&
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
                if (newestVisionTimestamp > lastRecordedVisionTimestampMs) {
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
                robot.addTelemetry("Cal/Truth X", truthX)
                robot.addTelemetry("Cal/Truth Y", truthY)
                robot.addTelemetry("Cal/Truth Heading", Math.toDegrees(truthHeading))
                robot.addTelemetry("Cal/Dropped", recorder?.droppedSampleCount ?: 0L)
            }
        }
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
                measurements = robot.base.visionTracker.visionInputs.measurements,
                checkpoint = checkpoint,
                truthValid = truthValid,
                truthX = truthX,
                truthY = truthY,
                truthHeading = truthHeading
            )
        )
    }

    private companion object {
        const val DRIVER_NEUTRAL_DEADZONE = 0.03f
        const val TELEMETRY_PERIOD_MS = 100L
    }
}

/** Neutral-command plus measured-motion dwell required before any calibration sample is written. */
internal class StationaryCalibrationGate(
    private val translationThresholdMps: Double = 0.03,
    private val angularThresholdRps: Double = 0.05,
    private val dwellMs: Long = 500L,
) {
    private var stationarySinceMs = Long.MIN_VALUE

    init {
        require(translationThresholdMps.isFinite() && translationThresholdMps >= 0.0)
        require(angularThresholdRps.isFinite() && angularThresholdRps >= 0.0)
        require(dwellMs >= 0L)
    }

    fun update(
        nowMs: Long,
        driverNeutral: Boolean,
        translationMetersPerSecond: Double,
        angularRadiansPerSecond: Double,
    ): Boolean {
        val stationary = driverNeutral && translationMetersPerSecond.isFinite() &&
            angularRadiansPerSecond.isFinite() &&
            kotlin.math.abs(translationMetersPerSecond) <= translationThresholdMps &&
            kotlin.math.abs(angularRadiansPerSecond) <= angularThresholdRps
        if (!stationary) {
            stationarySinceMs = Long.MIN_VALUE
            return false
        }
        if (stationarySinceMs == Long.MIN_VALUE || nowMs < stationarySinceMs) {
            stationarySinceMs = nowMs
            return dwellMs == 0L
        }
        return nowMs - stationarySinceMs >= dwellMs
    }
}
