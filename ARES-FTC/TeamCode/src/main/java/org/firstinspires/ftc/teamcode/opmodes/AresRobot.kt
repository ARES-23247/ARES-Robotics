package org.firstinspires.ftc.teamcode.opmodes

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.state.aprilTagPoseMap
import com.areslib.subsystem.Subsystem
import com.qualcomm.robotcore.hardware.HardwareMap
import org.firstinspires.ftc.robotcore.external.Telemetry
import org.firstinspires.ftc.teamcode.config.AresRuntimePolicy
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresFtcMecanumRuntimeConfig
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresTuningConfig
import org.firstinspires.ftc.teamcode.dsl.FtcAutoCapabilities
import org.firstinspires.ftc.teamcode.opmodes.robot.AresDriveController
import org.firstinspires.ftc.teamcode.opmodes.robot.AresSuperstructureController
import org.firstinspires.ftc.teamcode.opmodes.robot.AresTelemetryHelper
import org.firstinspires.ftc.teamcode.subsystems.GeneratedSubsystemRegistry
import org.firstinspires.ftc.teamcode.subsystems.superstructure.GeneratedSuperstructureRegistry

/**
 * Installs generator-owned subsystem plumbing into the same lifecycle used by hand-authored
 * season mechanisms. Required generated factories are intentionally allowed to fail startup;
 * optional-device policy belongs in the generated registry and must not be weakened here.
 */
internal fun installGeneratedSubsystems(
    hardwareMap: HardwareMap,
    register: (Subsystem) -> Unit,
    createAll: (HardwareMap) -> List<Subsystem> = GeneratedSubsystemRegistry::createAll,
): List<Subsystem> = createAll(hardwareMap).also { subsystems ->
    subsystems.forEach(register)
}

/** Installs generated Redux coordinators after their generated subsystem dependencies. */
internal fun installGeneratedSuperstructures(
    register: (Subsystem) -> Unit,
    createAll: () -> List<Subsystem> = GeneratedSuperstructureRegistry::createAll,
): List<Subsystem> = createAll().also { superstructures ->
    superstructures.forEach(register)
}

/**
 * Composition root for the FTC season layer over ARESLib's [FtcMecanumRobot].
 *
 * Required drivetrain/localization configuration is passed to [base]. The two indicator lights
 * and Prism are installed from Robot Builder descriptors through generated lifecycle plumbing.
 *
 * **Physical Units & Conventions:**
 * - Translational velocities: Meters per second ($m/s$).
 * - Angular velocities: Radians per second ($rad/s$).
 * - Heading: CCW-positive radians ($rad$).
 *
 * [update] preserves the hot-loop ordering: the shared frame refreshes every registered hardware
 * cache and computes power protection once, then the season layer consumes those caches, applies
 * interlocks, and writes mechanisms with that same frame's scale. Any exception escaping either
 * layer invokes both subsystem and platform safety before rethrowing.
 *
 * @param hardwareMap FTC device registry. Production drive names are `fl`, `fr`, `rl`, and `rr`.
 * @param localTelemetry optional Driver Station telemetry sink.
 */
class AresRobot(
    val hardwareMap: HardwareMap,
    val localTelemetry: Telemetry? = null
) {
    /** Shared drivetrain, Redux store, EKF, power, logging, telemetry, and hardware lifecycle. */
    val base: FtcMecanumRobot = GeneratedAresFtcMecanumRuntimeConfig.createRobot(
        hardwareMap,
        localTelemetry,
        limelightProxyEnabled = AresRuntimePolicy.options.limelightProxyEnabled,
    )

    private val typedTuningRuntime = GeneratedAresTuningConfig.createRuntime()

    private val driveController = AresDriveController(base)
    private val superstructureController = AresSuperstructureController(base)
    private val telemetryHelper = AresTelemetryHelper(base)
    private var fatalSeasonFailure: Throwable? = null
    /** Latched frame failure; recovery requires constructing a new OpMode robot instance. */
    val fatalUpdateFailure: Throwable?
        get() = fatalSeasonFailure ?: base.fatalUpdateFailure
    private var closed = false
    /** True only after the checked-in season field and its AprilTag projection validate. */
    var hasCanonicalFieldContract: Boolean = false
        private set
    init {
        val tuningProjectRoot = if (com.areslib.ftc.FtcBaseRobot.isAndroid) {
            java.nio.file.Paths.get("/sdcard/FIRST")
        } else {
            java.nio.file.Paths.get("").toAbsolutePath().normalize()
        }
        base.tuningManager = com.areslib.tuning.TuningManager(
            runtime = typedTuningRuntime,
            telemetry = base.telemetryManager.dataLoggingTelemetry,
            contextProvider = {
                com.areslib.tuning.TuningApplyContext(
                    sessionArmed = base.isCalibrationModeArmed,
                    // FTC tuning is armed after START; disabled-only edits fail closed until the
                    // lifecycle exposes a trustworthy Driver Station disabled signal.
                    robotDisabled = false,
                    calibrationParameterUids = FTC_CALIBRATION_PARAMETER_UIDS,
                )
            },
            onApplied = { parameterUid, _ ->
                if (GeneratedAresFtcMecanumRuntimeConfig.supportsRuntimeParameter(parameterUid)) {
                    base.store.dispatch(
                        com.areslib.action.RobotAction.UpdateTuningState(
                            GeneratedAresFtcMecanumRuntimeConfig.withRuntimeValues(
                                base.store.state.tuning,
                                typedTuningRuntime,
                            )
                        )
                    )
                    true
                } else {
                    false
                }
            },
            localProjectRoot = tuningProjectRoot,
            localOverlayFile = tuningProjectRoot.resolve(".ares/local/tuning/runtime.arestuning"),
        )

        // Field symmetry changes by season. Load the checked-in field contract before any
        // autonomous target, waypoint, or costmap is resolved.
        // The asset read stays outside the loader: a missing/failed asset open is an
        // environment failure with the same fallback as an invalid document.
        val fieldBytes = runCatching {
            hardwareMap.appContext.assets.open("paths/field.json").use { it.readBytes() }
        }.getOrNull()
        val fieldContract = fieldBytes?.let(::loadFtcFieldContract)
        if (fieldContract != null) {
            com.areslib.state.RobotFieldManager.setActiveConfig(fieldContract.config)
            // Auto and every TeleOp use the same checked-in field document. This assignment also
            // replaces the shared generic 1-4 square layout selected before this facade is built.
            com.areslib.math.estimation.PoseEstimator.activeTags = fieldContract.tags
            hasCanonicalFieldContract = true
        } else {
            // Never continue vision localization against the generic/shared tag layout when the
            // season contract is missing or invalid. Manual drive remains available without tags.
            com.areslib.state.RobotFieldManager.setActiveConfig(
                com.areslib.state.RobotFieldConfig(
                    id = "unavailable-ftc-season-field",
                    name = "Unavailable FTC season field",
                    fieldType = com.areslib.state.FieldType.FTC,
                    widthMeters = 3.6576,
                    heightMeters = 3.6576,
                    apriltags = emptyList(),
                )
            )
            com.areslib.math.estimation.PoseEstimator.activeTags = emptyMap()
            hasCanonicalFieldContract = false
            addTelemetry("Field", "Canonical field unavailable; vision tags disabled: ${FtcFieldContractLoader.error}")
        }

        // Registrations are process-global. Clear the previous OpMode's optional hardware catalog
        // before discovering this robot instance so missing devices cannot inherit stale commands.
        com.areslib.pathing.NamedCommands.clear()

        // GENERATED - DO NOT EDIT registry entries still use the normal subsystem lifecycle:
        // readSensors -> immutable Redux state -> writeOutputs -> safe/close on every exit path.
        try {
            installGeneratedSubsystems(hardwareMap, base::registerSubsystem)
            installGeneratedSuperstructures(base::registerSubsystem)
        } catch (failure: Throwable) {
            // The facade constructor cannot return a partially initialized robot. The generated
            // registry rolls back its own subsystem list; close the already-created shared robot
            // services before propagating the required-device failure to the OpMode.
            runCatching { base.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }

        FtcAutoCapabilities.registerDriveRecovery(base::recoverDriveOutputWithNeutral)
    }

    /**
     * Safely adds a key-value pair to the robot telemetry stream.
     * @param key The telemetry category label.
     * @param value The telemetry data value.
     */
    fun addTelemetry(key: String, value: Any) = telemetryHelper.addTelemetry(key, value)

    /**
     * Executes one complete season and shared robot frame.
     *
     * Normal sampling/output work preserves the library's zero-allocation hot-path design. Fault
     * transitions and low-rate telemetry may allocate because they are outside the steady-state
     * motor-control path.
     *
     * @param gamepad1 The primary gamepad telemetry state.
     * @param gamepad2 The secondary gamepad telemetry state.
     */
    @kotlin.jvm.JvmOverloads
    fun update(
        gamepad1: com.areslib.telemetry.GamepadState? = null,
        gamepad2: com.areslib.telemetry.GamepadState? = null
    ) {
        // Check both latches before touching any actuator. A failed instance can only recover
        // through normal OpMode reconstruction.
        val priorFailure = fatalUpdateFailure
        if (priorFailure != null) {
            runCatching { base.safeAll() }
            runCatching { base.safeHardware() }
            throw priorFailure
        }
        try {
            // Refresh all registered IO, update drivetrain/EKF, and compute this frame's power
            // scale exactly once. A thrown shared update skips every season write and its safety
            // stop remains final.
            base.update(gamepad1, gamepad2)

            // Consume the season IO values cached by the shared refresh above.
            val timestamp = com.areslib.util.RobotClock.currentTimeMillis()
            base.readAllSensors(timestamp)

            // Apply the freshly computed brownout/current scale to every season mechanism in the
            // same frame. Mechanism voltage normalization reads the same cached power sample.
            base.writeAllOutputs(base.powerManager.powerScale)

            // Continuously update core Driver Station telemetry.
            telemetryHelper.updateTelemetry()
        } catch (t: Throwable) {
            fatalSeasonFailure = t
            runCatching { base.safeAll() }
            runCatching { base.safeHardware() }
            throw t
        }
    }
    /** Commands shaped, alliance-aware field-relative translation and CCW-positive rotation. */
    fun driveFieldCentric(x: Double, y: Double, rotation: Double) = driveController.driveFieldCentric(x, y, rotation)

    /** Commands field-relative drive from a cached gamepad snapshot. */
    fun driveWithGamepad(driver: com.areslib.telemetry.AresGamepad, useHeadingLock: Boolean = true) = driveController.driveWithGamepad(driver, useHeadingLock)

    /** Resets localization to the configured origin for the current Redux alliance. */
    fun resetPoseForAlliance() = driveController.resetPoseForAlliance()

    /** Toggles Redux alliance; the caller decides whether to reset pose. */
    fun toggleAlliance() = superstructureController.toggleAlliance()

    /** Enables the shared calibration receiver only for a dedicated tuning OpMode. */
    fun enableCalibrationMode() {
        base.isLiveTuningEnabled = true
        try {
            base.enableCalibrationMode()
        } catch (failure: Throwable) {
            base.isLiveTuningEnabled = false
            throw failure
        }
    }

    /** Safes drivetrain characterization output and disables live tuning. */
    fun disableCalibrationMode() {
        try {
            base.disableCalibrationMode()
        } finally {
            base.isLiveTuningEnabled = false
        }
    }

    /** Zeroes outputs, closes season subsystems, then always closes shared robot resources. */
    fun close() {
        if (closed) return
        closed = true
        var firstFailure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val primary = firstFailure
                if (primary == null) firstFailure = failure
                else if (primary !== failure) primary.addSuppressed(failure)
            }
        }
        attempt(::disableCalibrationMode)
        attempt(base::safeAll)
        attempt(base::closeSubsystems)
        attempt(base::close)
        firstFailure?.let { throw it }
    }

    private companion object {
        val FTC_CALIBRATION_PARAMETER_UIDS = setOf(
            "ftc.drive.ticks-per-meter",
            "ftc.localization.pinpoint.x-offset",
            "ftc.localization.pinpoint.y-offset",
            "ftc.localization.pinpoint.encoder-resolution",
        )
    }
}

/**
 * Result of a successful canonical field-contract load: the validated season configuration
 * plus its id-indexed AprilTag layout.
 */
internal data class FtcFieldContract(
    val config: com.areslib.state.RobotFieldConfig,
    val tags: Map<Int, com.areslib.math.geometry.Pose3d>,
)

/**
 * Decodes and validates the checked-in FTC season field document.
 *
 * Pure function of the asset bytes so the failure taxonomy (non-FTC geometry, missing or
 * duplicate AprilTags, non-finite tag fields) is unit-testable without an Android context.
 * On any validation failure the caller must install the empty fallback field and disable
 * vision tags — never continue against the shared generic layout.
 */
internal object FtcFieldContractLoader {
    /** Description of the most recent load failure; null after a successful load. */
    var error: String? = null
        private set

    fun load(bytes: ByteArray): FtcFieldContract? {
        error = null
        val config = runCatching {
            bytes.decodeToString().reader().use { reader ->
                com.areslib.state.RobotFieldDocument.decode(reader.readText())
            }
        }.getOrElse { failure ->
            error = failure.message ?: failure::class.java.simpleName
            return null
        }
        val validationIssues = com.areslib.state.RobotFieldValidator.validate(
            config = config,
            requiredFieldType = com.areslib.state.FieldType.FTC,
            requireAprilTags = true,
        )
        if (validationIssues.isNotEmpty()) {
            error = validationIssues.first().message
            return null
        }
        val tags = config.aprilTagPoseMap()
        return FtcFieldContract(config, tags)
    }
}

internal fun loadFtcFieldContract(bytes: ByteArray): FtcFieldContract? = FtcFieldContractLoader.load(bytes)
