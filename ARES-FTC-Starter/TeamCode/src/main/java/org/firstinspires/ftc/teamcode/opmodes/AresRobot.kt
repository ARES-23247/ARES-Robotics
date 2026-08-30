// ARES OWNERSHIP: GENERATED STARTER
// Generic FTC composition root. Canonical .ares documents own robot configuration.
package org.firstinspires.ftc.teamcode.opmodes

import com.areslib.action.RobotAction
import com.areslib.ftc.FtcBaseRobot
import com.areslib.ftc.FtcMecanumRobot
import com.areslib.hardware.HardwareRegistry
import com.areslib.pathing.NamedCommands
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldManager
import com.areslib.state.RobotFieldValidator
import com.areslib.state.aprilTagPoseMap
import com.areslib.subsystem.Subsystem
import com.areslib.tuning.TuningApplyContext
import com.areslib.tuning.TuningManager
import com.areslib.tuning.TuningValue
import com.areslib.tuning.TypedTuningConsumer
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.HardwareMap
import org.firstinspires.ftc.robotcore.external.Telemetry
import org.firstinspires.ftc.teamcode.dsl.FtcAutoCapabilities
import org.firstinspires.ftc.teamcode.config.AresRuntimePolicy
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresFtcMecanumRuntimeConfig
import org.firstinspires.ftc.teamcode.generated.drivebase.GeneratedAresTuningConfig
import org.firstinspires.ftc.teamcode.subsystems.GeneratedSubsystemRegistry
import org.firstinspires.ftc.teamcode.subsystems.superstructure.GeneratedSuperstructureRegistry
import java.nio.file.Paths

internal fun installGeneratedSubsystems(
    hardwareMap: HardwareMap,
    hardwareRegistry: HardwareRegistry,
    register: (Subsystem) -> Unit,
    createAll: (HardwareMap, HardwareRegistry) -> List<Subsystem> = GeneratedSubsystemRegistry::createAll,
): List<Subsystem> = createAll(hardwareMap, hardwareRegistry).also { created -> created.forEach(register) }

internal fun installGeneratedSuperstructures(
    register: (Subsystem) -> Unit,
    createAll: () -> List<Subsystem> = GeneratedSuperstructureRegistry::createAll,
): List<Subsystem> = createAll().also { created -> created.forEach(register) }

/**
 * Generic zero-code FTC composition root.
 *
 * Hardware reads remain cached and occur once per shared update. Generated mechanisms consume the
 * same frame and power scale. An escaped exception latches this instance and makes neutral output
 * final; recovery requires normal OpMode reconstruction.
 */
class AresRobot(
    val hardwareMap: HardwareMap,
    val localTelemetry: Telemetry? = null,
) {
    val base: FtcMecanumRobot = GeneratedAresFtcMecanumRuntimeConfig.createRobot(
        hardwareMap,
        localTelemetry,
        limelightProxyEnabled = AresRuntimePolicy.options.limelightProxyEnabled,
    )
    private val typedTuningRuntime = GeneratedAresTuningConfig.createRuntime()
    private val tuningConsumers = ArrayList<TypedTuningConsumer>()
    private var fatalFrameFailure: Throwable? = null
    private var closed = false

    /** True when the checked-in canonical field document was decoded and validated. */
    var hasCanonicalFieldContract: Boolean = false
        private set

    val fatalUpdateFailure: Throwable?
        get() = fatalFrameFailure ?: base.fatalUpdateFailure

    init {
        val projectRoot = if (FtcBaseRobot.isAndroid) Paths.get("/sdcard/FIRST")
            else Paths.get("").toAbsolutePath().normalize()
        val tuningManager = TuningManager(
            runtime = typedTuningRuntime,
            telemetry = base.telemetryManager.dataLoggingTelemetry,
            contextProvider = {
                TuningApplyContext(
                    sessionArmed = base.isCalibrationModeArmed,
                    robotDisabled = false,
                    calibrationParameterUids = emptySet(),
                    outputsNeutralAndInhibited = base.isCalibrationNeutralOutputHoldActive,
                )
            },
            onApplied = { parameterUid, value ->
                if (GeneratedAresFtcMecanumRuntimeConfig.supportsRuntimeParameter(parameterUid)) {
                    base.store.dispatch(
                        RobotAction.UpdateTuningState(
                            GeneratedAresFtcMecanumRuntimeConfig.withRuntimeValues(
                                base.store.state.tuning,
                                typedTuningRuntime,
                            )
                        )
                    )
                    true
                } else applySubsystemTuning(parameterUid, value)
            },
            isConsumerSupported = ::supportsRuntimeParameter,
            localProjectRoot = projectRoot,
            localOverlayFile = projectRoot.resolve(".ares/local/tuning/runtime.arestuning"),
        )
        base.tuningManager = tuningManager

        val fieldBytes = runCatching {
            hardwareMap.appContext.assets.open("paths/field.json").use { it.readBytes() }
        }.getOrNull()
        val fieldConfig = fieldBytes?.let(::loadStarterFtcFieldContract)
        if (fieldConfig != null) {
            RobotFieldManager.setActiveConfig(fieldConfig)
            com.areslib.math.estimation.PoseEstimator.activeTags = fieldConfig.aprilTagPoseMap()
            hasCanonicalFieldContract = true
        } else {
            // Manual driving remains available, but localization must never inherit a stale tag map.
            RobotFieldManager.setActiveConfig(emptyStarterField())
            com.areslib.math.estimation.PoseEstimator.activeTags = emptyMap()
            addTelemetry(
                "Field",
                "Canonical field unavailable; AprilTag localization disabled: ${StarterFtcFieldContractLoader.error}",
            )
        }

        NamedCommands.clear()
        try {
            installGeneratedSubsystems(hardwareMap, base.hardwareRegistry, base::registerSubsystem).forEach { subsystem ->
                if (subsystem is TypedTuningConsumer) {
                    tuningConsumers += subsystem
                    applyCanonicalValues(subsystem)
                }
            }
            installGeneratedSuperstructures(base::registerSubsystem)
            FtcAutoCapabilities.registerDriveRecovery(base::recoverDriveOutputWithNeutral)
            tuningManager.publishMetadataAndValues()
        } catch (failure: Throwable) {
            runCatching { base.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun addTelemetry(key: String, value: Any) {
        localTelemetry?.addData(key, value)
    }

    private fun supportsRuntimeParameter(parameterUid: String): Boolean {
        if (GeneratedAresFtcMecanumRuntimeConfig.supportsRuntimeParameter(parameterUid)) return true
        var matches = 0
        for (index in tuningConsumers.indices) {
            if (tuningConsumers[index].supportsTuningParameter(parameterUid)) matches += 1
        }
        return matches == 1
    }

    private fun applySubsystemTuning(parameterUid: String, value: TuningValue): Boolean {
        var matchIndex = -1
        for (index in tuningConsumers.indices) {
            if (!tuningConsumers[index].supportsTuningParameter(parameterUid)) continue
            if (matchIndex >= 0) return false
            matchIndex = index
        }
        return matchIndex >= 0 && tuningConsumers[matchIndex].applyTuningParameter(parameterUid, value)
    }

    private fun applyCanonicalValues(consumer: TypedTuningConsumer) {
        typedTuningRuntime.metadata.declarations.forEach { declaration ->
            if (consumer.supportsTuningParameter(declaration.uid)) {
                check(consumer.applyTuningParameter(declaration.uid, requireNotNull(typedTuningRuntime.value(declaration.uid)))) {
                    "Generated subsystem rejected canonical tuning parameter '${declaration.uid}'"
                }
            }
        }
    }

    @JvmOverloads
    fun update(
        gamepad1: com.areslib.telemetry.GamepadState? = null,
        gamepad2: com.areslib.telemetry.GamepadState? = null,
    ) {
        fatalUpdateFailure?.let { failure ->
            runCatching { base.safeAll() }
            runCatching { base.safeHardware() }
            throw failure
        }
        try {
            base.update(gamepad1, gamepad2)
            base.readAllSensors(RobotClock.currentTimeMillis())
            base.writeAllOutputs(base.powerManager.powerScale)
        } catch (failure: Throwable) {
            fatalFrameFailure = failure
            runCatching { base.safeAll() }
            runCatching { base.safeHardware() }
            throw failure
        }
    }

    fun close() {
        if (closed) return
        closed = true
        var firstFailure: Throwable? = null
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                val prior = firstFailure
                if (prior == null) firstFailure = failure
                else if (prior !== failure) prior.addSuppressed(failure)
            }
        }
        attempt { base.safeAll() }
        attempt { base.safeHardware() }
        attempt { base.close() }
        NamedCommands.clear()
        firstFailure?.let { throw it }
    }
}

internal fun emptyStarterField(): RobotFieldConfig = RobotFieldConfig(
    id = "unavailable-starter-ftc-field",
    name = "Unavailable starter FTC field",
    fieldType = FieldType.FTC,
    widthMeters = 3.6576,
    heightMeters = 3.6576,
    apriltags = emptyList(),
)

/** Pure, unit-testable loader for the starter's canonical Field Studio document. */
internal object StarterFtcFieldContractLoader {
    var error: String? = null
        private set

    fun load(bytes: ByteArray): RobotFieldConfig? {
        error = null
        val config = runCatching { RobotFieldDocument.decode(bytes.decodeToString()) }
            .getOrElse { failure ->
                error = failure.message ?: failure::class.java.simpleName
                return null
            }
        val issues = RobotFieldValidator.validate(
            config = config,
            requiredFieldType = FieldType.FTC,
            requireAprilTags = false,
        )
        if (issues.isNotEmpty()) {
            error = issues.first().message
            return null
        }
        return config
    }
}

internal fun loadStarterFtcFieldContract(bytes: ByteArray): RobotFieldConfig? =
    StarterFtcFieldContractLoader.load(bytes)
