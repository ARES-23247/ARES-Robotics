package com.areslib.telemetry

import com.areslib.telemetry.SimInputBridge.CommandFrame
import com.areslib.telemetry.SimInputBridge.ACK_VALUE_COUNT
import com.areslib.telemetry.SimInputBridge.LEASE_TIMEOUT_MS
import com.areslib.telemetry.SimInputBridge.MAX_TRANSLATION_MPS
import com.areslib.telemetry.SimInputBridge.MAX_OMEGA_RADIANS_PER_SECOND
import com.areslib.util.RobotClock
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Instance-owned canonical v2 drive receiver. Independent robots/simulators cannot share
 * handshake, sequence or lease state. Accepted commands are immutable owned snapshots;
 * retained-frame polling reuses storage. Accepted new snapshots may allocate.
 * Receiver time, not sender time or repeated polls, determines the 500 ms lease.
 */
class DriveFrameReceiver {
    /** Stable numeric states carried by the atomic acknowledgement frame. */
    private enum class ReceiverStatus(val code: Int) {
        WAITING_FOR_FRAME(0),
        WAITING_FOR_NEUTRAL(1),
        ARMED_NEUTRAL(2),
        ACTIVE(3),
        EXPIRED(4),
        INVALID_FRAME(5),
        OUT_OF_ORDER(6),
    }

    private val neutralFrame = CommandFrame(
        vx = 0.0,
        vy = 0.0,
        omega = 0.0,
        isIntaking = false,
        isFlywheelOn = false,
        isTransferring = false,
        isTeleopMode = false,
        isFieldCentric = false,
        isRedAlliance = false,
        isButtonAPressed = false,
        isButtonBPressed = false,
        isButtonXPressed = false,
        isPoseReset = false,
        sessionNonce = Long.MIN_VALUE,
        sequence = Long.MIN_VALUE,
        clientMonotonicMs = Long.MIN_VALUE,
        receivedAtMs = Long.MIN_VALUE
    )

    private val frame = AtomicReference(neutralFrame)
    private val inputBuffer = DoubleArray(FRAME_VALUE_COUNT)
    private var activeSession = Long.MIN_VALUE
    private var lastSequence = Long.MIN_VALUE
    private var lastClientMonotonicMs = Long.MIN_VALUE
    private val lastFrameBits = LongArray(FRAME_VALUE_COUNT)
    private var sessionArmed = false
    private var receiverStatus = ReceiverStatus.WAITING_FOR_FRAME
    private var lastAcceptedSession = Long.MIN_VALUE
    private var lastAcceptedSequence = Long.MIN_VALUE
    private var lastAcceptedAtMs = Long.MIN_VALUE
    private var rejectedFrameCount = 0L

    /** Accepts a copied v2 payload; null means no new payload and cannot renew the lease. */
    @Synchronized
    fun acceptFrame(values: DoubleArray?, nowMs: Long = RobotClock.currentTimeMillis()): CommandFrame {
        expireLease(nowMs)
        if (values == null) return currentFrame(nowMs)
        if (values.size != FRAME_VALUE_COUNT) return reject(ReceiverStatus.INVALID_FRAME)
        values.copyInto(inputBuffer)

        val version = inputBuffer[VERSION_INDEX]
        val session = protocolInteger(inputBuffer[SESSION_INDEX], requirePositive = true)
        if (session < 0L) return reject(ReceiverStatus.INVALID_FRAME)
        val sequence = protocolInteger(inputBuffer[SEQUENCE_INDEX])
        if (sequence < 0L) return reject(ReceiverStatus.INVALID_FRAME)
        val clientTime = protocolInteger(inputBuffer[CLIENT_TIME_INDEX])
        if (clientTime < 0L) return reject(ReceiverStatus.INVALID_FRAME)
        val vx = inputBuffer[VX_INDEX]
        val vy = inputBuffer[VY_INDEX]
        val omega = inputBuffer[OMEGA_INDEX]
        val flags = protocolInteger(inputBuffer[FLAGS_INDEX])
        if (flags < 0L) return reject(ReceiverStatus.INVALID_FRAME)

        if (version != FRAME_VERSION || flags and KNOWN_FLAGS_MASK.inv() != 0L ||
            !isValidAxis(vx, MAX_TRANSLATION_MPS) ||
            !isValidAxis(vy, MAX_TRANSLATION_MPS) ||
            !isValidAxis(omega, MAX_OMEGA_RADIANS_PER_SECOND)
        ) return reject(ReceiverStatus.INVALID_FRAME)

        if (session != activeSession) {
            activeSession = session
            lastSequence = Long.MIN_VALUE
            lastClientMonotonicMs = Long.MIN_VALUE
            sessionArmed = false
        }

        if (sequence == lastSequence) {
            // NT retains the last value. An identical frame is not a new command and cannot renew
            // the receiver lease; a same-sequence mutation is a protocol violation.
            return if (sameRawFrame()) currentFrame(nowMs) else reject(ReceiverStatus.INVALID_FRAME)
        }
        if (sequence < lastSequence || clientTime < lastClientMonotonicMs) {
            return reject(ReceiverStatus.OUT_OF_ORDER)
        }

        val needsHandshake = !sessionArmed
        if (needsHandshake && !isNeutralHandshake(vx, vy, omega, flags)) {
            return reject(ReceiverStatus.WAITING_FOR_NEUTRAL)
        }

        val accepted = CommandFrame(
            vx = vx,
            vy = vy,
            omega = omega,
            isIntaking = flags has FLAG_INTAKE,
            isFlywheelOn = flags has FLAG_FLYWHEEL,
            isTransferring = flags has FLAG_TRANSFER,
            isTeleopMode = flags has FLAG_TELEOP,
            isFieldCentric = flags has FLAG_FIELD_CENTRIC,
            isRedAlliance = flags has FLAG_RED_ALLIANCE,
            isButtonAPressed = flags has FLAG_BUTTON_A,
            isButtonBPressed = flags has FLAG_BUTTON_B,
            isButtonXPressed = flags has FLAG_BUTTON_X,
            isPoseReset = flags has FLAG_POSE_RESET,
            sessionNonce = session,
            sequence = sequence,
            clientMonotonicMs = clientTime,
            receivedAtMs = nowMs
        )
        lastSequence = sequence
        lastClientMonotonicMs = clientTime
        rememberRawFrame()
        sessionArmed = true
        lastAcceptedSession = session
        lastAcceptedSequence = sequence
        lastAcceptedAtMs = nowMs
        receiverStatus = if (isNeutralHandshake(vx, vy, omega, flags)) {
            ReceiverStatus.ARMED_NEUTRAL
        } else {
            ReceiverStatus.ACTIVE
        }
        frame.set(accepted)
        return accepted
    }

    /** Returns one immutable snapshot, neutralized after the 500 ms receiver-time lease. */
    fun currentFrame(nowMs: Long = RobotClock.currentTimeMillis()): CommandFrame {
        val snapshot = frame.get()
        return if (isFresh(snapshot, nowMs)) snapshot else neutralFrame
    }

    private fun isFresh(snapshot: CommandFrame, nowMs: Long): Boolean =
        snapshot !== neutralFrame && nowMs >= snapshot.receivedAtMs &&
            nowMs - snapshot.receivedAtMs in 0..LEASE_TIMEOUT_MS

    private fun expireLease(nowMs: Long) {
        if (!sessionArmed) return
        val snapshot = frame.get()
        if (!isFresh(snapshot, nowMs)) {
            reject(ReceiverStatus.EXPIRED)
        }
    }

    private fun reject(status: ReceiverStatus): CommandFrame {
        sessionArmed = false
        receiverStatus = status
        rejectedFrameCount++
        frame.set(neutralFrame)
        return neutralFrame
    }

    /**
     * Copies one allocation-free, atomic acknowledgement snapshot for the desktop controller.
     * The accepted session/sequence remain visible after a rejection so a sender can distinguish
     * an unacknowledged frame from an ordinary neutral command.
     *
     * @return the required acknowledgement value count.
     */
    @Synchronized
    fun copyAcknowledgement(destination: DoubleArray, nowMs: Long = RobotClock.currentTimeMillis()): Int {
        require(destination.size >= ACK_VALUE_COUNT) {
            "Drive input acknowledgement requires at least $ACK_VALUE_COUNT values"
        }
        expireLease(nowMs)
        val applied = currentFrame(nowMs)
        val leaseAgeMs = if (lastAcceptedSession == Long.MIN_VALUE) {
            -1L
        } else {
            if (nowMs < lastAcceptedAtMs) 0L else {
                val elapsed = nowMs - lastAcceptedAtMs
                if (elapsed < 0L) Long.MAX_VALUE else elapsed
            }
        }
        destination[0] = ACK_VERSION
        destination[1] = receiverStatus.code.toDouble()
        destination[2] = protocolValue(lastAcceptedSession)
        destination[3] = protocolValue(lastAcceptedSequence)
        destination[4] = leaseAgeMs.toDouble()
        destination[5] = applied.vx
        destination[6] = applied.vy
        destination[7] = applied.omega
        destination[8] = rejectedFrameCount.toDouble()
        return ACK_VALUE_COUNT
    }

    private fun protocolValue(value: Long): Double = if (value == Long.MIN_VALUE) -1.0 else value.toDouble()

    private fun isNeutralHandshake(vx: Double, vy: Double, omega: Double, flags: Long): Boolean =
        vx == 0.0 && vy == 0.0 && omega == 0.0 && flags and ACTUATING_OR_EDGE_FLAGS == 0L

    private fun isValidAxis(value: Double, maximum: Double): Boolean = value.isFinite() && abs(value) <= maximum

    private fun protocolInteger(value: Double, requirePositive: Boolean = false): Long {
        val minimum = if (requirePositive) 1.0 else 0.0
        if (!value.isFinite() || value < minimum || value > MAX_SAFE_INTEGER) return -1L
        val integer = value.toLong()
        return if (integer.toDouble() == value) integer else -1L
    }

    private infix fun Long.has(flag: Long): Boolean = this and flag != 0L

    private fun sameRawFrame(): Boolean {
        for (index in 0 until FRAME_VALUE_COUNT) {
            if (inputBuffer[index].toBits() != lastFrameBits[index]) return false
        }
        return true
    }

    private fun rememberRawFrame() {
        for (index in 0 until FRAME_VALUE_COUNT) lastFrameBits[index] = inputBuffer[index].toBits()
    }

    /** Clears all retained input and protocol state. Intended for simulator lifecycle and tests. */
    @Synchronized
    fun reset() {
        frame.set(neutralFrame)
        activeSession = Long.MIN_VALUE
        lastSequence = Long.MIN_VALUE
        lastClientMonotonicMs = Long.MIN_VALUE
        lastFrameBits.fill(0L)
        inputBuffer.fill(0.0)
        sessionArmed = false
        receiverStatus = ReceiverStatus.WAITING_FOR_FRAME
        lastAcceptedSession = Long.MIN_VALUE
        lastAcceptedSequence = Long.MIN_VALUE
        lastAcceptedAtMs = Long.MIN_VALUE
        rejectedFrameCount = 0L
    }

}

private const val FRAME_VALUE_COUNT = 8
private const val FRAME_VERSION = 2.0
private const val ACK_VERSION = 1.0
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0
private const val VERSION_INDEX = 0
private const val SESSION_INDEX = 1
private const val SEQUENCE_INDEX = 2
private const val CLIENT_TIME_INDEX = 3
private const val VX_INDEX = 4
private const val VY_INDEX = 5
private const val OMEGA_INDEX = 6
private const val FLAGS_INDEX = 7

private const val FLAG_INTAKE = 1L shl 0
private const val FLAG_FLYWHEEL = 1L shl 1
private const val FLAG_TRANSFER = 1L shl 2
private const val FLAG_TELEOP = 1L shl 3
private const val FLAG_FIELD_CENTRIC = 1L shl 4
private const val FLAG_RED_ALLIANCE = 1L shl 5
private const val FLAG_BUTTON_A = 1L shl 6
private const val FLAG_BUTTON_B = 1L shl 7
private const val FLAG_BUTTON_X = 1L shl 8
private const val FLAG_POSE_RESET = 1L shl 9
private const val KNOWN_FLAGS_MASK = (1L shl 10) - 1L
private const val ACTUATING_OR_EDGE_FLAGS = FLAG_INTAKE or FLAG_FLYWHEEL or FLAG_TRANSFER or
    FLAG_BUTTON_A or FLAG_BUTTON_B or FLAG_BUTTON_X or FLAG_POSE_RESET
