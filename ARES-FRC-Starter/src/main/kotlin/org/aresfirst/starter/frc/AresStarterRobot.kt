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
class AresStarterRobot : TimedRobot() {
    private lateinit var robot: StarterRobotRuntime
    private lateinit var generatedControls: FrcGeneratedProjectControlsRuntime<GeneratedAresProjectCapabilities>
    private lateinit var generatedCapabilities: StarterGeneratedCapabilities
    private lateinit var autonomousRuntime: StarterFrcAutonomousRuntime
    private var studioSimulationBridge: FrcStudioSimulationBridge? = null
    private val simulation = StarterDriveSimulation()
    private var lastSimulationSeconds = 0.0
    private var lastAlliance: Alliance? = null
    private var closed = false

    /** Physical output stays blocked until a reviewed adapter is generated for the selected hardware. */
    private val physicalAdapterInstalled = false

    override fun robotInit() {
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

        robot = StarterRobotRuntime()
        try {
            installGeneratedSubsystems(
                usePhysicalAdapters = physicalOutputsPermitted(RobotBase.isReal(), physicalAdapterInstalled),
                hardwareRegistry = robot.hardwareRegistry,
                register = robot::registerSubsystem,
            )
            installGeneratedSuperstructures(robot::registerSubsystem)
        } catch (failure: Throwable) {
            runCatching { robot.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }

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

    override fun robotPeriodic() {
        applyAlliance()
        robot.update()
    }

    override fun teleopInit() {
        autonomousRuntime.stop("Teleop initialized")
    }

    override fun teleopPeriodic() {
        if (physicalOutputsPermitted(RobotBase.isReal(), physicalAdapterInstalled)) {
            generatedControls.update()
        } else {
            robot.safeHardware()
        }
    }

    override fun autonomousInit() {
        autonomousRuntime.autonomousInit()
    }

    override fun autonomousPeriodic() {
        autonomousRuntime.autonomousPeriodic()
    }

    override fun disabledInit() {
        autonomousRuntime.stop("Robot disabled")
    }

    override fun testInit() {
        autonomousRuntime.stop("Test initialized")
    }

    override fun simulationInit() {
        lastSimulationSeconds = RobotClock.currentTimeMillis() / 1000.0
    }

    override fun simulationPeriodic() {
        studioSimulationBridge?.update()
        val nowMs = RobotClock.currentTimeMillis()
        val nowSeconds = nowMs / 1000.0
        val dt = (nowSeconds - lastSimulationSeconds).coerceIn(0.0, 0.05)
        lastSimulationSeconds = nowSeconds
        robot.store.dispatch(simulation.step(robot.store.state, dt, nowMs))
        robot.telemetry.putNumber("ARES/TruePose/0", simulation.xMeters)
        robot.telemetry.putNumber("ARES/TruePose/1", simulation.yMeters)
        robot.telemetry.putNumber("ARES/TruePose/2", simulation.headingRadians)
        robot.telemetry.putBoolean("ARES/Starter/PhysicalHardwareReady", physicalAdapterInstalled)
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
                val prior = failure
                if (prior == null) failure = error else prior.addSuppressed(error)
            }
        }
        if (::autonomousRuntime.isInitialized) attempt { autonomousRuntime.stop("Robot closing") }
        else if (::generatedControls.isInitialized) attempt { generatedControls.cancelAll("Robot closing") }
        studioSimulationBridge?.let { bridge -> attempt(bridge::close) }
        if (::robot.isInitialized) attempt { robot.close() }
        attempt { super.close() }
        failure?.let { throw it }
    }
}

internal fun installGeneratedSubsystems(
    usePhysicalAdapters: Boolean,
    hardwareRegistry: HardwareRegistry,
    register: (Subsystem) -> Unit,
    createAll: (Boolean, HardwareRegistry) -> List<Subsystem> = GeneratedSubsystemRegistry::createAll,
): List<Subsystem> = createAll(usePhysicalAdapters, hardwareRegistry).also { created -> created.forEach(register) }

internal fun installGeneratedSuperstructures(
    register: (Subsystem) -> Unit,
    createAll: () -> List<Subsystem> = GeneratedSuperstructureRegistry::createAll,
): List<Subsystem> = createAll().also { created -> created.forEach(register) }

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
        if (tuningUids.supports(parameterUid)) {
            store.dispatch(
                RobotAction.UpdateTuningState(
                    withStarterRuntimeTuning(store.state.tuning, tuningRuntime, tuningUids),
                ),
            )
            return true
        }
        var matchIndex = -1
        for (index in subsystems.indices) {
            val consumer = subsystems[index] as? TypedTuningConsumer ?: continue
            if (!consumer.supportsTuningParameter(parameterUid)) continue
            if (matchIndex >= 0) return false
            matchIndex = index
        }
        if (matchIndex < 0) return false
        return (subsystems[matchIndex] as TypedTuningConsumer).applyTuningParameter(parameterUid, value)
    }

    private fun supportsRuntimeParameter(parameterUid: String): Boolean {
        if (tuningUids.supports(parameterUid)) return true
        var matches = 0
        for (index in subsystems.indices) {
            val consumer = subsystems[index] as? TypedTuningConsumer ?: continue
            if (consumer.supportsTuningParameter(parameterUid)) matches += 1
        }
        return matches == 1
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
