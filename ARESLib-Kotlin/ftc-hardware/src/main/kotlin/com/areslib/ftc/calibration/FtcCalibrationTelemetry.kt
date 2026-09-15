package com.areslib.ftc.calibration

import com.areslib.Store
import com.areslib.ftc.calibration.FtcCalibrationFeedback.validDriveFeedback
import com.areslib.ftc.drivetrain.MecanumHardwareIO
import com.areslib.ftc.telemetry.FtcTelemetryManager
import com.areslib.ftc.vision.FtcVisionTracker

/** Owns coherent SysId samples and reusable calibration telemetry buffers for one controller. */
internal class FtcCalibrationTelemetry(private val controller: FtcMecanumCalibrationController) {
    private val sysIdManager get() = controller.sysIdManager
    val emptyData = DoubleArray(0)
    private val sysIdData = DoubleArray(5)
    private var sysIdSampleValid = false
    private var sysIdSampleTimeMs = 0L
    private val pinpointData = DoubleArray(5)
    private val trackWidthData = DoubleArray(7)
    private val visionData = DoubleArray(5)
    private val linearData = DoubleArray(5)

    fun invalidateSample() {
        sysIdSampleValid = false
    }

    /** One coherent signed sample; telemetry must not re-read providers or stamp it with a later time. */
    fun captureSysIdSample(timestamp: Long, velocity: Double) {
        if (!sysIdManager.isActive()) {
            sysIdSampleValid = false
            return
        }
        if (sysIdSampleValid && timestamp == sysIdSampleTimeMs) return
        sysIdSampleTimeMs = timestamp
        sysIdData[0] = timestamp.toDouble()
        sysIdData[1] = sysIdManager.currentVoltage
        sysIdData[2] = sysIdManager.accumulatedPosition
        sysIdData[3] = velocity
        sysIdData[4] = sysIdManager.calculatedAcceleration
        sysIdSampleValid = true
    }

    fun publishRobotTelemetry(
        timestamp: Long,
        store: Store,
        telemetryManager: FtcTelemetryManager,
        mecanumIO: MecanumHardwareIO,
        visionTracker: FtcVisionTracker,
        ticksPerMeterSetting: Double,
        defaultTicksPerMeter: Double,
        supportedMechanismsTelemetry: String,
    ) {
        telemetryManager.nt4.putBoolean("SysId/ModeEnabled", controller.modeEnabled)
        telemetryManager.nt4.putBoolean("SysId/Armed", controller.networkArmed)
        telemetryManager.nt4.putString("SysId/SupportedMechanisms", supportedMechanismsTelemetry)
        var status = "NONE"
        var data = emptyData
        if (sysIdManager.isActive() && sysIdSampleValid) {
            status = sysIdManager.activeRoutine.name
            data = sysIdData
        } else if (controller.activeCalibration != "NONE") {
            status = controller.activeCalibration
            val drive = store.state.drive
            val pose = drive.poseEstimator
            if (controller.activeCalibration != "VISION_CALIBRATION" && !validDriveFeedback(drive, timestamp)) {
                telemetryManager.nt4.putString("SysId/Error", "INVALID_DRIVE_MEASUREMENT")
            } else when (controller.activeCalibration) {
                "PINPOINT_SPIN" -> {
                    pinpointData[0] = timestamp.toDouble()
                    pinpointData[1] = pose.estimatedPoseX
                    pinpointData[2] = pose.estimatedPoseY
                    pinpointData[3] = pose.estimatedPoseHeading
                    pinpointData[4] = 0.0
                    data = pinpointData
                }
                "TRACK_WIDTH_SPIN", "LINEAR_DRIVE" -> {
                    val ticks = calibrationTicks(driveTuningTicks = store.state.tuning.drive.ftc.ticksPerMeter,
                        configuredTicks = ticksPerMeterSetting, defaultTicks = defaultTicksPerMeter)
                    if (!ticks.isFinite()) {
                        telemetryManager.nt4.putString("SysId/Error", "INVALID_ENCODER_SCALE")
                    } else {
                        val fl = mecanumIO.flIO.position / ticks
                        val fr = mecanumIO.frIO.position / ticks
                        val rl = mecanumIO.rlIO.position / ticks
                        val rr = mecanumIO.rrIO.position / ticks
                        if (controller.activeCalibration == "TRACK_WIDTH_SPIN") {
                            val wheelBase = store.state.tuning.drive.wheelBaseMeters
                            if (!positiveFinite(wheelBase)) {
                                telemetryManager.nt4.putString("SysId/Error", "INVALID_DRIVE_GEOMETRY")
                            } else {
                                trackWidthData[0] = timestamp.toDouble()
                                trackWidthData[1] = fl
                                trackWidthData[2] = fr
                                trackWidthData[3] = rl
                                trackWidthData[4] = rr
                                trackWidthData[5] = drive.odometryHeading
                                trackWidthData[6] = wheelBase
                                data = trackWidthData
                            }
                        } else {
                            linearData[0] = timestamp.toDouble()
                            linearData[1] = fl * 0.25 + fr * 0.25 + rl * 0.25 + rr * 0.25
                            linearData[2] = ticks
                            linearData[3] = 0.0
                            linearData[4] = 0.0
                            data = linearData
                        }
                    }
                }
                "VISION_CALIBRATION" -> {
                    val measurement = visionTracker.lastLimelightPose
                    val capturedAt = visionTracker.lastLimelightTimeMs
                    val age = timestamp - capturedAt
                    if (measurement != null && visionTracker.isConnected && capturedAt >= 0L &&
                        timestamp >= capturedAt && age >= 0L && age <= 500L) {
                        visionData[0] = capturedAt.toDouble()
                        visionData[1] = measurement.x
                        visionData[2] = measurement.y
                        visionData[3] = measurement.heading.radians
                        visionData[4] = 0.0
                        data = visionData
                    } else {
                        telemetryManager.nt4.putString("SysId/Error", "INVALID_VISION_MEASUREMENT")
                    }
                }
            }
        }
        for (value in data) {
            if (!value.isFinite()) {
                data = emptyData
                telemetryManager.nt4.putString("SysId/Error", "INVALID_CALIBRATION_SAMPLE")
                break
            }
        }
        val dataLogging = telemetryManager.dataLoggingTelemetry
        dataLogging.putString("SysId/Status", status)
        dataLogging.putDoubleArray("SysId/Data", data)
        telemetryManager.nt4.putString("SysId/Status", status)
        telemetryManager.nt4.putDoubleArray("SysId/Data", data)
        // Calibration feedback must not wait for ordinary telemetry throttling.
        telemetryManager.nt4.update()
    }

    private fun calibrationTicks(driveTuningTicks: Double, configuredTicks: Double, defaultTicks: Double): Double = when {
        positiveFinite(driveTuningTicks) -> driveTuningTicks
        positiveFinite(configuredTicks) -> configuredTicks
        positiveFinite(defaultTicks) -> defaultTicks
        else -> Double.NaN
    }

    private fun positiveFinite(value: Double): Boolean = value.isFinite() && value > 0.0

}
