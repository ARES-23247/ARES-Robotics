package com.areslib.ftc.calibration

import com.areslib.control.assist.SysIdManager
import com.areslib.control.assist.SysIdMechanism
import com.areslib.control.assist.SysIdRoutine
import com.areslib.ftc.drivetrain.MecanumHardwareIO
import com.areslib.ftc.drivetrain.PinpointIO
import com.areslib.ftc.telemetry.FtcTelemetryManager
import com.areslib.ftc.vision.FtcVisionTracker
import com.areslib.hardware.sensor.ImuInputs
import com.areslib.Store
import com.areslib.util.RobotClock
import com.areslib.hardware.actuator.FlywheelIO
import com.areslib.control.assist.FlywheelSysIdAdapter
import com.areslib.control.assist.SysIdMechanismIO
import com.areslib.state.MechanismTuningState

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

    /** Optional season flywheel adapter shared by FTC and FRC characterization paths. */
    var flywheelIO: FlywheelIO? = null
        set(value) {
            field = value
            flywheelSysIdAdapter = value?.let(::FlywheelSysIdAdapter)
            lastFlywheelTuning = null
        }
    private var flywheelSysIdAdapter: SysIdMechanismIO? = null
    private var lastFlywheelTuning: MechanismTuningState? = null

    private var lastCommandProcessed = ""

    /** Identifier name of the currently active physical calibration routine (`"NONE"`, `"PINPOINT_SPIN"`, `"TRACK_WIDTH_SPIN"`, etc.). */
    var activeCalibration = "NONE"
        private set
    private var calibrationStartTimeMs = 0L
    private val EMPTY_SYSID_DATA = DoubleArray(0)
    private val sysIdData = DoubleArray(5)
    private val pinpointData = DoubleArray(5)
    private val trackWidthData = DoubleArray(6)
    private val visionData = DoubleArray(5)
    private val linearData = DoubleArray(5)

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
        val flywheelTuning = store.state.tuning.subsystem.flywheel
        if (flywheelTuning != lastFlywheelTuning) {
            flywheelIO?.configureVelocityController(flywheelTuning.velocityGains, flywheelTuning.feedforward)
            lastFlywheelTuning = flywheelTuning
        }
        val command = telemetryManager.nt4.getString("SysId/Command", "")
        if (command != lastCommandProcessed) {
            lastCommandProcessed = command
            if (command.isNotBlank()) {
                println("[ARES Calibration] Received command: $command")
            }
            activeCalibration = "NONE"
            sysIdManager.stop()
            flywheelSysIdAdapter?.stop()

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
                val adapter = flywheelSysIdAdapter
                if (adapter == null || !adapter.measurementValid ||
                    !sysIdManager.checkSafety(pose.x, pose.y, pose.heading.radians, timestamp)) {
                    sysIdManager.stop()
                    adapter?.stop()
                    telemetryManager.nt4.putString("SysId/Error", if (adapter == null) "NO_FLYWHEEL_ADAPTER" else "INVALID_FLYWHEEL_MEASUREMENT")
                } else {
                    val measuredVelocity = customSysIdVelocityProvider?.invoke() ?: adapter.velocity
                    val voltage = sysIdManager.update(timestamp, measuredVelocity)
                    adapter.setCharacterizationVoltage(voltage)
                }
            }
            return true
        } else if (activeCalibration != "NONE") {
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
     * @param visionTracker Vision tracking engine reference.
     * @param ticksPerMeterSetting Configured encoder ticks per meter setting ($ticks/m$).
     * @param defaultTicksPerMeter Default fallback encoder ticks per meter ($ticks/m$).
     */
    fun publishRobotTelemetry(
        timestamp: Long,
        store: Store,
        telemetryManager: FtcTelemetryManager,
        mecanumIO: MecanumHardwareIO,
        visionTracker: FtcVisionTracker,
        ticksPerMeterSetting: Double,
        defaultTicksPerMeter: Double
    ) {
        val dataLogging = telemetryManager.dataLoggingTelemetry
        if (sysIdManager.isActive()) {
            dataLogging.putString("SysId/Status", sysIdManager.activeRoutine.name)
            telemetryManager.nt4.putString("SysId/Status", sysIdManager.activeRoutine.name)
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
                SysIdMechanism.FLYWHEEL -> customSysIdVelocityProvider?.invoke() ?: flywheelSysIdAdapter?.velocity ?: 0.0
            }

            sysIdData[0] = timestamp.toDouble()
            sysIdData[1] = sysIdManager.currentVoltage
            sysIdData[2] = position
            sysIdData[3] = velocity
            sysIdData[4] = sysIdManager.calculatedAcceleration
            dataLogging.putDoubleArray("SysId/Data", sysIdData)
            telemetryManager.nt4.putDoubleArray("SysId/Data", sysIdData)
        } else if (activeCalibration != "NONE") {
            dataLogging.putString("SysId/Status", activeCalibration)
            telemetryManager.nt4.putString("SysId/Status", activeCalibration)
            val pose = store.state.drive.poseEstimator.estimatedPose
            when (activeCalibration) {
                "PINPOINT_SPIN" -> {
                    pinpointData[0] = timestamp.toDouble()
                    pinpointData[1] = pose.x
                    pinpointData[2] = pose.y
                    pinpointData[3] = pose.heading.radians
                    pinpointData[4] = 0.0
                    dataLogging.putDoubleArray("SysId/Data", pinpointData)
                    telemetryManager.nt4.putDoubleArray("SysId/Data", pinpointData)
                }
                "TRACK_WIDTH_SPIN" -> {
                    val currentTicks = store.state.tuning.ticksPerMeter
                    val ticks = if (currentTicks > 0.0) currentTicks else ticksPerMeterSetting.takeIf { it > 0.0 } ?: defaultTicksPerMeter

                    val flPosMeters = mecanumIO.flIO.position / ticks
                    val frPosMeters = mecanumIO.frIO.position / ticks
                    val rlPosMeters = mecanumIO.rlIO.position / ticks
                    val rrPosMeters = mecanumIO.rrIO.position / ticks
                    // The robot loop already cached this heading; never trigger a second IMU hardware read here.
                    val imuHeading = store.state.drive.odometryHeading
                    trackWidthData[0] = timestamp.toDouble()
                    trackWidthData[1] = flPosMeters
                    trackWidthData[2] = frPosMeters
                    trackWidthData[3] = rlPosMeters
                    trackWidthData[4] = rrPosMeters
                    trackWidthData[5] = imuHeading
                    dataLogging.putDoubleArray("SysId/Data", trackWidthData)
                    telemetryManager.nt4.putDoubleArray("SysId/Data", trackWidthData)
                }
                "VISION_CALIBRATION" -> {
                    val lastLL = visionTracker.lastLimelightPose
                    val tagX = lastLL?.x ?: 0.0
                    val tagY = lastLL?.y ?: 0.0
                    val tagHeading = lastLL?.heading?.radians ?: 0.0
                    visionData[0] = timestamp.toDouble()
                    visionData[1] = tagX
                    visionData[2] = tagY
                    visionData[3] = tagHeading
                    visionData[4] = 0.0
                    dataLogging.putDoubleArray("SysId/Data", visionData)
                    telemetryManager.nt4.putDoubleArray("SysId/Data", visionData)
                }
                "LINEAR_DRIVE" -> {
                    val currentTicks = store.state.tuning.ticksPerMeter
                    val ticks = if (currentTicks > 0.0) currentTicks else ticksPerMeterSetting.takeIf { it > 0.0 } ?: defaultTicksPerMeter

                    val flPosMeters = mecanumIO.flIO.position / ticks
                    val frPosMeters = mecanumIO.frIO.position / ticks
                    val rlPosMeters = mecanumIO.rlIO.position / ticks
                    val rrPosMeters = mecanumIO.rrIO.position / ticks
                    val avgDisplacement = (flPosMeters + frPosMeters + rlPosMeters + rrPosMeters) / 4.0

                    linearData[0] = timestamp.toDouble()
                    linearData[1] = avgDisplacement
                    linearData[2] = 0.0
                    linearData[3] = 0.0
                    linearData[4] = 0.0
                    dataLogging.putDoubleArray("SysId/Data", linearData)
                    telemetryManager.nt4.putDoubleArray("SysId/Data", linearData)
                }
                else -> {
                    dataLogging.putDoubleArray("SysId/Data", EMPTY_SYSID_DATA)
                    telemetryManager.nt4.putDoubleArray("SysId/Data", EMPTY_SYSID_DATA)
                }
            }
        } else {
            dataLogging.putString("SysId/Status", "NONE")
            dataLogging.putDoubleArray("SysId/Data", EMPTY_SYSID_DATA)
            telemetryManager.nt4.putString("SysId/Status", "NONE")
            telemetryManager.nt4.putDoubleArray("SysId/Data", EMPTY_SYSID_DATA)
        }
        // Calibration streams are safety/control feedback and must not wait for telemetry throttling.
        telemetryManager.nt4.update()
    }
}

