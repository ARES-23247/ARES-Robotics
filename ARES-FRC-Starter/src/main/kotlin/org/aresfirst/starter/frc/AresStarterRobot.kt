package org.aresfirst.starter.frc

import com.areslib.action.RobotAction
import com.areslib.Store
import com.areslib.state.Alliance
import com.areslib.state.RobotState
import com.areslib.state.RobotFieldManager
import com.areslib.state.TuningState
import com.areslib.control.tuning.PIDFCoefficients
import com.areslib.subsystem.Subsystem
import com.areslib.hardware.HardwareRegistry
import com.areslib.telemetry.ARESNetworkStatePublisher
import com.areslib.telemetry.ITelemetry
import com.areslib.tuning.TuningApplyContext
import com.areslib.tuning.TuningManager
import com.areslib.tuning.TuningValue
import com.areslib.tuning.TypedTuningConsumer
import com.areslib.tuning.TypedTuningRuntime
import com.areslib.util.RobotClock
import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.Filesystem
import edu.wpi.first.wpilibj.RobotBase
import edu.wpi.first.wpilibj.TimedRobot
import com.areslib.frc.runtime.FrcGeneratedProjectControlsRuntime
import org.aresfirst.starter.frc.generated.GeneratedAresProject
import org.aresfirst.starter.frc.generated.GeneratedAresProjectCapabilities
import org.aresfirst.starter.frc.generated.drivebase.GeneratedAresTuningConfig
import org.aresfirst.starter.frc.generated.subsystems.GeneratedSubsystemRegistry
import org.aresfirst.starter.frc.generated.subsystems.superstructure.GeneratedSuperstructureRegistry
import kotlin.io.path.readBytes

/** True in simulation or after the project explicitly installs reviewed physical adapters. */
internal fun physicalOutputsPermitted(isReal: Boolean, adapterInstalled: Boolean): Boolean =
    !isReal || adapterInstalled

/** Generic, simulation-first FRC composition root generated projects can extend without hand code. */
class AresStarterRobot internal constructor(
    private val runtimeFactory: () -> StarterRobotRuntime,
) : TimedRobot() {
    constructor() : this({ StarterRobotRuntime() })
    private lateinit var robot: StarterRobotRuntime
    private lateinit var generatedControls: FrcGeneratedProjectControlsRuntime<GeneratedAresProjectCapabilities>
    private lateinit var generatedCapabilities: StarterGeneratedCapabilities
    private lateinit var autonomousRuntime: StarterFrcAutonomousRuntime
    private var studioSimulationBridge: FrcStudioSimulationBridge? = null
    private val simulation = StarterDriveSimulation()
    private var lastSimulationMs = 0L
    private var hasSimulationTimestamp = false
    private val neutralDrive = RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0, isFieldCentric = true)
    private var lastAlliance: Alliance? = null
    private var initializationStarted = false
    private var initialized = false
    private var closed = false

    /** Physical output stays blocked until a reviewed adapter is generated for the selected hardware. */
    private val physicalAdapterInstalled = false

    override fun robotInit() {
        check(!closed && !initializationStarted) { "FRC starter robot cannot initialize twice or after close" }
        initializationStarted = true
        try {
            initialize()
            initialized = true
        } catch (failure: Throwable) {
            closeAfterFailure(failure)
        }
    }

    private fun initialize() {
        val fieldPath = Filesystem.getDeployDirectory().toPath().resolve("paths/field.json")
        val field = runCatching { loadStarterFieldContract(fieldPath.readBytes()) }.getOrNull()
        if (field != null) {
            applyStarterSimulationField(simulation, field)
        } else {
            RobotFieldManager.setActiveConfig(unavailableFrcField())
            DriverStation.reportError(
                "ARES: canonical field unavailable; AprilTag localization disabled: " +
                    (StarterFieldContractLoader.error ?: "field file missing"),
                false,
            )
        }

        robot = runtimeFactory()
        installGeneratedSubsystems(
            usePhysicalAdapters = physicalOutputsPermitted(RobotBase.isReal(), physicalAdapterInstalled),
            hardwareRegistry = robot.hardwareRegistry,
            register = robot::registerSubsystems,
        )
        installGeneratedSuperstructures(robot::registerSubsystems)

        generatedCapabilities = StarterGeneratedCapabilities(
            robot = robot,
            drivePermitted = physicalOutputsPermitted(RobotBase.isReal(), physicalAdapterInstalled),
        )
        studioSimulationBridge = if (RobotBase.isSimulation()) {
            FrcStudioSimulationBridge(
                onFieldApplied = { updatedField ->
                    applyStarterSimulationField(simulation, updatedField)
                }
            )
        } else null
        generatedControls = studioSimulationBridge?.let { bridge ->
            FrcGeneratedProjectControlsRuntime(
                definition = GeneratedAresProject.runtimeDefinition,
                stateProvider = { robot.store.state },
                dispatch = robot.store::dispatch,
                capabilities = generatedCapabilities,
                portSampler = bridge,
            )
        } ?: FrcGeneratedProjectControlsRuntime(
            definition = GeneratedAresProject.runtimeDefinition,
            stateProvider = { robot.store.state },
            dispatch = robot.store::dispatch,
            capabilities = generatedCapabilities,
        )
        autonomousRuntime = StarterFrcAutonomousRuntime(
            robot = robot,
            simulation = simulation,
            generatedControls = generatedControls,
            capabilities = generatedCapabilities,
            isSimulation = RobotBase.isSimulation(),
        )
        autonomousRuntime.publishCatalog()
        robot.publishHardwareTopology("ARES-FRC-Starter")
        applyAlliance()

        if (RobotBase.isReal()) {
            DriverStation.reportError(
                "ARES Hardware Review required: this generic starter has no physical drivetrain adapter. " +
                    "Simulation remains available; configure hardware in Robot Studio before deployment.",
                false,
            )
        }
    }

    override fun robotPeriodic() = runRobotCallback {
        applyAlliance()
        if (DriverStation.isDisabled()) clearDriveIntent()
        robot.update()
    }

    override fun teleopInit() = runRobotCallback {
        clearDriveIntent()
        autonomousRuntime.stop("Teleop initialized")
    }

    override fun teleopPeriodic() = runRobotCallback {
        if (physicalOutputsPermitted(RobotBase.isReal(), physicalAdapterInstalled)) {
            generatedControls.update()
        } else {
            robot.safeHardware()
        }
    }

    override fun autonomousInit() = runRobotCallback {
        clearDriveIntent()
        autonomousRuntime.autonomousInit()
    }

    override fun autonomousPeriodic() = runRobotCallback {
        autonomousRuntime.autonomousPeriodic()
    }

    override fun disabledInit() = runRobotCallback {
        clearDriveIntent()
        autonomousRuntime.stop("Robot disabled")
    }

    override fun testInit() = runRobotCallback {
        clearDriveIntent()
        autonomousRuntime.stop("Test initialized")
    }

    override fun simulationInit() = runRobotCallback {
        lastSimulationMs = RobotClock.currentTimeMillis()
        hasSimulationTimestamp = true
    }

    override fun simulationPeriodic() = runRobotCallback {
        studioSimulationBridge?.update()
        // Bridge lease/mode changes take effect in this tick, before the next mode callback.
        if (DriverStation.isDisabled()) clearDriveIntent()
        val nowMs = RobotClock.currentTimeMillis()
        val elapsedMs = nowMs - lastSimulationMs
        val dt = if (hasSimulationTimestamp && nowMs >= lastSimulationMs && elapsedMs > 0L) {
            elapsedMs.coerceAtMost(50L) / 1000.0
        } else 0.0
        lastSimulationMs = nowMs
        hasSimulationTimestamp = true
        robot.store.dispatch(simulation.step(robot.store.state, dt, nowMs))
        robot.telemetry.putNumber("ARES/TruePose/0", simulation.xMeters)
        robot.telemetry.putNumber("ARES/TruePose/1", simulation.yMeters)
        robot.telemetry.putNumber("ARES/TruePose/2", simulation.headingRadians)
        robot.telemetry.putBoolean("ARES/Starter/PhysicalHardwareReady", physicalAdapterInstalled)
    }

    private fun clearDriveIntent() {
        val drive = robot.store.state.drive
        if (drive.xVelocityMetersPerSecond == 0.0 && drive.yVelocityMetersPerSecond == 0.0 &&
            drive.angularVelocityRadiansPerSecond == 0.0) return
        neutralDrive.timestampMs = RobotClock.currentTimeMillis()
        robot.store.dispatch(neutralDrive)
    }

    private inline fun runRobotCallback(block: () -> Unit) {
        check(initialized && !closed) { "FRC starter robot is not running" }
        try { block() } catch (failure: Throwable) { closeAfterFailure(failure) }
    }

    private fun closeAfterFailure(failure: Throwable): Nothing {
        retainStarterFailure(null, failure)
        try { close() } catch (cleanup: Throwable) { retainStarterFailure(failure, cleanup) }
        throw failure
    }

    private fun applyAlliance() {
        val selected = if (DriverStation.getAlliance().orElse(DriverStation.Alliance.Blue) ==
            DriverStation.Alliance.Red
        ) Alliance.RED else Alliance.BLUE
        if (selected != lastAlliance) {
            lastAlliance = selected
            robot.store.dispatch(RobotAction.SetAlliance(selected))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                failure = retainStarterFailure(failure, error)
            }
        }
        if (::autonomousRuntime.isInitialized) attempt { autonomousRuntime.stop("Robot closing") }
        else if (::generatedControls.isInitialized) attempt { generatedControls.cancelAll("Robot closing") }
        if (::robot.isInitialized) attempt { clearDriveIntent() }
        studioSimulationBridge?.let { bridge -> attempt(bridge::close) }
        if (::robot.isInitialized) attempt { robot.close() }
        attempt { super.close() }
        failure?.let { throw it }
    }
}

internal fun installGeneratedSubsystems(
    usePhysicalAdapters: Boolean,
    hardwareRegistry: HardwareRegistry,
    register: (List<Subsystem>) -> Unit,
    createAll: (Boolean, HardwareRegistry) -> List<Subsystem> = GeneratedSubsystemRegistry::createAll,
): List<Subsystem> = createAll(usePhysicalAdapters, hardwareRegistry).also(register)

internal fun installGeneratedSuperstructures(
    register: (List<Subsystem>) -> Unit,
    createAll: () -> List<Subsystem> = GeneratedSuperstructureRegistry::createAll,
): List<Subsystem> = createAll().also(register)

/** Minimal vendor-neutral Redux/subsystem host used by the generic starter.
 * A failed update, registration or neutral output latches this instance until it is replaced.
 */
internal class StarterRobotRuntime(
    val telemetry: ITelemetry = StarterFrcTelemetry(),
    private val tuningContextProvider: () -> TuningApplyContext = {
        TuningApplyContext(
            sessionArmed = RobotBase.isSimulation(),
            robotDisabled = DriverStation.isDisabled(),
        )
    },
    tuningRuntime: TypedTuningRuntime? = null,
) {
    val hardwareRegistry = HardwareRegistry()
    private val tuningRuntime: TypedTuningRuntime
    private val tuningUids: StarterFrcRuntimeTuningUids
    val store: Store
    private val publisher: ARESNetworkStatePublisher
    // TuningManager publishes consumer support during construction; initialize its callback state first.
    private val subsystems = ArrayList<Subsystem>()
    private var lastUpdateMs = 0L
    private var hasUpdateTimestamp = false
    private var closed = false
    private var fault: Throwable? = null
    private val tuningManager: TuningManager

    init {
        var manager: TuningManager? = null
        try {
            this.tuningRuntime = tuningRuntime ?: GeneratedAresTuningConfig.createRuntime()
            tuningUids = StarterFrcRuntimeTuningUids.from(this.tuningRuntime)
            store = Store(initialState = RobotState(
                tuning = withStarterRuntimeTuning(TuningState(), this.tuningRuntime, tuningUids),
            ))
            publisher = ARESNetworkStatePublisher(telemetry)
            manager = TuningManager(
                runtime = this.tuningRuntime,
                telemetry = telemetry,
                contextProvider = tuningContextProvider,
                onApplied = ::applyTuningToConsumer,
                isConsumerSupported = ::supportsRuntimeParameter,
            )
            // Explicit empty support lets Studio fail closed before the first periodic tick.
            telemetry.putString("SysId/SupportedMechanisms", "")
            tuningManager = manager
        } catch (failure: Throwable) {
            retainStarterFailure(null, failure)
            try {
                closeStarterResources(listOf(
                    AutoCloseable { hardwareRegistry.closeAll() },
                    AutoCloseable { manager?.close() },
                    AutoCloseable { telemetry.close() },
                ))
            } catch (cleanup: Throwable) {
                retainStarterFailure(failure, cleanup)
            }
            throw failure
        }
    }

    /** Ownership transfers after the open/identity checks, including when canonical setup fails. */
    fun registerSubsystem(subsystem: Subsystem) {
        ensureRunning()
        require(subsystems.none { it === subsystem }) { "Subsystem instance is already registered" }
        subsystems += subsystem
        try {
            if (subsystem is TypedTuningConsumer) {
                applyCanonicalValues(subsystem)
                tuningManager.publishMetadataAndValues()
            }
        } catch (failure: Throwable) { fail(failure) }
    }

    /** Consumes every new batch member, even if registration fails before its ownership transfers. */
    fun registerSubsystems(created: List<Subsystem>) {
        try {
            ensureRunning()
            for (index in created.indices) registerSubsystem(created[index])
        } catch (failure: Throwable) {
            retainStarterFailure(null, failure)
            if (!closed) {
                fault = retainStarterFailure(fault, failure)
                neutralize(fault)
            }
            val unowned = ArrayList<Subsystem>()
            val seen = java.util.IdentityHashMap<Subsystem, Boolean>()
            for (subsystem in created) {
                if (subsystems.none { it === subsystem } && seen.put(subsystem, true) == null) unowned += subsystem
            }
            // Neutral every untransferred owner before attempting any close. The prefix stays ours.
            for (subsystem in unowned) {
                try { subsystem.writeOutputs(store.state, 0.0) }
                catch (cleanup: Throwable) { retainStarterFailure(failure, cleanup) }
            }
            for (index in unowned.indices.reversed()) {
                try { unowned[index].close() }
                catch (cleanup: Throwable) { retainStarterFailure(failure, cleanup) }
            }
            throw failure
        }
    }

    fun publishHardwareTopology(robotId: String) {
        ensureRunning()
        try { publisher.publishTopology(hardwareRegistry.getTopologyJson(robotId)) }
        catch (failure: Throwable) { fail(failure) }
    }

    fun update() {
        ensureRunning()
        try {
            val now = RobotClock.currentTimeMillis()
            val elapsed = now - lastUpdateMs
            // This is measured loop telemetry, not a physics integration interval. Never clip stalls.
            val dt = if (hasUpdateTimestamp && now >= lastUpdateMs && elapsed > 0L) elapsed / 1000.0 else Double.NaN
            lastUpdateMs = now
            hasUpdateTimestamp = true
            hardwareRegistry.refreshAll()
            for (index in subsystems.indices) subsystems[index].readSensors(store, now)
            val outputScale = if (DriverStation.isEnabled()) 1.0 else 0.0
            for (index in subsystems.indices) subsystems[index].writeOutputs(store.state, outputScale)
            tuningManager.update(now)
            publisher.publish(store.state, dtSeconds = dt, flush = false)
            telemetry.putBoolean("ARES/Starter/PhysicalHardwareReady", false)
            telemetry.putString("SysId/SupportedMechanisms", "")
            telemetry.update()
        } catch (failure: Throwable) { fail(failure) }
    }

    /** Test seam for proving transport acknowledgement without starting a WPILib robot loop. */
    internal fun updateTuningForTest(timestampMs: Long) {
        ensureRunning()
        try { tuningManager.update(timestampMs) } catch (failure: Throwable) { fail(failure) }
    }

    private fun applyTuningToConsumer(parameterUid: String, value: TuningValue): Boolean {
        val owner = tuningConsumerIndex(parameterUid)
        if (owner == -1) {
            store.dispatch(
                RobotAction.UpdateTuningState(
                    withStarterRuntimeTuning(store.state.tuning, tuningRuntime, tuningUids),
                ),
            )
            return true
        }
        if (owner < 0) return false
        return (subsystems[owner] as TypedTuningConsumer).applyTuningParameter(parameterUid, value)
    }

    private fun supportsRuntimeParameter(parameterUid: String): Boolean = tuningConsumerIndex(parameterUid) != -2

    /** -1 is the builtin Redux consumer; -2 means missing or ambiguous ownership. */
    private fun tuningConsumerIndex(parameterUid: String): Int {
        var owner = if (tuningUids.supports(parameterUid)) -1 else -2
        for (index in subsystems.indices) {
            val consumer = subsystems[index] as? TypedTuningConsumer ?: continue
            if (!consumer.supportsTuningParameter(parameterUid)) continue
            if (owner != -2) return -2
            owner = index
        }
        return owner
    }

    private fun applyCanonicalValues(consumer: TypedTuningConsumer) {
        tuningRuntime.metadata.declarations.forEach { declaration ->
            if (consumer.supportsTuningParameter(declaration.uid)) {
                check(consumer.applyTuningParameter(declaration.uid, requireNotNull(tuningRuntime.value(declaration.uid)))) {
                    "Generated subsystem rejected canonical tuning parameter '${declaration.uid}'"
                }
            }
        }
    }

    private fun ensureRunning() {
        check(!closed) { "FRC starter runtime is closed" }
        fault?.let { failure ->
            neutralize(failure)
            throw failure
        }
    }

    private fun fail(failure: Throwable): Nothing {
        val primary = retainStarterFailure(fault, failure)
        fault = primary
        neutralize(primary)
        throw primary
    }

    /** Best effort neutral of every mechanism and raw device, retaining observable failures. */
    private fun neutralize(initialFailure: Throwable? = null): Throwable? {
        var failure = initialFailure
        for (index in subsystems.indices) {
            try { subsystems[index].writeOutputs(store.state, 0.0) }
            catch (error: Throwable) { failure = retainStarterFailure(failure, error) }
        }
        try { hardwareRegistry.safeAll() }
        catch (error: Throwable) { failure = retainStarterFailure(failure, error) }
        return failure
    }

    fun safeHardware() {
        if (closed) return
        neutralize()?.let { failure ->
            val primary = retainStarterFailure(fault, failure)
            fault = primary
            throw primary
        }
    }

    fun close() {
        if (closed) return
        closed = true
        var failure = neutralize()
        for (index in subsystems.indices.reversed()) {
            try { subsystems[index].close() }
            catch (error: Throwable) { failure = retainStarterFailure(failure, error) }
        }
        subsystems.clear()
        try { hardwareRegistry.closeAll() }
        catch (error: Throwable) { failure = retainStarterFailure(failure, error) }
        try { tuningManager.close() }
        catch (error: Throwable) { failure = retainStarterFailure(failure, error) }
        try { telemetry.close() }
        catch (error: Throwable) { failure = retainStarterFailure(failure, error) }
        failure?.let { throw it }
    }
}

/** Explicit consumer map: a value is acknowledged only when Redux and the controller can use it. */
internal fun withStarterRuntimeTuning(
    current: TuningState,
    runtime: TypedTuningRuntime,
    uids: StarterFrcRuntimeTuningUids = StarterFrcRuntimeTuningUids.from(runtime),
): TuningState = current.copy(
    drive = current.drive.copy(
        pathTranslationGains = PIDFCoefficients(
            runtime.double(uids.pathTranslationKp),
            runtime.double(uids.pathTranslationKi),
            runtime.double(uids.pathTranslationKd),
        ),
        pathRotationGains = PIDFCoefficients(
            runtime.double(uids.pathRotationKp),
            runtime.double(uids.pathRotationKi),
            runtime.double(uids.pathRotationKd),
        ),
        pathVelocityScale = runtime.double(uids.pathVelocityScale),
        pathAccelerationLimit = runtime.double(uids.pathAccelerationLimit),
    ),
)

/** Stable UID binding for every FRC starter tuning value with a compiled control consumer. */
internal class StarterFrcRuntimeTuningUids private constructor(
    val pathTranslationKp: String,
    val pathTranslationKi: String,
    val pathTranslationKd: String,
    val pathRotationKp: String,
    val pathRotationKi: String,
    val pathRotationKd: String,
    val pathVelocityScale: String,
    val pathAccelerationLimit: String,
) {
    private val supported = setOf(
        pathTranslationKp,
        pathTranslationKi,
        pathTranslationKd,
        pathRotationKp,
        pathRotationKi,
        pathRotationKd,
        pathVelocityScale,
        pathAccelerationLimit,
    )

    fun supports(parameterUid: String): Boolean = parameterUid in supported

    companion object {
        fun from(runtime: TypedTuningRuntime): StarterFrcRuntimeTuningUids {
            val byKey = runtime.metadata.declarations.associate { it.key to it.uid }
            fun uid(key: String): String = requireNotNull(byKey[key]) {
                "FRC starter tuning contract is missing '$key'"
            }
            return StarterFrcRuntimeTuningUids(
                pathTranslationKp = uid("drive.pathTranslationKp"),
                pathTranslationKi = uid("drive.pathTranslationKi"),
                pathTranslationKd = uid("drive.pathTranslationKd"),
                pathRotationKp = uid("drive.pathRotationKp"),
                pathRotationKi = uid("drive.pathRotationKi"),
                pathRotationKd = uid("drive.pathRotationKd"),
                pathVelocityScale = uid("drive.pathVelocityScale"),
                pathAccelerationLimit = uid("drive.pathAccelerationLimit"),
            )
        }
    }
}
