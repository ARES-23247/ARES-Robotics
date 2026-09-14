package org.firstinspires.ftc.teamcode.opmodes

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.hardware.HardwareRegistry
import com.areslib.state.aprilTagPoseMap
import com.areslib.subsystem.Subsystem
import com.areslib.tuning.TypedTuningRuntime
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
    hardwareRegistry: HardwareRegistry,
    register: (Subsystem) -> Unit,
    createAll: (HardwareMap, HardwareRegistry) -> List<Subsystem> = GeneratedSubsystemRegistry::createAll,
): List<Subsystem> = registerGeneratedSubsystems(createAll(hardwareMap, hardwareRegistry), register)

/** Installs generated Redux coordinators after their generated subsystem dependencies. */
internal fun installGeneratedSuperstructures(
    register: (Subsystem) -> Unit,
    createAll: () -> List<Subsystem> = GeneratedSuperstructureRegistry::createAll,
): List<Subsystem> = registerGeneratedSubsystems(createAll(), register)

/** Registration transfers ownership one object at a time; the caller owns accepted entries. */
private fun registerGeneratedSubsystems(subsystems: List<Subsystem>, register: (Subsystem) -> Unit): List<Subsystem> {
    var registered = 0
    try {
        while (registered < subsystems.size) {
            register(subsystems[registered])
            registered++
        }
        return subsystems
    } catch (failure: Throwable) {
        preserveInterrupt(failure)
        // Factory-time rollback remains the generated registry's responsibility. Do not close
        // accepted entries or close a repeated object twice if initialization rejects the list.
        val released = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Subsystem, Boolean>())
        for (index in 0 until registered) released.add(subsystems[index])
        for (index in subsystems.lastIndex downTo registered) {
            val subsystem = subsystems[index]
            if (!released.add(subsystem)) continue
            try { subsystem.close() }
            catch (cleanup: Throwable) { retainSuppressed(failure, cleanup) }
        }
        throw failure
    }
}

private fun preserveInterrupt(failure: Throwable) {
    if (failure is InterruptedException || failure.cause is InterruptedException) Thread.currentThread().interrupt()
}

private fun retainSuppressed(primary: Throwable, failure: Throwable) {
    preserveInterrupt(failure)
    if (primary !== failure && primary.suppressed.none { it === failure }) primary.addSuppressed(failure)
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

    private val typedTuningRuntime: TypedTuningRuntime
    private val driveController: AresDriveController
    private val superstructureController: AresSuperstructureController
    private val telemetryHelper: AresTelemetryHelper
    private var fatalSeasonFailure: Throwable? = null
    /** Latched frame failure; recovery requires constructing a new OpMode robot instance. */
    val fatalUpdateFailure: Throwable?
        get() = fatalSeasonFailure ?: base.fatalUpdateFailure
    private var closed = false
    /** True only after the checked-in season field and its AprilTag projection validate. */
    var hasCanonicalFieldContract: Boolean = false
        private set
    init {
        try {
            typedTuningRuntime = GeneratedAresTuningConfig.createRuntime()
            driveController = AresDriveController(base)
            superstructureController = AresSuperstructureController(base)
            telemetryHelper = AresTelemetryHelper(base)
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
                isConsumerSupported = GeneratedAresFtcMecanumRuntimeConfig::supportsRuntimeParameter,
                localProjectRoot = tuningProjectRoot,
                localOverlayFile = tuningProjectRoot.resolve(".ares/local/tuning/runtime.arestuning"),
            )

            // Field symmetry changes by season. Load the checked-in field contract before any
            // autonomous target, waypoint, or costmap is resolved.
            // The asset read stays outside the loader: a missing/failed asset open is an
            // environment failure with the same fallback as an invalid document.
            val fieldRead = runCatching {
                hardwareMap.appContext.assets.open("paths/field.json").use { it.readBytes() }
            }
            val fieldContract = fieldRead.getOrNull()?.let(::loadFtcFieldContract)
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
                val detail = fieldRead.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }
                    ?: FtcFieldContractLoader.error
                addTelemetry("Field", "Canonical field unavailable; vision tags disabled: $detail")
            }

            // NamedCommands is still a process-wide catalog. Clear the previous OpMode's optional
            // commands before discovering this robot instance so missing devices cannot inherit them.
            com.areslib.pathing.NamedCommands.clear()

            // GENERATED - DO NOT EDIT registry entries still use the normal subsystem lifecycle:
            // readSensors -> immutable Redux state -> writeOutputs -> safe/close on every exit path.
            installGeneratedSubsystems(hardwareMap, base.hardwareRegistry, base::registerSubsystem)
            installGeneratedSuperstructures(base::registerSubsystem)

            FtcAutoCapabilities.registerDriveRecovery(base::recoverDriveOutputWithNeutral)
        } catch (failure: Throwable) {
            preserveInterrupt(failure)
            // Every season initializer after shared construction belongs to this transaction.
            // Shared close owns neutralization, registered subsystems, hardware and services.
            try { base.close() } catch (cleanup: Throwable) { retainSuppressed(failure, cleanup) }
            throw failure
        }
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
     * Season orchestration consumes cached inputs without per-frame collections. Shared runtime
     * and telemetry retain their own budgets; fault transitions and low-rate display text may allocate.
     *
     * @param gamepad1 The primary gamepad telemetry state.
     * @param gamepad2 The secondary gamepad telemetry state.
     */
    @kotlin.jvm.JvmOverloads
    fun update(
        gamepad1: com.areslib.telemetry.GamepadState? = null,
        gamepad2: com.areslib.telemetry.GamepadState? = null
    ) {
        check(!closed) { "AresRobot is closed" }
        // Check both latches before touching any actuator. A failed instance can only recover
        // through normal OpMode reconstruction.
        val priorFailure = fatalUpdateFailure
        if (priorFailure != null) {
            safeAfterFailure(priorFailure)
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
            preserveInterrupt(t)
            safeAfterFailure(t)
            throw t
        }
    }
    private fun safeAfterFailure(primary: Throwable) {
        var subsystemFailure: Throwable? = null
        var hardwareFailure: Throwable? = null
        try { base.safeAll() } catch (failure: Throwable) { subsystemFailure = failure }
        try { base.safeHardware() } catch (failure: Throwable) { hardwareFailure = failure }
        subsystemFailure?.let { retainSuppressed(primary, it) }
        hardwareFailure?.let { retainSuppressed(primary, it) }
    }

    private fun requireOperational() {
        check(!closed) { "AresRobot is closed" }
        fatalUpdateFailure?.let { throw it }
    }

    /** Commands shaped, alliance-aware field-relative translation and CCW-positive rotation. */
    fun driveFieldCentric(x: Double, y: Double, rotation: Double) {
        requireOperational()
        driveController.driveFieldCentric(x, y, rotation)
    }

    /** Commands field-relative drive from a cached gamepad snapshot. */
    fun driveWithGamepad(driver: com.areslib.telemetry.AresGamepad, useHeadingLock: Boolean = true) {
        requireOperational()
        driveController.driveWithGamepad(driver, useHeadingLock)
    }

    /** Resets localization to the configured origin for the current Redux alliance. */
    fun resetPoseForAlliance() {
        requireOperational()
        driveController.resetPoseForAlliance()
    }

    /** Toggles Redux alliance; the caller decides whether to reset pose. */
    fun toggleAlliance() {
        requireOperational()
        superstructureController.toggleAlliance()
    }

    /** Enables the shared calibration receiver only for a dedicated tuning OpMode. */
    fun enableCalibrationMode() {
        requireOperational()
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

    /** Disarms calibration, then delegates once to the shared owner of hardware, subsystems and services. */
    fun close() {
        if (closed) return
        closed = true
        var firstFailure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val primary = firstFailure
                preserveInterrupt(failure)
                if (primary == null) firstFailure = failure
                else retainSuppressed(primary, failure)
            }
        }
        attempt(::disableCalibrationMode)
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
 * Validates asset bytes without an Android context and records the latest failure diagnostic.
 * The failure taxonomy covers non-FTC geometry, missing or duplicate AprilTags, and non-finite tags.
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
            com.areslib.state.ValidatedRobotFieldLoader.load(
                bytes,
                requiredFieldType = com.areslib.state.FieldType.FTC,
                requireAprilTags = true,
            )
        }.getOrElse { failure ->
            error = failure.message ?: failure::class.java.simpleName
            return null
        }
        val tags = config.aprilTagPoseMap()
        return FtcFieldContract(config, tags)
    }
}

internal fun loadFtcFieldContract(bytes: ByteArray): FtcFieldContract? = FtcFieldContractLoader.load(bytes)
