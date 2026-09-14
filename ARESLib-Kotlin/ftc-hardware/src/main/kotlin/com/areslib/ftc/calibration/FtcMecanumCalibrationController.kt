package com.areslib.ftc.calibration

import com.areslib.ftc.calibration.FtcCalibrationFeedback.validDriveFeedback
import com.areslib.control.assist.SysIdManager
import com.areslib.control.assist.SysIdMechanism
import com.areslib.control.assist.SysIdRoutine
import com.areslib.ftc.drivetrain.MecanumHardwareIO
import com.areslib.ftc.drivetrain.PinpointIO
import com.areslib.ftc.core.retainFtcFailure
import com.areslib.ftc.telemetry.FtcTelemetryManager
import com.areslib.ftc.vision.FtcVisionTracker
import com.areslib.Store
import com.areslib.util.RobotClock
import com.areslib.hardware.actuator.FlywheelIO
import com.areslib.control.assist.FlywheelSysIdAdapter
import com.areslib.control.assist.SysIdMechanismIO

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
 * Pre-allocates constant buffers (e.g., [calibrationTelemetry.emptyData]) and updates primitive metrics arrays in-place to avoid dynamic heap allocations inside 50Hz update loops.
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
            supportedMechanismsTelemetry = if (value == null) DRIVE_SYSID_MECHANISMS else DRIVE_AND_FLYWHEEL_SYSID_MECHANISMS
        }
    private var flywheelSysIdAdapter: SysIdMechanismIO? = null
    private var supportedMechanismsTelemetry = DRIVE_SYSID_MECHANISMS
    private var lastCommandProcessed = ""
    private var enableToken = ""
    private var neutralizedDuringInputPass = false

    /** True only after a calibration-specific OpMode explicitly opts in locally. */
    var modeEnabled = false
        private set

    /**
     * True only after the dashboard presents a fresh enable token while commanding STOP.
     * A retained command/token from an earlier run can therefore never energize hardware.
     */
    var networkArmed = false
        private set

    /**
     * Disabled-equivalent window for typed tuning. This is true only after the current client has
     * a fresh lease, STOP is the processed command, every calibration routine is inactive, and
     * calibration still owns the neutral output path.
     */
    val neutralOutputHoldActive: Boolean
        get() = modeEnabled && networkArmed && lastCommandProcessed == STOP_COMMAND &&
            !sysIdManager.isActive() && activeCalibration == "NONE"
    private var enableLeaseSequence = INVALID_LEASE_SEQUENCE
    private var lastEnableLeaseAtMs = 0L

    /** Identifier name of the currently active physical calibration routine (`"NONE"`, `"PINPOINT_SPIN"`, `"TRACK_WIDTH_SPIN"`, etc.). */
    var activeCalibration = "NONE"
        private set
    private var calibrationStartTimeMs = 0L
    private var lastCalibrationTimeMs = 0L
    private var calibrationStallActive = false
    private var calibrationStallStartMs = 0L
    private val calibrationTelemetry = FtcCalibrationTelemetry(this)

    /**
     * Enables the calibration control surface for this OpMode only.
     *
     * The caller must subsequently publish a new non-blank `SysId/EnableToken` while
     * `SysId/Command` is `STOP`. The token present at the instant this method is called is
     * deliberately treated as retained/stale and cannot arm the controller.
     */
    fun enableMode(telemetryManager: FtcTelemetryManager, mecanumIO: MecanumHardwareIO) {
        stopAndNeutral(mecanumIO)
        modeEnabled = true
        networkArmed = false
        neutralizedDuringInputPass = false
        enableToken = telemetryManager.nt4.getString(ENABLE_TOKEN_TOPIC, "")
        enableLeaseSequence = telemetryManager.nt4.getNumber(ENABLE_LEASE_TOPIC, INVALID_LEASE_SEQUENCE)
            .takeIf(::isValidLeaseSequence) ?: INVALID_LEASE_SEQUENCE
        lastEnableLeaseAtMs = 0L
        lastCommandProcessed = ""
        telemetryManager.nt4.putBoolean("SysId/ModeEnabled", true)
        telemetryManager.nt4.putBoolean("SysId/Armed", false)
    }

    /** Disarms calibration immediately and returns every owned output to neutral. */
    fun disableMode(telemetryManager: FtcTelemetryManager, mecanumIO: MecanumHardwareIO) {
        // Revoke ownership before touching hardware so even a failed stop cannot leave the
        // controller logically armed for a later loop.
        modeEnabled = false
        networkArmed = false
        neutralizedDuringInputPass = false
        enableToken = ""
        enableLeaseSequence = INVALID_LEASE_SEQUENCE
        lastEnableLeaseAtMs = 0L
        lastCommandProcessed = ""
        var firstFailure: Throwable? = null
        try {
            stopAndNeutral(mecanumIO)
        } catch (failure: Throwable) {
            firstFailure = retainFtcFailure(firstFailure, failure)
        }
        try {
            telemetryManager.nt4.putBoolean("SysId/ModeEnabled", false)
            telemetryManager.nt4.putBoolean("SysId/Armed", false)
            telemetryManager.dataLoggingTelemetry.putString("SysId/Status", "NONE")
            telemetryManager.dataLoggingTelemetry.putDoubleArray("SysId/Data", calibrationTelemetry.emptyData)
            telemetryManager.nt4.putString("SysId/Status", "NONE")
            telemetryManager.nt4.putDoubleArray("SysId/Data", calibrationTelemetry.emptyData)
            telemetryManager.nt4.update()
        } catch (failure: Throwable) {
            firstFailure = retainFtcFailure(firstFailure, failure)
        }
        firstFailure?.let { throw it }
    }

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
        if (!modeEnabled) {
            return
        }

        val command = telemetryManager.nt4.getString(COMMAND_TOPIC, "").trim()
        val observedToken = telemetryManager.nt4.getString(ENABLE_TOKEN_TOPIC, "").trim()
        val observedLease = telemetryManager.nt4.getNumber(ENABLE_LEASE_TOPIC, INVALID_LEASE_SEQUENCE)
        val nowMs = RobotClock.currentTimeMillis()
        if (!networkArmed) {
            val hasFreshToken = observedToken.isNotEmpty() &&
                observedToken.length <= MAX_ENABLE_TOKEN_LENGTH &&
                observedToken != enableToken
            val hasFreshLease = isValidLeaseSequence(observedLease) && observedLease != enableLeaseSequence
            if (hasFreshToken && hasFreshLease && command == STOP_COMMAND) {
                enableToken = observedToken
                enableLeaseSequence = observedLease
                lastEnableLeaseAtMs = nowMs
                networkArmed = true
                lastCommandProcessed = STOP_COMMAND
                stopAndNeutral(mecanumIO)
                neutralizedDuringInputPass = true
                onResetTuning()
                telemetryManager.nt4.putString("SysId/Error", "")
                telemetryManager.nt4.putBoolean("SysId/Armed", true)
            }
            // enableMode() already performed the one-shot neutral required at this safety
            // boundary. While waiting for a fresh handshake, calibration does not own the
            // drivetrain; repeatedly neutralizing here would erase the tuning OpMode's manual
            // repositioning command later in the same frame.
            return
        }

        // Once armed, changing or clearing the token is a session boundary and fails closed.
        if (observedToken != enableToken) {
            disarmForInputFault(telemetryManager, mecanumIO, "ENABLE_TOKEN_CHANGED")
            return
        }

        if (!isValidLeaseSequence(observedLease) || observedLease < enableLeaseSequence) {
            disarmForInputFault(telemetryManager, mecanumIO, "ENABLE_LEASE_INVALID")
            return
        }
        if (observedLease > enableLeaseSequence) {
            enableLeaseSequence = observedLease
            lastEnableLeaseAtMs = nowMs
        }
        if (nowMs < lastEnableLeaseAtMs || nowMs - lastEnableLeaseAtMs > ENABLE_LEASE_TIMEOUT_MS) {
            disarmForInputFault(telemetryManager, mecanumIO, "ENABLE_LEASE_EXPIRED")
            return
        }

        if (command != lastCommandProcessed) {
            lastCommandProcessed = command
            if (command.isNotBlank()) {
                println("[ARES Calibration] Received command: $command")
            }
            // A new mechanism must never inherit the previous mechanism's energized outputs.
            stopAndNeutral(mecanumIO)
            lastCalibrationTimeMs = nowMs

            when {
                command == STOP_COMMAND -> {
                    stopAndNeutral(mecanumIO)
                    neutralizedDuringInputPass = true
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
                        val mechanism = enumValues<SysIdMechanism>().firstOrNull { it.name == mechStr }
                        val routine = enumValues<SysIdRoutine>().firstOrNull {
                            it.name == routineStr && it != SysIdRoutine.NONE
                        }
                        val supported = mechanism == SysIdMechanism.LINEAR || mechanism == SysIdMechanism.ANGULAR ||
                            mechanism == SysIdMechanism.FLYWHEEL && flywheelIO != null
                        if (mechanism == null || routine == null || !supported) {
                            networkArmed = false
                            stopAndNeutral(mecanumIO)
                            neutralizedDuringInputPass = true
                            telemetryManager.nt4.putBoolean("SysId/Armed", false)
                            telemetryManager.nt4.putString("SysId/Error",
                                if (mechanism != null && routine != null && !supported) "UNSUPPORTED_SYSID_MECHANISM"
                                else "INVALID_COMMAND")
                        } else {
                            val pose = store.state.drive.poseEstimator
                            sysIdManager.start(
                                mechanism = mechanism,
                                routine = routine,
                                timestampMs = RobotClock.currentTimeMillis(),
                                x = pose.estimatedPoseX,
                                y = pose.estimatedPoseY,
                                heading = pose.estimatedPoseHeading
                            )
                        }
                    } else {
                        networkArmed = false
                        stopAndNeutral(mecanumIO)
                        neutralizedDuringInputPass = true
                        telemetryManager.nt4.putBoolean("SysId/Armed", false)
                        telemetryManager.nt4.putString("SysId/Error", "INVALID_COMMAND")
                    }
                }
                else -> {
                    networkArmed = false
                    stopAndNeutral(mecanumIO)
                    neutralizedDuringInputPass = true
                    telemetryManager.nt4.putBoolean("SysId/Armed", false)
                    telemetryManager.nt4.putString("SysId/Error", "INVALID_COMMAND")
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
        if (neutralizedDuringInputPass) {
            // Do not let normal kinematics overwrite a STOP/token/fault neutral in the same robot
            // frame. Ownership is released immediately after this single output pass.
            neutralizedDuringInputPass = false
            return true
        }
        if (!modeEnabled || !networkArmed) {
            // Enabled-but-unarmed is an observation state, not output ownership. Safety
            // transitions (enable, token change, STOP, fault, and disable) neutral exactly once;
            // normal kinematics regains authority on the following frame.
            return false
        }

        val drive = store.state.drive
        val pose = drive.poseEstimator
        val timestamp = RobotClock.currentTimeMillis()

        if (sysIdManager.isActive()) {
            if (!batteryVoltage.isFinite() || batteryVoltage <= 0.0) {
                stopAndNeutral(mecanumIO)
                telemetryManager.nt4.putString("SysId/Error", "SYSID_INVALID_SUPPLY")
                return true
            }
            // FtcPowerManager distributes its global limit to every registered motor.
            // Characterization requires full power for flywheel and drivetrain alike.
            if (!fullSysIdPower(mecanumIO.flIO.powerScale) || !fullSysIdPower(mecanumIO.frIO.powerScale) ||
                !fullSysIdPower(mecanumIO.rlIO.powerScale) || !fullSysIdPower(mecanumIO.rrIO.powerScale)) {
                stopAndNeutral(mecanumIO)
                telemetryManager.nt4.putString("SysId/Error", "SYSID_REQUIRES_FULL_POWER")
                return true
            }
            if (sysIdManager.activeMechanism == SysIdMechanism.LINEAR || sysIdManager.activeMechanism == SysIdMechanism.ANGULAR) {
                // The configured limit is per motor. Any invalid cache propagates NaN and aborts.
                val currentAmps = maxOf(
                    maxOf(sysIdCurrent(mecanumIO.flIO), sysIdCurrent(mecanumIO.frIO)),
                    maxOf(sysIdCurrent(mecanumIO.rlIO), sysIdCurrent(mecanumIO.rrIO)))
                val validMotion = validDriveFeedback(drive, timestamp)
                if (!validMotion || !sysIdManager.checkSafety(pose.estimatedPoseX, pose.estimatedPoseY,
                        pose.estimatedPoseHeading, timestamp, currentAmps)) {
                    sysIdManager.stop()
                    mecanumIO.setMotorPowers(0.0, 0.0, 0.0, 0.0)
                    telemetryManager.nt4.putString("SysId/Error",
                        if (!validMotion) "INVALID_DRIVE_MEASUREMENT" else "SYSID_ABORTED")
                } else {
                    val velocity = if (sysIdManager.activeMechanism == SysIdMechanism.LINEAR) {
                        // Velocities and heading belong to the same odometry observation frame.
                        drive.measuredFieldXVelocityMetersPerSecond * kotlin.math.cos(drive.odometryHeading) +
                            drive.measuredFieldYVelocityMetersPerSecond * kotlin.math.sin(drive.odometryHeading)
                    } else {
                        drive.measuredAngularVelocityRadiansPerSecond
                    }

                    val voltage = sysIdManager.update(timestamp, velocity)
                    if (kotlin.math.abs(voltage) > batteryVoltage) {
                        stopAndNeutral(mecanumIO)
                        telemetryManager.nt4.putString("SysId/Error", "SYSID_INSUFFICIENT_SUPPLY")
                        return true
                    }
                    val power = (voltage / batteryVoltage).coerceIn(-1.0, 1.0)

                    if (sysIdManager.activeMechanism == SysIdMechanism.LINEAR) {
                        mecanumIO.setMotorPowers(power, power, power, power)
                    } else {
                        mecanumIO.setMotorPowers(-power, power, -power, power)
                    }
                    calibrationTelemetry.captureSysIdSample(timestamp, velocity)
                }
            } else {
                val adapter = flywheelSysIdAdapter
                val currentAmps = flywheelIO?.let { sysIdCurrent(it) } ?: Double.NaN
                if (adapter == null || !adapter.measurementValid ||
                    !sysIdManager.checkSafety(pose.estimatedPoseX, pose.estimatedPoseY,
                        pose.estimatedPoseHeading, timestamp, currentAmps)) {
                    sysIdManager.stop()
                    adapter?.stop()
                    telemetryManager.nt4.putString("SysId/Error", when {
                        adapter == null -> "NO_FLYWHEEL_ADAPTER"
                        !currentAmps.isFinite() -> "INVALID_FLYWHEEL_CURRENT"
                        !adapter.measurementValid -> "INVALID_FLYWHEEL_MEASUREMENT"
                        else -> "SYSID_ABORTED"
                    })
                } else {
                    val measuredVelocity = customSysIdVelocityProvider?.invoke() ?: adapter.velocity
                    val voltage = sysIdManager.update(timestamp, measuredVelocity)
                    if (kotlin.math.abs(voltage) > batteryVoltage) {
                        stopAndNeutral(mecanumIO)
                        telemetryManager.nt4.putString("SysId/Error", "SYSID_INSUFFICIENT_SUPPLY")
                        return true
                    }
                    adapter.setCharacterizationVoltage(voltage)
                    calibrationTelemetry.captureSysIdSample(timestamp, measuredVelocity)
                }
            }
            return true
        } else if (activeCalibration != "NONE") {
            val elapsedMs = timestamp - calibrationStartTimeMs
            val timeoutMs = if (activeCalibration == "LINEAR_DRIVE") 3000L else 5000L
            if (timestamp < calibrationStartTimeMs || timestamp < lastCalibrationTimeMs || elapsedMs < 0L) {
                stopAndNeutral(mecanumIO)
                telemetryManager.nt4.putString("SysId/Error", "CALIBRATION_CLOCK_INVALID")
                return true
            }
            lastCalibrationTimeMs = timestamp

            if (elapsedMs > timeoutMs) {
                stopAndNeutral(mecanumIO)
                // SysId/Command is dashboard-owned input. Publishing a local STOP here would
                // claim the topic on the custom NT4 server and reject every later client command.
                telemetryManager.nt4.putString(STATUS_TOPIC, "NONE")
                onResetTuning()
            } else {
                // Vision collection is stationary; moving routines also require trustworthy drive feedback.
                val safetyError = if (activeCalibration == "VISION_CALIBRATION") null else
                    empiricalDriveSafetyError(drive, timestamp, batteryVoltage, mecanumIO)
                if (safetyError != null) {
                    stopAndNeutral(mecanumIO)
                    telemetryManager.nt4.putString("SysId/Error", safetyError)
                    return true
                }
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
        // Reaching this point means the calibration session is armed but intentionally idle
        // (normally after STOP). Keep output ownership without issuing another hardware write so
        // a persistent pre-arm Redux drive command cannot be reapplied by normal kinematics.
        return true
    }

    /** Consume one cached current value and its freshness contract; never poll hardware here. */
    private fun sysIdCurrent(source: com.areslib.hardware.CurrentSourceIO): Double {
        val reading = source.currentAmps
        return if (source.isCurrentReadingValid(reading)) reading else Double.NaN
    }

    private fun fullSysIdPower(scale: Double): Boolean = scale.isFinite() && scale in 0.999..1.0

    private fun empiricalDriveSafetyError(drive: com.areslib.state.DriveState, timestamp: Long,
                                          batteryVoltage: Double, io: MecanumHardwareIO): String? {
        if (!batteryVoltage.isFinite() || batteryVoltage <= 0.0) return "CALIBRATION_INVALID_SUPPLY"
        if (!validDriveFeedback(drive, timestamp)) return "INVALID_DRIVE_MEASUREMENT"
        if ((activeCalibration == "LINEAR_DRIVE" || activeCalibration == "TRACK_WIDTH_SPIN") &&
            (!io.flIO.position.isFinite() || !io.frIO.position.isFinite() ||
                !io.rlIO.position.isFinite() || !io.rrIO.position.isFinite())) return "INVALID_ENCODER_MEASUREMENT"
        if (!validPowerScale(io.flIO.powerScale) || !validPowerScale(io.frIO.powerScale) ||
            !validPowerScale(io.rlIO.powerScale) || !validPowerScale(io.rrIO.powerScale)) return "CALIBRATION_INVALID_POWER_SCALE"
        val limit = sysIdManager.maxCurrentAmps
        val timeout = sysIdManager.stallTimeoutMs
        if (!limit.isFinite() || limit <= 0.0 || timeout < 0L) return "CALIBRATION_INVALID_CURRENT_LIMIT"
        val current = maxOf(maxOf(sysIdCurrent(io.flIO), sysIdCurrent(io.frIO)),
            maxOf(sysIdCurrent(io.rlIO), sysIdCurrent(io.rrIO)))
        if (!current.isFinite()) return "CALIBRATION_INVALID_CURRENT"
        if (current >= limit) {
            if (!calibrationStallActive) {
                calibrationStallActive = true
                calibrationStallStartMs = timestamp
            }
            if (timestamp - calibrationStallStartMs >= timeout) return "CALIBRATION_OVERCURRENT"
        } else {
            calibrationStallActive = false
        }
        return null
    }

    private fun validPowerScale(scale: Double): Boolean = scale.isFinite() && scale in 0.0..1.0

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
        calibrationTelemetry.publishRobotTelemetry(
            timestamp, store, telemetryManager, mecanumIO, visionTracker,
            ticksPerMeterSetting, defaultTicksPerMeter, supportedMechanismsTelemetry,
        )
    }

    private fun stopAndNeutral(mecanumIO: MecanumHardwareIO) {
        activeCalibration = "NONE"
        calibrationStallActive = false
        calibrationTelemetry.invalidateSample()
        var firstFailure: Throwable? = null
        try {
            sysIdManager.stop()
        } catch (failure: Throwable) {
            firstFailure = retainFtcFailure(firstFailure, failure)
        }
        try {
            flywheelSysIdAdapter?.stop()
        } catch (failure: Throwable) {
            firstFailure = retainFtcFailure(firstFailure, failure)
        }
        try {
            mecanumIO.setMotorPowers(0.0, 0.0, 0.0, 0.0)
        } catch (failure: Throwable) {
            firstFailure = retainFtcFailure(firstFailure, failure)
        }
        firstFailure?.let { throw it }
    }

    private fun disarmForInputFault(
        telemetryManager: FtcTelemetryManager,
        mecanumIO: MecanumHardwareIO,
        reason: String
    ) {
        networkArmed = false
        lastEnableLeaseAtMs = 0L
        stopAndNeutral(mecanumIO)
        neutralizedDuringInputPass = true
        telemetryManager.nt4.putBoolean("SysId/Armed", false)
        telemetryManager.nt4.putString("SysId/Error", reason)
    }

    private fun isValidLeaseSequence(value: Double): Boolean =
        value.isFinite() && value >= 0.0 && value <= MAX_SAFE_INTEGER && value == kotlin.math.floor(value)

    private companion object {
        val DRIVE_SYSID_MECHANISMS = "LINEAR,ANGULAR"
        val DRIVE_AND_FLYWHEEL_SYSID_MECHANISMS = "LINEAR,ANGULAR,FLYWHEEL"
        const val COMMAND_TOPIC = "SysId/Command"
        const val STATUS_TOPIC = "SysId/Status"
        const val ENABLE_TOKEN_TOPIC = "SysId/EnableToken"
        const val ENABLE_LEASE_TOPIC = "SysId/EnableLease"
        const val STOP_COMMAND = "STOP"
        const val MAX_ENABLE_TOKEN_LENGTH = 128
        const val ENABLE_LEASE_TIMEOUT_MS = 500L
        const val INVALID_LEASE_SEQUENCE = -1.0
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0
    }
}

