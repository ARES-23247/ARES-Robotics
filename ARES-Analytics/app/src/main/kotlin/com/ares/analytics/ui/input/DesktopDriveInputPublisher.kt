package com.ares.analytics.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.ares.analytics.di.KeyboardDriveState
import com.ares.analytics.service.GamepadState
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.shared.models.League
import com.areslib.math.InputMath
import com.areslib.telemetry.schema.DesktopDriveFrameGate
import com.areslib.telemetry.schema.DesktopDriveProtocol
import com.areslib.telemetry.schema.DesktopDriveReceiverStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The 50 Hz safety lease must not share workers with DuckDB, cloud sync, log parsing, or image IO.
 * A single daemon is process-owned and survives navigation/recomposition without thread churn.
 */
private val DRIVE_HEARTBEAT_DISPATCHER = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "ARES-Drive-Heartbeat").apply { isDaemon = true }
}.asCoroutineDispatcher()

/** One fail-closed desktop control snapshot before protocol sequencing is applied. */
internal data class DesktopDriveIntent(
    val command: DesktopFieldDriveCommand,
    val modeFlags: Long,
    val actuationFlags: Long,
)

/** Immutable handoff from Compose/AWT input state to the independent control heartbeat. */
internal data class DesktopKeyboardDriveSnapshot(
    val enabled: Boolean,
    val useGamepad: Boolean,
    val isWPressed: Boolean,
    val isSPressed: Boolean,
    val isAPressed: Boolean,
    val isDPressed: Boolean,
    val isUpPressed: Boolean,
    val isDownPressed: Boolean,
    val isLeftPressed: Boolean,
    val isRightPressed: Boolean,
    val isQPressed: Boolean,
    val isEPressed: Boolean,
    val isJPressed: Boolean,
    val isLPressed: Boolean,
    val isUPressed: Boolean,
    val isShiftPressed: Boolean,
)

internal fun KeyboardDriveState.driveSnapshot() = DesktopKeyboardDriveSnapshot(
    enabled = enabled,
    useGamepad = useGamepad,
    isWPressed = isWPressed,
    isSPressed = isSPressed,
    isAPressed = isAPressed,
    isDPressed = isDPressed,
    isUpPressed = isUpPressed,
    isDownPressed = isDownPressed,
    isLeftPressed = isLeftPressed,
    isRightPressed = isRightPressed,
    isQPressed = isQPressed,
    isEPressed = isEPressed,
    isJPressed = isJPressed,
    isLPressed = isLPressed,
    isUPressed = isUPressed,
    isShiftPressed = isShiftPressed,
)

/**
 * Builds the atomic v2 drive frames for one leased connection session.
 *
 * Every new session holds a neutral handshake across five successfully transmitted frames so the
 * 50 Hz receiver cannot miss it at a scheduling boundary. Motion and mechanism flags are admitted
 * only after that interval, and sequence numbers advance only after transport accepts a frame. The
 * returned array is reused by this session and must be consumed synchronously.
 */
internal class DesktopDriveFrameSession(
    val sessionNonce: Double,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val frame = DoubleArray(DesktopDriveProtocol.VALUE_COUNT)
    private var sequence = 0L
    private var neutralHandshakeFramesTransmitted = 0
    private var lastSuccessfulTransmissionMs: Long? = null
    private val createdAtMs = clockMs()
    private var lastAcknowledgedSequence = -1L
    private var lastAcknowledgedAtMs: Long? = null
    private var receiverHandshakeComplete = false

    private val neutralHandshakeComplete: Boolean
        get() = neutralHandshakeFramesTransmitted >= NEUTRAL_HANDSHAKE_FRAME_COUNT

    fun frameFor(intent: DesktopDriveIntent): DoubleArray = frame.apply {
        val actuationAuthorized = neutralHandshakeComplete && receiverHandshakeComplete
        this[DesktopDriveProtocol.VERSION_INDEX] = DesktopDriveProtocol.VERSION
        this[DesktopDriveProtocol.SESSION_INDEX] = sessionNonce
        this[DesktopDriveProtocol.SEQUENCE_INDEX] = sequence.toDouble()
        this[DesktopDriveProtocol.CLIENT_TIME_INDEX] = clockMs().toDouble()
        this[DesktopDriveProtocol.VX_INDEX] = if (actuationAuthorized) intent.command.vxMetersPerSecond else 0.0
        this[DesktopDriveProtocol.VY_INDEX] = if (actuationAuthorized) intent.command.vyMetersPerSecond else 0.0
        this[DesktopDriveProtocol.OMEGA_INDEX] = if (actuationAuthorized) intent.command.omegaRadiansPerSecond else 0.0
        this[DesktopDriveProtocol.FLAGS_INDEX] = (
            intent.modeFlags or if (actuationAuthorized) intent.actuationFlags else 0L
        ).toDouble()
    }

    fun markTransmitted() {
        if (!neutralHandshakeComplete) neutralHandshakeFramesTransmitted++
        sequence++
        lastSuccessfulTransmissionMs = clockMs()
    }

    /** Age of the last accepted transport write, used only for sparse stall diagnostics. */
    fun successfulTransmissionAgeMs(): Long? = lastSuccessfulTransmissionMs?.let { last ->
        (clockMs() - last).coerceAtLeast(0L)
    }

    /** A delayed publisher must begin a new neutral session before sending motion again. */
    fun needsRehandshake(): Boolean = successfulTransmissionAgeMs()?.let { age ->
        age >= REHANDSHAKE_AFTER_GAP_MS
    } ?: false

    /** Records receiver acceptance, not merely local WebSocket queueing. */
    fun observeReceiverAcknowledgement(receiverSession: Long?, receiverSequence: Long?) {
        if (receiverSession?.toDouble() != sessionNonce || receiverSequence == null || receiverSequence < 0L) return
        if (receiverSequence > lastAcknowledgedSequence) {
            lastAcknowledgedSequence = receiverSequence
            lastAcknowledgedAtMs = clockMs()
            receiverHandshakeComplete = true
        }
    }

    fun receiverAcknowledgementAgeMs(): Long? = lastAcknowledgedAtMs?.let { acceptedAt ->
        (clockMs() - acceptedAt).coerceAtLeast(0L)
    }

    /**
     * Once a simulator advertises the acknowledgement contract, silence means that the receiver
     * did not accept queued frames. Start a new neutral session instead of remaining falsely armed.
     */
    fun needsReceiverRehandshake(
        acknowledgementContractAvailable: Boolean,
        receiverStatusCode: Int? = null,
    ): Boolean {
        if (!acknowledgementContractAvailable) {
            return clockMs() - createdAtMs >= RECEIVER_ACK_STARTUP_TIMEOUT_MS
        }
        // A launched simulator without an active TeleOp intentionally leaves the receiver in
        // WAITING_FOR_FRAME. Replacing an already-neutral sender session cannot make that dormant
        // receiver poll, and doing so every startup timeout only floods diagnostics. Once a TeleOp
        // polls, the acknowledgement changes to an armed, active, or explicit rejection state and
        // the normal fail-closed timeout remains authoritative.
        if (receiverStatusCode == DesktopDriveReceiverStatus.WAITING_FOR_FRAME.code) return false
        return receiverAcknowledgementAgeMs()?.let { it >= RECEIVER_ACK_TIMEOUT_MS }
            ?: (clockMs() - createdAtMs >= RECEIVER_ACK_STARTUP_TIMEOUT_MS)
    }
}

/** Converts current keyboard/gamepad state into canonical league-aware field commands. */
internal fun desktopDriveIntent(
    keyboard: KeyboardDriveState,
    gamepad: GamepadState,
    controlSurfaceActive: Boolean,
    league: League,
    isRedAlliance: Boolean,
): DesktopDriveIntent = desktopDriveIntent(
    keyboard = keyboard.driveSnapshot(),
    gamepad = gamepad,
    controlSurfaceActive = controlSurfaceActive,
    league = league,
    isRedAlliance = isRedAlliance,
)

internal fun desktopDriveIntent(
    keyboard: DesktopKeyboardDriveSnapshot,
    gamepad: GamepadState,
    controlSurfaceActive: Boolean,
    league: League,
    isRedAlliance: Boolean,
): DesktopDriveIntent {
    val armedSurface = controlSurfaceActive && keyboard.enabled
    val inputActive = armedSurface && (!keyboard.useGamepad || gamepad.connected)

    val command = when {
        !inputActive -> DesktopFieldDriveCommand(0.0, 0.0, 0.0)
        keyboard.useGamepad && gamepad.connected -> {
            val forward = InputMath.applyCurve(InputMath.applyDeadband(gamepad.leftStickY.toDouble(), 0.02), 1.2)
            val right = InputMath.applyCurve(InputMath.applyDeadband(gamepad.leftStickX.toDouble(), 0.02), 1.2)
            val counterClockwise = -InputMath.applyCurve(
                InputMath.applyDeadband(gamepad.rightStickX.toDouble(), 0.02),
                1.2,
            )
            mapDesktopFieldCentricDrive(league, forward, right, counterClockwise)
        }
        else -> mapDesktopFieldCentricDrive(
            league = league,
            forward = when {
                keyboard.isWPressed || keyboard.isUpPressed -> 1.0
                keyboard.isSPressed || keyboard.isDownPressed -> -1.0
                else -> 0.0
            },
            right = when {
                keyboard.isDPressed -> 1.0
                keyboard.isAPressed -> -1.0
                else -> 0.0
            },
            counterClockwise = when {
                keyboard.isLeftPressed -> 1.0
                keyboard.isRightPressed -> -1.0
                else -> 0.0
            },
        )
    }

    var actuationFlags = 0L
    if (inputActive && inputPressed(keyboard, gamepad, keyboard.isQPressed) { it.leftBumper }) {
        actuationFlags = actuationFlags or DesktopDriveProtocol.FLAG_INTAKE
    }
    if (inputActive && inputPressed(keyboard, gamepad, keyboard.isEPressed) { it.rightBumper }) {
        actuationFlags = actuationFlags or DesktopDriveProtocol.FLAG_FLYWHEEL
    }
    if (inputActive && inputPressed(keyboard, gamepad, keyboard.isShiftPressed) { it.rightTrigger > 0.5f }) {
        actuationFlags = actuationFlags or DesktopDriveProtocol.FLAG_TRANSFER
    }
    if (inputActive && inputPressed(keyboard, gamepad, keyboard.isJPressed) { it.a }) {
        actuationFlags = actuationFlags or DesktopDriveProtocol.FLAG_BUTTON_A
    }
    if (inputActive && inputPressed(keyboard, gamepad, keyboard.isLPressed) { it.b }) {
        actuationFlags = actuationFlags or DesktopDriveProtocol.FLAG_BUTTON_B
    }
    if (inputActive && inputPressed(keyboard, gamepad, keyboard.isUPressed) { it.x }) {
        actuationFlags = actuationFlags or DesktopDriveProtocol.FLAG_BUTTON_X
    }

    return DesktopDriveIntent(
        command = command,
        modeFlags = desktopDriveModeFlags(isRedAlliance),
        actuationFlags = actuationFlags,
    )
}

private inline fun inputPressed(
    keyboard: DesktopKeyboardDriveSnapshot,
    gamepad: GamepadState,
    keyboardPressed: Boolean,
    gamepadPressed: (GamepadState) -> Boolean,
): Boolean = if (keyboard.useGamepad && gamepad.connected) gamepadPressed(gamepad) else keyboardPressed

/** Owns the resilient 50 Hz desktop control publisher outside the root screen composition. */
@Composable
internal fun DesktopDriveInputPublisher(
    nt4ClientService: Nt4ClientService,
    keyboardState: KeyboardDriveState,
    gamepadState: StateFlow<GamepadState>,
    connected: Boolean,
    controlSurfaceActive: Boolean,
    league: League,
) {
    val keyboardSnapshot = remember(keyboardState) {
        MutableStateFlow(keyboardState.driveSnapshot())
    }
    LaunchedEffect(keyboardState) {
        snapshotFlow { keyboardState.driveSnapshot() }.collect { snapshot ->
            keyboardSnapshot.value = snapshot
        }
    }

    LaunchedEffect(connected, controlSurfaceActive, league) {
        if (!connected) return@LaunchedEffect
        // Incoming telemetry and Compose layout can briefly saturate the UI dispatcher. The
        // simulator's 500 ms fail-closed lease must be renewed independently of that work, using
        // the latest immutable input snapshot produced by the UI thread.
        withContext(DRIVE_HEARTBEAT_DISPATCHER) {
            var lastReportedReceiverStall: Triple<Int?, Long?, Long?>? = null
            while (currentCoroutineContext().isActive) {
                val session = DesktopDriveFrameSession(nt4ClientService.nextDriveSessionNonce())
                try {
                    while (currentCoroutineContext().isActive) {
                        // A single GC/transport pause can outlive the simulator's receiver lease.
                        // Resume with a new session's neutral handshake; an old-session motion
                        // frame is intentionally rejected by the fail-closed receiver.
                        if (session.needsRehandshake()) {
                            System.err.println(
                                "[DesktopDriveInput] Control heartbeat stalled for " +
                                    "${session.successfulTransmissionAgeMs()} ms; starting a neutral session"
                            )
                            break
                        }
                        val acknowledgement = nt4ClientService.driveInputAcknowledgement.value
                        val acknowledgementAvailable = acknowledgement?.version == DesktopDriveFrameGate.ACK_VERSION
                        session.observeReceiverAcknowledgement(
                            receiverSession = acknowledgement?.acceptedSession,
                            receiverSequence = acknowledgement?.acceptedSequence,
                        )
                        if (session.needsReceiverRehandshake(acknowledgementAvailable, acknowledgement?.statusCode)) {
                            val stallIdentity = Triple(
                                acknowledgement?.statusCode,
                                acknowledgement?.acceptedSession,
                                acknowledgement?.acceptedSequence,
                            )
                            if (stallIdentity != lastReportedReceiverStall) {
                                System.err.println(
                                    "[DesktopDriveInput] Simulator acknowledgement stalled for " +
                                        "${session.receiverAcknowledgementAgeMs() ?: "startup"}; " +
                                        "status=${acknowledgement?.statusCode}, " +
                                        "senderSession=${session.sessionNonce.toLong()}, " +
                                        "receiverSession=${acknowledgement?.acceptedSession}, " +
                                        "receiverSequence=${acknowledgement?.acceptedSequence}; " +
                                        "starting a neutral session"
                                )
                                lastReportedReceiverStall = stallIdentity
                            }
                            break
                        }
                        if (acknowledgement?.acceptedSession?.toDouble() == session.sessionNonce) {
                            lastReportedReceiverStall = null
                        }
                        val intent = desktopDriveIntent(
                            keyboard = keyboardSnapshot.value,
                            gamepad = gamepadState.value,
                            controlSurfaceActive = controlSurfaceActive,
                            league = league,
                            isRedAlliance = nt4ClientService.selectedRedAlliance.value,
                        )
                        if (nt4ClientService.publishDriveFrame(session.frameFor(intent))) {
                            session.markTransmitted()
                        }
                        delay(PUBLISH_INTERVAL_MS)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    System.err.println(
                        "[DesktopDriveInput] Publisher session failed; restarting with a neutral frame: " +
                            "${error::class.simpleName}: ${error.message}"
                    )
                    error.printStackTrace(System.err)
                    delay(RESTART_DELAY_MS)
                }
            }
        }
    }
}

private const val PUBLISH_INTERVAL_MS = 20L
private const val RESTART_DELAY_MS = 250L
internal const val REHANDSHAKE_AFTER_GAP_MS = 250L
internal const val NEUTRAL_HANDSHAKE_FRAME_COUNT = 5
// The receiver's independent 500 ms lease remains the safety authority. This sender-side
// diagnostic timeout is intentionally longer so a transient desktop/NT4 scheduler stall cannot
// manufacture a neutral-handshake pause while the simulator is still accepting fresh commands.
internal const val RECEIVER_ACK_TIMEOUT_MS = 2_000L
internal const val RECEIVER_ACK_STARTUP_TIMEOUT_MS = 2_500L
