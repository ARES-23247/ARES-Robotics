package com.areslib.telemetry

import com.areslib.telemetry.SimInputBridge.CommandFrame
import com.areslib.telemetry.SimInputBridge.ACK_VALUE_COUNT
import com.areslib.telemetry.SimInputBridge.LEASE_TIMEOUT_MS
import com.areslib.telemetry.SimInputBridge.MAX_TRANSLATION_MPS
import com.areslib.telemetry.SimInputBridge.MAX_OMEGA_RADIANS_PER_SECOND
import com.areslib.util.RobotClock
import com.areslib.telemetry.schema.DesktopDriveProtocol
import com.areslib.telemetry.schema.DesktopDriveFrameGate
import com.areslib.telemetry.schema.DesktopDriveReceiverStatus as ReceiverStatus
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Instance-owned canonical v2 drive receiver. Independent robots/simulators cannot share
 * handshake, sequence or lease state. Accepted commands are immutable owned snapshots;
 * retained-frame polling reuses storage. Accepted new snapshots may allocate.
 * Receiver time, not sender time or repeated polls, determines the 500 ms lease.
 */
class DriveFrameReceiver {
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
        if (rejectedFrameCount < Long.MAX_VALUE) rejectedFrameCount++
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

private const val FRAME_VALUE_COUNT = DesktopDriveProtocol.VALUE_COUNT
private const val FRAME_VERSION = DesktopDriveProtocol.VERSION
private const val ACK_VERSION = DesktopDriveFrameGate.ACK_VERSION
private const val MAX_SAFE_INTEGER = DesktopDriveProtocol.MAX_SAFE_INTEGER_DOUBLE
private const val VERSION_INDEX = DesktopDriveProtocol.VERSION_INDEX
private const val SESSION_INDEX = DesktopDriveProtocol.SESSION_INDEX
private const val SEQUENCE_INDEX = DesktopDriveProtocol.SEQUENCE_INDEX
private const val CLIENT_TIME_INDEX = DesktopDriveProtocol.CLIENT_TIME_INDEX
private const val VX_INDEX = DesktopDriveProtocol.VX_INDEX
private const val VY_INDEX = DesktopDriveProtocol.VY_INDEX
private const val OMEGA_INDEX = DesktopDriveProtocol.OMEGA_INDEX
private const val FLAGS_INDEX = DesktopDriveProtocol.FLAGS_INDEX

private const val FLAG_INTAKE = DesktopDriveProtocol.FLAG_INTAKE
private const val FLAG_FLYWHEEL = DesktopDriveProtocol.FLAG_FLYWHEEL
private const val FLAG_TRANSFER = DesktopDriveProtocol.FLAG_TRANSFER
private const val FLAG_TELEOP = DesktopDriveProtocol.FLAG_TELEOP
private const val FLAG_FIELD_CENTRIC = DesktopDriveProtocol.FLAG_FIELD_CENTRIC
private const val FLAG_RED_ALLIANCE = DesktopDriveProtocol.FLAG_RED_ALLIANCE
private const val FLAG_BUTTON_A = DesktopDriveProtocol.FLAG_BUTTON_A
private const val FLAG_BUTTON_B = DesktopDriveProtocol.FLAG_BUTTON_B
private const val FLAG_BUTTON_X = DesktopDriveProtocol.FLAG_BUTTON_X
private const val FLAG_POSE_RESET = DesktopDriveProtocol.FLAG_POSE_RESET
private const val KNOWN_FLAGS_MASK = DesktopDriveProtocol.KNOWN_FLAGS_MASK
private const val ACTUATING_OR_EDGE_FLAGS = DesktopDriveProtocol.ACTUATING_OR_EDGE_FLAGS
