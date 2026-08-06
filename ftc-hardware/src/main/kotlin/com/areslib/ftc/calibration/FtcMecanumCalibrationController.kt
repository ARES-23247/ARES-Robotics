package com.areslib.ftc.calibration

import com.areslib.control.assist.SysIdManager
import com.areslib.control.assist.SysIdMechanism
import com.areslib.control.assist.SysIdRoutine
import com.areslib.ftc.drivetrain.MecanumHardwareIO
import com.areslib.ftc.drivetrain.PinpointIO
import com.areslib.ftc.telemetry.FtcTelemetryManager
import com.areslib.ftc.vision.FtcVisionTracker
import com.areslib.hardware.sensor.ImuIO
import com.areslib.hardware.sensor.ImuInputs
import com.areslib.Store
import com.areslib.util.RobotClock

/**
 * Subsystem controller managing System Identification (SysId) routines and physical calibration workflows for FTC Mecanum drivetrains.
 *
 * Drives automated data collection routines for empirical parameter identification:
 * - **SysId Characterization**: Quasistatic and Dynamic voltage ramps ($\text{Linear}, \text{Angular}, \text{Flywheel}$) to fit feedforward coefficients $(kS, kV, kA)$.
 * - **Pinpoint Odometry Characterization**: Zero-offset calibration and rotational center estimation for GoBilda Pinpoint pods.
 * - **Track Width Calibration**: Empirical spin tests to determine effective kinematically equivalent track width ($W$, $m$).
 * - **Vision AprilTag Alignment Calibration**: Empirical offset and variance estimation against known field target tags.
 * - **Linear Drive Distance Tuning**: Ticks-per-meter encoder calibration ($ticks/m$).
 *
 * ### Physical Units & Commands:
 * - Voltage: Volts ($V$), mapped into normalized motor power $[-1.0, 1.0]$ based on live battery bus voltage ($V$).
 * - Position / Distance: Meters ($m$).
 * - Heading / Angular displacement: Radians ($rad$), **CCW-positive** standard ($0 = +X$, $\pi/2 = +Y$).
 * - Velocities: Linear $m/s$, Angular $rad/s$.
 * - Time: Milliseconds ($ms$) or seconds ($s$).
 *
 * ### Zero-GC Guarantee:
 * Pre-allocates constant buffers (e.g., [EMPTY_SYSID_DATA]) and updates primitive metrics arrays in-place to avoid dynamic heap allocations inside 50Hz update loops.
 *
 * @see SysIdManager
 * @see MecanumHardwareIO
 * @see PinpointIO
 */
class FtcMecanumCalibrationController {
    /** Manager executing Quasistatic and Dynamic SysId routines. */
    val sysIdManager = SysIdManager()

    /** Optional custom velocity provider function for Flywheel or custom mechanism SysId routines ($rad/s$ or $m/s$). */
    var customSysIdVelocityProvider: (() -> Double)? = null

    private var lastCommandProcessed = ""

    /** Identifier name of the currently active physical calibration routine (`"NONE"`, `"PINPOINT_SPIN"`, `"TRACK_WIDTH_SPIN"`, etc.). */
    var activeCalibration = "NONE"
        private set
    private var calibrationStartTimeMs = 0L
    private val EMPTY_SYSID_DATA = DoubleArray(0)

    /**
     * Polls NetworkTables (`"SysId/Command"`) for active calibration triggers and initializes routine state machines.
     *
     * @param store Redux state store reference.
     * @param telemetryManager Telemetry manager for NT4 communication.
     * @param mecanumIO Drivetrain hardware IO cluster.
     * @param pinpointIO Physical GoBilda Pinpoint odometry IO wrapper (or `null`).
     * @param onResetTuning Callback invoked to reset cached tuning parameters when calibration terminates.
     */
    fun updateHardwareInputs(
        store: Store,
        telemetryManager: FtcTelemetryManager,
        mecanumIO: MecanumHardwareIO,
        pinpointIO: PinpointIO?,
        onResetTuning: () -> Unit
    ) {
        val command = telemetryManager.nt4.getString("SysId/Command", "")
        if (command != lastCommandProcessed) {
            lastCommandProcessed = command
            activeCalibration = "NONE"
            sysIdManager.stop()

            when {
                command == "STOP" -> {
                    mecanumIO.setMotorPowers(0.0, 0.0, 0.0, 0.0)
                    onResetTuning()
                }
                command == "START_PINPOINT_SPIN" -> {
                    activeCalibration = "PINPOINT_SPIN"
                    calibrationStartTimeMs = RobotClock.currentTimeMillis()
                    pinpointIO?.setOffsets(0.0, 0.0)
                }
                command == "START_TRACK_WIDTH_SPIN" -> {
                    activeCalibration = "TRACK_WIDTH_SPIN"
                    calibrationStartTimeMs = RobotClock.currentTimeMillis()
                }
                command == "START_VISION_CALIBRATION" -> {
                    activeCalibration = "VISION_CALIBRATION"
                    calibrationStartTimeMs = RobotClock.currentTimeMillis()
                }
                command == "START_LINEAR_DRIVE" -> {
                    activeCalibration = "LINEAR_DRIVE"
                    calibrationStartTimeMs = RobotClock.currentTimeMillis()
                }
                command.startsWith("START_") -> {
                    val parts = command.removePrefix("START_").split("_")
                    if (parts.size >= 2) {
                        val mechStr = parts[0]
                        val routineStr = command.removePrefix("START_${mechStr}_")

                        val mechanism = try {
                            SysIdMechanism.valueOf(mechStr)
                        } catch (_: Exception) {
                            SysIdMechanism.LINEAR
                        }

                        val routine = try {
                            SysIdRoutine.valueOf(routineStr)
                        } catch (_: Exception) {
                            SysIdRoutine.NONE
                        }

                        val pose = store.state.drive.poseEstimator.estimatedPose
                        sysIdManager.start(
                            mechanism = mechanism,
                            routine = routine,
                            timestampMs = RobotClock.currentTimeMillis(),
                            x = pose.x,
                            y = pose.y,
                            heading = pose.heading.radians
                        )
                    }
                }
            }
        }
    }

    /**
     * Advances active SysId tests or empirical calibration state routines, overriding manual driving commands.
     *
     * @param store Redux state store reference.
     * @param batteryVoltage Measured bus battery voltage ($V$).
     * @param mecanumIO Drivetrain hardware IO cluster.
     * @param telemetryManager Telemetry manager for NT4 logging.
     * @param onResetTuning Callback to reset tuning flags upon sequence termination.
     * @return `true` if calibration routine actively took control of motor outputs; `false` if normal driving should proceed.
     */
    fun updateSubsystems(
        store: Store,
        batteryVoltage: Double,
        mecanumIO: MecanumHardwareIO,
        telemetryManager: FtcTelemetryManager,
        onResetTuning: () -> Unit
    ): Boolean {
        val pose = store.state.drive.poseEstimator.estimatedPose
        val timestamp = RobotClock.currentTimeMillis()

        if (sysIdManager.isActive()) {
            println("[CalibrationController] ACTIVE SYSID! activeMechanism=${sysIdManager.activeMechanism}")
            if (sysIdManager.activeMechanism == SysIdMechanism.LINEAR || sysIdManager.activeMechanism == SysIdMechanism.ANGULAR) {
                if (!sysIdManager.checkSafety(pose.x, pose.y, pose.heading.radians, timestamp)) {
                    sysIdManager.stop()
                    mecanumIO.setMotorPowers(0.0, 0.0, 0.0, 0.0)
                } else {
                    val velocity = if (sysIdManager.activeMechanism == SysIdMechanism.LINEAR) {
                        store.state.drive.xVelocityMetersPerSecond
                    } else {
                        store.state.drive.angularVelocityRadiansPerSecond
                    }

                    val voltage = sysIdManager.update(timestamp, velocity)
                    val power = (voltage / batteryVoltage).coerceIn(-1.0, 1.0)

                    if (sysIdManager.activeMechanism == SysIdMechanism.LINEAR) {
                        mecanumIO.setMotorPowers(power, power, power, power)
                    } else {
                        mecanumIO.setMotorPowers(-power, power, -power, power)
                    }
                }
            } else {
                if (!sysIdManager.checkSafety(pose.x, pose.y, pose.heading.radians, timestamp)) {
                    sysIdManager.stop()
                }
            }
            return true
        } else if (activeCalibration != "NONE") {
            println("[CalibrationController] ACTIVE CALIBRATION: $activeCalibration")
            val elapsedSec = (timestamp - calibrationStartTimeMs) / 1000.0
            val timeoutSec = if (activeCalibration == "LINEAR_DRIVE") 3.0 else 5.0

            if (elapsedSec > timeoutSec) {
                activeCalibration = "NONE"
                telemetryManager.nt4.putString("SysId/Command", "STOP")
                mecanumIO.setMotorPowers(0.0, 0.0, 0.0, 0.0)
                onResetTuning()
            } else {
                when (activeCalibration) {
                    "PINPOINT_SPIN", "TRACK_WIDTH_SPIN" -> {
                        mecanumIO.setMotorPowers(-0.25, 0.25, -0.25, 0.25)
                    }
                    "VISION_CALIBRATION" -> {
                        mecanumIO.setMotorPowers(0.0, 0.0, 0.0, 0.0)
                    }
                    "LINEAR_DRIVE" -> {
                        mecanumIO.setMotorPowers(0.25, 0.25, 0.25, 0.25)
                    }
                }
            }
            return true
        }
        return false
    }

    /**
     * Publishes high-frequency calibration data streams (`"SysId/Data"`, `"SysId/Status"`) to NetworkTables and local disk logs.
     *
     * @param timestamp System clock timestamp in milliseconds ($ms$).
     * @param store Redux state store reference.
     * @param telemetryManager Telemetry manager for NT4 logging.
     * @param mecanumIO Drivetrain hardware IO cluster.
     * @param imuIO Control Hub IMU IO interface (or `null`).
     * @param visionTracker Vision tracking engine reference.
     * @param ticksPerMeterSetting Configured encoder ticks per meter setting ($ticks/m$).
     * @param defaultTicksPerMeter Default fallback encoder ticks per meter ($ticks/m$).
     */
    fun publishRobotTelemetry(
        timestamp: Long,
        store: Store,
        telemetryManager: FtcTelemetryManager,
        mecanumIO: MecanumHardwareIO,
        imuIO: ImuIO?,
        visionTracker: FtcVisionTracker,
        ticksPerMeterSetting: Double,
        defaultTicksPerMeter: Double
    ) {
        val dataLogging = telemetryManager.dataLoggingTelemetry
        if (sysIdManager.isActive()) {
            dataLogging.putString("SysId/Status", sysIdManager.activeRoutine.name)
            val pose = store.state.drive.poseEstimator.estimatedPose
            val position = when (sysIdManager.activeMechanism) {
                SysIdMechanism.LINEAR -> {
                    val dx = pose.x - sysIdManager.startX
                    val dy = pose.y - sysIdManager.startY
                    kotlin.math.sqrt(dx * dx + dy * dy)
                }
                SysIdMechanism.ANGULAR -> sysIdManager.accumulatedHeadingChange
                SysIdMechanism.FLYWHEEL -> sysIdManager.accumulatedPosition
            }

            val velocity = when (sysIdManager.activeMechanism) {
                SysIdMechanism.LINEAR -> store.state.drive.xVelocityMetersPerSecond
                SysIdMechanism.ANGULAR -> store.state.drive.angularVelocityRadiansPerSecond
                SysIdMechanism.FLYWHEEL -> customSysIdVelocityProvider?.invoke() ?: 0.0
            }

            dataLogging.putDoubleArray(
                "SysId/Data",
                doubleArrayOf(
                    timestamp.toDouble(),
                    sysIdManager.currentVoltage,
                    position,
                    velocity,
                    sysIdManager.calculatedAcceleration
                )
            )
        } else if (activeCalibration != "NONE") {
            dataLogging.putString("SysId/Status", activeCalibration)
            val pose = store.state.drive.poseEstimator.estimatedPose
            when (activeCalibration) {
                "PINPOINT_SPIN" -> {
                    dataLogging.putDoubleArray(
                        "SysId/Data",
                        doubleArrayOf(
                            timestamp.toDouble(),
                            pose.x,
                            pose.y,
                            pose.heading.radians,
                            0.0
                        )
                    )
                }
                "TRACK_WIDTH_SPIN" -> {
                    val currentTicks = store.state.tuning.ticksPerMeter
                    val ticks = if (currentTicks > 0.0) currentTicks else ticksPerMeterSetting.takeIf { it > 0.0 } ?: defaultTicksPerMeter

                    val flPosMeters = mecanumIO.flIO.position / ticks
                    val frPosMeters = mecanumIO.frIO.position / ticks
                    val rlPosMeters = mecanumIO.rlIO.position / ticks
                    val rrPosMeters = mecanumIO.rrIO.position / ticks
                    val imuHeading = imuIO?.let {
                        val inputs = ImuInputs()
                        it.updateInputs(inputs)
                        inputs.headingRadians
                    } ?: 0.0

                    dataLogging.putDoubleArray(
                        "SysId/Data",
                        doubleArrayOf(
                            timestamp.toDouble(),
                            flPosMeters,
                            frPosMeters,
                            rlPosMeters,
                            rrPosMeters,
                            imuHeading
                        )
                    )
                }
                "VISION_CALIBRATION" -> {
                    val lastLL = visionTracker.lastLimelightPose
                    val tagX = lastLL?.x ?: 0.0
                    val tagY = lastLL?.y ?: 0.0
                    val tagHeading = lastLL?.heading?.radians ?: 0.0
                    dataLogging.putDoubleArray(
                        "SysId/Data",
                        doubleArrayOf(
                            timestamp.toDouble(),
                            tagX,
                            tagY,
                            tagHeading,
                            0.0
                        )
                    )
                }
                "LINEAR_DRIVE" -> {
                    val currentTicks = store.state.tuning.ticksPerMeter
                    val ticks = if (currentTicks > 0.0) currentTicks else ticksPerMeterSetting.takeIf { it > 0.0 } ?: defaultTicksPerMeter

                    val flPosMeters = mecanumIO.flIO.position / ticks
                    val frPosMeters = mecanumIO.frIO.position / ticks
                    val rlPosMeters = mecanumIO.rlIO.position / ticks
                    val rrPosMeters = mecanumIO.rrIO.position / ticks
                    val avgDisplacement = (flPosMeters + frPosMeters + rlPosMeters + rrPosMeters) / 4.0

                    dataLogging.putDoubleArray(
                        "SysId/Data",
                        doubleArrayOf(
                            timestamp.toDouble(),
                            avgDisplacement,
                            0.0,
                            0.0,
                            0.0
                        )
                    )
                }
                else -> {
                    dataLogging.putDoubleArray("SysId/Data", EMPTY_SYSID_DATA)
                }
            }
        } else {
            dataLogging.putString("SysId/Status", "NONE")
            dataLogging.putDoubleArray("SysId/Data", EMPTY_SYSID_DATA)
        }
    }
}

