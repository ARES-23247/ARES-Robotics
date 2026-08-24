package org.aresfirst.starter.frc.generatedruntime

import com.areslib.action.RobotAction
import org.aresfirst.starter.frc.generated.GeneratedAresProject
import org.aresfirst.starter.frc.generated.GeneratedAresProjectCapabilities
import org.aresfirst.starter.frc.generated.GeneratedAresProjectControlTaskSink
import com.areslib.input.ControllerBindingRuntime
import com.areslib.input.InputFrame
import com.areslib.routine.RoutineManager
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskExecutor
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import edu.wpi.first.wpilibj.GenericHID

/** Samples one configured Driver Station port into caller-owned storage. */
internal interface FrcControllerPortSampler {
    fun prepare(port: Int)
    fun sampleInto(port: Int, frame: InputFrame, nowNanos: Long)
}

/** Production sampler that owns one reusable WPILib adapter for each active generated port. */
internal class WpilibFrcControllerPortSampler : FrcControllerPortSampler {
    private val devices = arrayOfNulls<GenericHID>(MAX_FRC_CONTROLLER_PORTS)

    override fun prepare(port: Int) {
        require(port in devices.indices) { "FRC Driver Station port $port is outside 0..${devices.lastIndex}" }
        if (devices[port] == null) devices[port] = GenericHID(port)
    }

    override fun sampleInto(port: Int, frame: InputFrame, nowNanos: Long) {
        val hid = requireNotNull(devices[port]) { "FRC Driver Station port $port was not prepared" }
        try {
            if (!edu.wpi.first.wpilibj.DriverStation.isJoystickConnected(port)) {
                frame.beginSample(connected = false, sampleTimeNanos = nowNanos)
                return
            }
            val axisCount = edu.wpi.first.wpilibj.DriverStation.getStickAxisCount(port)
            val buttonCount = edu.wpi.first.wpilibj.DriverStation.getStickButtonCount(port)
            if (axisCount !in 0..frame.axisCapacity || buttonCount !in 0..120) {
                frame.beginSample(connected = false, sampleTimeNanos = nowNanos)
                return
            }
            frame.beginSample(
                connected = true,
                reportedAxisCount = axisCount,
                reportedButtonCount = 124,
                sampleTimeNanos = nowNanos,
            )
            var axis = 0
            while (axis < axisCount) {
                val value = hid.getRawAxis(axis)
                frame.setAxis(axis, if (value.isFinite() && value in -1.0..1.0) value else 0.0)
                axis++
            }
            var wpilibButton = 1
            while (wpilibButton <= buttonCount) {
                frame.setButton(wpilibButton - 1, hid.getRawButton(wpilibButton))
                wpilibButton++
            }
            val pov = if (edu.wpi.first.wpilibj.DriverStation.getStickPOVCount(port) > 0) hid.getPOV(0) else -1
            val validPov = pov in 0..359
            frame.setButton(120, validPov && (pov >= 315 || pov <= 45))
            frame.setButton(121, validPov && pov in 45..135)
            frame.setButton(122, validPov && pov in 135..225)
            frame.setButton(123, validPov && pov in 225..315)
        } catch (_: RuntimeException) {
            frame.beginSample(connected = false, sampleTimeNanos = nowNanos)
        }
    }
}

/**
 * FRC host for the allocation-free controller bindings emitted from checked-in `.arescontrols`.
 *
 * The hard-coded season controller runs first. This host runs second, making an explicitly authored
 * GUI binding authoritative for that frame. It is invoked only by `teleopPeriodic`; every other
 * mode transition calls [cancelAll], which releases held bindings and dispatches task cleanup.
 */
internal class FrcGeneratedControlsRuntime(
    private val stateProvider: () -> RobotState,
    private val dispatch: (RobotAction) -> Unit,
    private val capabilities: GeneratedAresProjectCapabilities,
    private val portSampler: FrcControllerPortSampler = WpilibFrcControllerPortSampler(),
    /**
     * Scheme stick drive is suppressed while this gate returns false, letting hand-authored
     * assists (X-lock, speaker/shuttle aiming) own those frames instead of being overwritten
     * by the every-frame generated emit.
     */
    private val driveEmissionGate: () -> Boolean = { true },
) : GeneratedAresProjectControlTaskSink {
    private val directTaskExecutor = TaskExecutor()
    private val routineManager = RoutineManager(
        bindings = GeneratedAresProject.runtimeBindings(capabilities),
        stateProvider = stateProvider,
        dispatch = dispatch,
    ).also { manager -> manager.replaceDocuments(GeneratedAresProject.routines.values) }
    private val inputFrames = Array(MAX_FRC_CONTROLLER_PORTS) { InputFrame() }
    private val controllerRuntimes = arrayOfNulls<ControllerBindingRuntime>(MAX_FRC_CONTROLLER_PORTS)

    init {
        val generated = GeneratedAresProject.createControllerRuntimes(
            schemeId = GeneratedAresProject.DEFAULT_CONTROL_SCHEME_ID,
            registry = capabilities,
            routineManager = routineManager,
            taskSink = this,
        )
        for ((port, runtime) in generated) {
            require(port in controllerRuntimes.indices) {
                "Generated FRC controller port $port is outside 0..${controllerRuntimes.lastIndex}"
            }
            check(controllerRuntimes[port] == null) { "Generated FRC controller port $port is duplicated" }
            portSampler.prepare(port)
            controllerRuntimes[port] = runtime
        }
    }

    /** Samples active ports and advances generated tasks once during an enabled TeleOp frame. */
    fun update() {
        val nowNanos = RobotClock.nanoTime()
        var port = 0
        while (port < controllerRuntimes.size) {
            val runtime = controllerRuntimes[port]
            if (runtime != null) {
                val frame = inputFrames[port]
                portSampler.sampleInto(port, frame, nowNanos)
                runtime.update(frame, nowNanos)
            }
            port++
        }
        if (driveEmissionGate()) GeneratedAresProject.emitDriveCommand(capabilities)

        if (directTaskExecutor.size > 0) {
            val actions = directTaskExecutor.update(stateProvider(), RobotClock.currentTimeMillis())
            for (index in actions.indices) dispatch(actions[index])
        }
        if (routineManager.activeCount > 0 || routineManager.queuedCount > 0) routineManager.update()
    }

    override fun submit(bindingId: String, task: Task) {
        require(bindingId.isNotBlank()) { "Generated FRC binding ID must not be blank" }
        directTaskExecutor.addTask(task)
    }

    /** Releases every binding and task. No generated input survives a mode transition. */
    fun cancelAll(reason: String) {
        var port = 0
        while (port < controllerRuntimes.size) {
            controllerRuntimes[port]?.cancel()
            port++
        }
        val actions = directTaskExecutor.cancelAll(stateProvider())
        for (index in actions.indices) dispatch(actions[index])
        routineManager.cancelAll(reason)
    }

    val controlsSource: String
        get() = GeneratedAresProject.DEFAULT_CONTROL_SCHEME_ID?.let { scheme ->
            "generated:$scheme:${GeneratedAresProject.CONTENT_SHA256}"
        } ?: "hardcoded-only"

    internal val activeControllerPortCount: Int
        get() {
            var count = 0
            var port = 0
            while (port < controllerRuntimes.size) {
                if (controllerRuntimes[port] != null) count++
                port++
            }
            return count
        }
}

private const val MAX_FRC_CONTROLLER_PORTS = 6
