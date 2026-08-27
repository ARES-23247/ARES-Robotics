package com.areslib.sim.opmode

import com.areslib.ftc.photon.AresFtcRuntimeOptions
import com.areslib.ftc.photon.AresFtcRuntimeOptionsProvider
import com.areslib.telemetry.RobotStatusTracker
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.eventloop.opmode.Autonomous
import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.Gamepad
import org.firstinspires.ftc.robotcore.external.Telemetry

/** FTC annotation-derived operating mode for simulator ownership decisions. */
enum class SimOpModeKind { AUTONOMOUS, TELEOP }

/** Canonical simulator-owned lifecycle state published independently of dashboard match timing. */
enum class SimOpModeState {
    AUTO_INIT,
    AUTO_RUNNING,
    TELEOP_INIT,
    TELEOP_RUNNING,
    DISABLED,
}

/**
 * Owns one desktop-simulator OpMode lifecycle.
 *
 * The FTC SDK supports both iterative [OpMode] classes and [LinearOpMode] classes. The desktop
 * mocks intentionally expose those as separate types, so this adapter gives the simulator one
 * fail-closed lifecycle without pretending every discovered OpMode is linear.
 */
class SimOpModeLifecycle private constructor(
    val rawOpMode: Any,
    val modeKind: SimOpModeKind,
) {
    private val iterative = rawOpMode as? OpMode
    private val linear = rawOpMode as? LinearOpMode

    private var initialized = false
    private var started = false
    private var stopped = false
    private var iterativeStopInvoked = false
    private var linearThread: Thread? = null
    @Volatile private var linearFailure: Throwable? = null

    val displayName: String
        get() = rawOpMode.javaClass.simpleName

    val telemetry: Telemetry
        get() = iterative?.telemetry ?: requireNotNull(linear).telemetry

    val gamepad1: Gamepad
        get() = iterative?.gamepad1 ?: requireNotNull(linear).gamepad1

    val gamepad2: Gamepad
        get() = iterative?.gamepad2 ?: requireNotNull(linear).gamepad2

    val isStarted: Boolean
        get() = initialized && started && !stopped && !stopRequested

    val publishedState: SimOpModeState
        get() = when {
            !initialized || stopped || stopRequested -> SimOpModeState.DISABLED
            modeKind == SimOpModeKind.AUTONOMOUS && started -> SimOpModeState.AUTO_RUNNING
            modeKind == SimOpModeKind.AUTONOMOUS -> SimOpModeState.AUTO_INIT
            started -> SimOpModeState.TELEOP_RUNNING
            else -> SimOpModeState.TELEOP_INIT
        }

    val stopRequested: Boolean
        get() = iterative?.isStopRequested == true || linear?.isStopRequested == true

    /** True while a linear worker still owns user OpMode code after a stop request. */
    val hasPendingTermination: Boolean
        get() = linearThread?.isAlive == true

    /** Installs the simulated hardware map and executes the SDK INIT transition exactly once. */
    fun initialize(hardwareMap: HardwareMap) {
        check(!initialized) { "$displayName was already initialized" }
        check(!stopped) { "$displayName was already stopped" }
        initialized = true
        reportSimulatorRuntimeSelection(
            (rawOpMode as? AresFtcRuntimeOptionsProvider)?.aresFtcRuntimeOptions
                ?: AresFtcRuntimeOptions(),
        )
        when {
            iterative != null -> {
                iterative.hardwareMap = hardwareMap
                iterative.isStopRequested = false
                try {
                    iterative.init()
                } catch (failure: Throwable) {
                    stopAfterFailure(failure)
                }
            }

            linear != null -> {
                linear.hardwareMap = hardwareMap
                linear.isStarted = false
                linear.isStopRequested = false
                linearThread = Thread({
                    try {
                        linear.runOpMode()
                    } catch (_: InterruptedException) {
                        // Normal cooperative shutdown.
                    } catch (failure: Throwable) {
                        linearFailure = failure
                    }
                }, "SimOpMode-${displayName}").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    /** Runs one 50 Hz INIT or active callback and surfaces asynchronous linear failures. */
    fun tick() {
        checkInitialized()
        throwLinearFailureIfPresent()
        if (stopped || stopRequested) return
        val mode = iterative ?: return
        val priorPacingOwner = com.areslib.ftc.core.FtcOpModeLifecycleController.beginExternallyPacedFrame()
        try {
            if (started) mode.loop() else mode.init_loop()
        } catch (failure: Throwable) {
            stopAfterFailure(failure)
        } finally {
            com.areslib.ftc.core.FtcOpModeLifecycleController.endExternallyPacedFrame(priorPacingOwner)
        }
        if (mode.isStopRequested) stop()
    }

    /** Executes the SDK START transition. */
    fun start() {
        checkInitialized()
        throwLinearFailureIfPresent()
        check(!stopped && !stopRequested) { "$displayName cannot start after a stop request" }
        if (started) return
        try {
            iterative?.start()
            linear?.isStarted = true
            started = true
        } catch (failure: Throwable) {
            stopAfterFailure(failure)
        }
    }

    /** Requests stop, invokes iterative cleanup, and joins a linear OpMode thread. */
    fun stop() {
        // A failed linear stop deliberately retains its thread handle. Subsequent shutdown passes
        // must retry the join instead of treating the earlier stop request as completed.
        if (stopped && linearThread?.isAlive != true) return
        stopped = true
        var firstFailure: Throwable? = null
        fun record(failure: Throwable) {
            val primary = firstFailure
            if (primary == null) firstFailure = failure
            else if (primary !== failure) primary.addSuppressed(failure)
        }

        iterative?.let { mode ->
            mode.isStopRequested = true
            if (initialized && !iterativeStopInvoked) {
                iterativeStopInvoked = true
                try {
                    mode.stop()
                } catch (failure: Throwable) {
                    record(failure)
                }
            }
        }
        linear?.isStopRequested = true
        linearThread?.let { thread ->
            try {
                thread.join(LINEAR_STOP_JOIN_MS)
                if (thread.isAlive) {
                    thread.interrupt()
                    thread.join(LINEAR_STOP_JOIN_MS)
                }
                if (thread.isAlive) {
                    record(IllegalStateException("$displayName did not terminate after its stop request"))
                } else if (linearThread === thread) {
                    linearThread = null
                }
            } catch (failure: Throwable) {
                record(failure)
            }
        }
        linearFailure?.let(::record)
        firstFailure?.let { throw it }
    }

    private fun checkInitialized() {
        check(initialized) { "$displayName has not been initialized" }
    }

    private fun throwLinearFailureIfPresent() {
        val failure = linearFailure ?: return
        try {
            stop()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw IllegalStateException("$displayName terminated with an exception", failure)
    }

    private fun stopAfterFailure(failure: Throwable): Nothing {
        try {
            stop()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }

    companion object {
        private const val LINEAR_STOP_JOIN_MS = 500L

        fun supports(type: Class<*>): Boolean =
            OpMode::class.java.isAssignableFrom(type) || LinearOpMode::class.java.isAssignableFrom(type)

        fun wrap(instance: Any): SimOpModeLifecycle? {
            if (instance !is OpMode && instance !is LinearOpMode) return null
            val type = instance.javaClass
            val autonomous = type.isAnnotationPresent(Autonomous::class.java)
            val teleop = type.isAnnotationPresent(TeleOp::class.java)
            require(autonomous.xor(teleop)) {
                "${type.name} must declare exactly one of @Autonomous or @TeleOp"
            }
            return SimOpModeLifecycle(
                rawOpMode = instance,
                modeKind = if (autonomous) SimOpModeKind.AUTONOMOUS else SimOpModeKind.TELEOP,
            )
        }
    }
}

/**
 * Publishes the policy selected by the simulated OpMode without claiming that hardware-only
 * acceleration is active. The FTC SDK invokes ARES Photon before physical OpMode initialization;
 * the desktop simulator has no SDK event loop or REV USB modules, so it must report selection at
 * its own lifecycle boundary while keeping the actual-active signal false.
 */
internal fun reportSimulatorRuntimeSelection(options: AresFtcRuntimeOptions) {
    RobotStatusTracker.ftcHubCommandTransport = options.hubCommandTransport.name
    RobotStatusTracker.ftcPhotonActive = false
    RobotStatusTracker.ftcLimelightProxyConfigured = options.limelightProxyEnabled
    RobotStatusTracker.ftcLimelightProxyActive = false
}

/**
 * Owns the simulator's active OpMode transition boundary.
 *
 * A stop failure is terminal because the old user thread may still command shared simulated
 * hardware. The active handle is retained for shutdown diagnostics/retries, and every later
 * install/transition is rejected rather than allowing two OpModes to run concurrently.
 */
internal class SimOpModeLifecycleSlot(initialMode: SimOpModeLifecycle? = null) {
    var activeMode: SimOpModeLifecycle? = initialMode
        private set

    var terminalFailure: Throwable? = null
        private set

    val isTerminal: Boolean
        get() = terminalFailure != null

    /** Test/integration hook for asserting that a new mode factory is never entered when terminal. */
    fun install(factory: () -> SimOpModeLifecycle) {
        checkOperational()
        install(factory())
    }

    fun install(mode: SimOpModeLifecycle) {
        checkOperational()
        installChecked(mode)
    }

    fun stopActive() {
        checkOperational()
        val stopping = activeMode ?: return
        stopOrBecomeTerminal(stopping)
        activeMode = null
    }

    /** Retries cleanup during process shutdown without reopening the terminal transition gate. */
    fun stopActiveForShutdown() {
        val stopping = activeMode ?: return
        try {
            stopping.stop()
            activeMode = null
        } catch (failure: Throwable) {
            if (terminalFailure == null) terminalFailure = failure
            throw failure
        }
    }

    fun stopCandidate(candidate: SimOpModeLifecycle?) {
        if (candidate == null || candidate === activeMode) return
        checkOperational()
        try {
            stopOrBecomeTerminal(candidate)
        } catch (failure: Throwable) {
            // The candidate may already own a running LinearOpMode thread even though INIT did not
            // complete. Retain it as the active lifecycle solely for shutdown retry/diagnostics.
            if (activeMode == null) activeMode = candidate
            throw failure
        }
    }

    private fun stopOrBecomeTerminal(mode: SimOpModeLifecycle) {
        try {
            mode.stop()
        } catch (failure: Throwable) {
            terminalFailure = failure
            throw IllegalStateException(
                "Simulator lifecycle is terminal because ${mode.displayName} failed to stop",
                failure,
            )
        }
    }

    private fun checkOperational() {
        terminalFailure?.let { failure ->
            throw IllegalStateException(
                "Simulator lifecycle is terminal; restart the simulator before selecting another OpMode",
                failure,
            )
        }
    }

    private fun installChecked(mode: SimOpModeLifecycle) {
        check(activeMode == null) { "An OpMode is already active" }
        activeMode = mode
    }
}
