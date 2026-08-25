package org.aresfirst.starter.frc

import com.areslib.action.RobotAction
import com.areslib.Store
import com.areslib.state.Alliance
import com.areslib.state.RobotFieldManager
import com.areslib.subsystem.Subsystem
import com.areslib.telemetry.ARESNetworkStatePublisher
import com.areslib.util.RobotClock
import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.Filesystem
import edu.wpi.first.wpilibj.RobotBase
import edu.wpi.first.wpilibj.TimedRobot
import org.aresfirst.starter.frc.generated.subsystems.GeneratedSubsystemRegistry
import org.aresfirst.starter.frc.generated.subsystems.superstructure.GeneratedSuperstructureRegistry
import org.aresfirst.starter.frc.generatedruntime.FrcGeneratedControlsRuntime
import kotlin.io.path.readBytes

/** True in simulation or after the project explicitly installs reviewed physical adapters. */
internal fun physicalOutputsPermitted(isReal: Boolean, adapterInstalled: Boolean): Boolean =
    !isReal || adapterInstalled

/** Generic, simulation-first FRC composition root generated projects can extend without hand code. */
class AresStarterRobot : TimedRobot() {
    private lateinit var robot: StarterRobotRuntime
    private lateinit var generatedControls: FrcGeneratedControlsRuntime
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
            RobotFieldManager.setActiveConfig(field.config)
            simulation.configureField(field.config)
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
                    RobotFieldManager.setActiveConfig(updatedField.config)
                    simulation.configureField(updatedField.config)
                }
            )
        } else null
        generatedControls = studioSimulationBridge?.let { bridge ->
            FrcGeneratedControlsRuntime(
                stateProvider = { robot.store.state },
                dispatch = robot.store::dispatch,
                capabilities = generatedCapabilities,
                portSampler = bridge,
            )
        } ?: FrcGeneratedControlsRuntime(
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
    register: (Subsystem) -> Unit,
    createAll: (Boolean) -> List<Subsystem> = GeneratedSubsystemRegistry::createAll,
): List<Subsystem> = createAll(usePhysicalAdapters).also { created -> created.forEach(register) }

internal fun installGeneratedSuperstructures(
    register: (Subsystem) -> Unit,
    createAll: () -> List<Subsystem> = GeneratedSuperstructureRegistry::createAll,
): List<Subsystem> = createAll().also { created -> created.forEach(register) }

/** Minimal vendor-neutral Redux/subsystem host used by the generic starter. */
internal class StarterRobotRuntime {
    val store = Store()
    val telemetry = StarterFrcTelemetry()
    private val publisher = ARESNetworkStatePublisher(telemetry)
    private val subsystems = ArrayList<Subsystem>()
    private var lastUpdateMs = 0L
    private var closed = false

    fun registerSubsystem(subsystem: Subsystem) {
        check(!closed) { "Cannot register a subsystem after the robot runtime closes" }
        subsystems += subsystem
    }

    fun publishHardwareTopology(robotId: String) {
        publisher.publishTopology(com.areslib.hardware.HardwareRegistry.getTopologyJson(robotId))
    }

    fun update() {
        val now = RobotClock.currentTimeMillis()
        val dt = if (lastUpdateMs == 0L) 0.02 else ((now - lastUpdateMs) / 1000.0).coerceIn(0.0, 0.05)
        lastUpdateMs = now
        com.areslib.hardware.HardwareRegistry.refreshAll()
        for (index in subsystems.indices) subsystems[index].readSensors(store, now)
        val outputScale = if (DriverStation.isEnabled()) 1.0 else 0.0
        for (index in subsystems.indices) subsystems[index].writeOutputs(store.state, outputScale)
        publisher.publish(store.state, dtSeconds = dt, flush = false)
        telemetry.putBoolean("ARES/Starter/PhysicalHardwareReady", false)
        telemetry.update()
    }

    fun safeHardware() {
        for (index in subsystems.indices) {
            runCatching { subsystems[index].writeOutputs(store.state, 0.0) }
        }
        com.areslib.hardware.HardwareRegistry.safeAll()
    }

    fun close() {
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
        attempt(::safeHardware)
        for (index in subsystems.indices.reversed()) attempt(subsystems[index]::close)
        attempt { com.areslib.hardware.HardwareRegistry.closeAll() }
        attempt(telemetry::close)
        failure?.let { throw it }
    }
}
