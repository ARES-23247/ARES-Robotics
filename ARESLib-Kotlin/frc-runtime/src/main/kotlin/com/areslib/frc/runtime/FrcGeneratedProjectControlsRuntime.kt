package com.areslib.frc.runtime

import com.areslib.action.RobotAction
import com.areslib.frc.input.FrcInputFrameAdapter
import com.areslib.input.InputFrame
import com.areslib.routine.RoutineRequestResult
import com.areslib.routine.RoutineStartPolicy
import com.areslib.runtime.GeneratedProjectControlRuntime
import com.areslib.runtime.GeneratedProjectDefinition
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import edu.wpi.first.wpilibj.GenericHID

/** Samples one configured FRC Driver Station port into caller-owned storage. */
interface FrcControllerPortSampler {
    fun prepare(port: Int)
    fun sampleInto(port: Int, frame: InputFrame, nowNanos: Long)
}

/** Vendor-neutral WPILib sampler with one reusable adapter per active generated port. */
class WpilibFrcControllerPortSampler : FrcControllerPortSampler {
    private val adapters = arrayOfNulls<FrcInputFrameAdapter>(MAX_FRC_CONTROLLER_PORTS)

    override fun prepare(port: Int) {
        require(port in adapters.indices) { "FRC Driver Station port $port is outside 0..${adapters.lastIndex}" }
        if (adapters[port] == null) adapters[port] = FrcInputFrameAdapter(GenericHID(port))
    }

    override fun sampleInto(port: Int, frame: InputFrame, nowNanos: Long) {
        requireNotNull(adapters[port]) { "FRC Driver Station port $port was not prepared" }
            .sampleInto(frame, nowNanos)
    }
}

/**
 * FRC league host for generated controller bindings and routines.
 *
 * WPILib owns Driver Station sampling. The caller owns TimedRobot/FMS lifecycle, generated
 * capabilities, drive arbitration, hardware IO, and simulation selection.
 */
class FrcGeneratedProjectControlsRuntime<C>(
    definition: GeneratedProjectDefinition<C>,
    stateProvider: () -> RobotState,
    dispatch: (RobotAction) -> Unit,
    capabilities: C,
    private val portSampler: FrcControllerPortSampler = WpilibFrcControllerPortSampler(),
    private val driveEmissionGate: () -> Boolean = { true },
) {
    private val runtime = GeneratedProjectControlRuntime(
        definition = definition,
        stateProvider = stateProvider,
        dispatch = dispatch,
        capabilities = capabilities,
        maximumControllerPorts = MAX_FRC_CONTROLLER_PORTS,
    )
    private val inputFrames = Array(MAX_FRC_CONTROLLER_PORTS) { InputFrame() }

    init {
        var port = 0
        while (port < runtime.controllerPortCapacity) {
            if (runtime.hasControllerPort(port)) portSampler.prepare(port)
            port++
        }
    }

    /** Samples active ports and advances generated work once during an enabled TeleOp frame. */
    fun update() {
        val nowNanos = RobotClock.nanoTime()
        var port = 0
        while (port < runtime.controllerPortCapacity) {
            if (runtime.hasControllerPort(port)) {
                val frame = inputFrames[port]
                portSampler.sampleInto(port, frame, nowNanos)
                runtime.updatePort(port, frame, nowNanos)
            }
            port++
        }
        if (driveEmissionGate()) runtime.emitDriveCommand()
        runtime.updateTasks()
    }

    fun requestRoutine(
        routineId: String,
        policy: RoutineStartPolicy = RoutineStartPolicy.RESTART_EXISTING,
    ): RoutineRequestResult = runtime.requestRoutine(routineId, policy)

    fun updateRoutines() = runtime.updateRoutines()

    /** Releases all generated input and work; the TimedRobot host decides when modes transition. */
    fun cancelAll(reason: String) = runtime.cancelAll(reason)

    val controlsSource: String
        get() = runtime.controlsSource

    val activeControllerPortCount: Int
        get() = runtime.activeControllerPortCount
}

private const val MAX_FRC_CONTROLLER_PORTS = 6
