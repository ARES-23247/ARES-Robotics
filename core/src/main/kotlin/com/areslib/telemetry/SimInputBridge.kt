package com.areslib.telemetry

import com.areslib.networktables.NT4Server
import com.areslib.util.RobotClock
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Thread-safe receiver for the version-2 dashboard command frame.
 *
 * `ARES/Input/driveFrame` is exactly eight doubles:
 * `[2, sessionNonce, sequence, clientMonotonicMs, vx, vy, omega, flags]`.
 * A session must start with a neutral frame and every accepted frame shares one receiver-time lease.
 * Retained NT values never refresh that lease. Invalid input always disarms the session and returns
 * a completely neutral command until another neutral handshake is received.
 */
object SimInputBridge {
    private const val FRAME_VALUE_COUNT = 8

    data class CommandFrame(
        val vx: Double,
        val vy: Double,
        val omega: Double,
        val isIntaking: Boolean,
        val isFlywheelOn: Boolean,
        val isTransferring: Boolean,
        val isTeleopMode: Boolean,
        val isFieldCentric: Boolean,
        val isRedAlliance: Boolean,
        val isButtonAPressed: Boolean,
        val isButtonBPressed: Boolean,
        val isButtonXPressed: Boolean,
        val isPoseReset: Boolean,
        val sessionNonce: Long,
        val sequence: Long,
        val clientMonotonicMs: Long,
        val receivedAtMs: Long
    )

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
    private var lastFrameBits = LongArray(FRAME_VALUE_COUNT)
    private var sessionArmed = false

    /** Polls NT4 and returns the sole expiry-checked command snapshot. */
    @Synchronized
    @JvmStatic
    fun pollNetworkFrame(nowMs: Long = RobotClock.currentTimeMillis()): CommandFrame {
        expireLease(nowMs)
        val count = NT4Server.copyDoubleArray(TelemetryTopicConstants.DRIVE_INPUT_FRAME, inputBuffer)
        if (count < 0) return currentFrame(nowMs)
        if (count != FRAME_VALUE_COUNT) return reject()

        val version = inputBuffer[VERSION_INDEX]
        val session = protocolInteger(inputBuffer[SESSION_INDEX], requirePositive = true) ?: return reject()
        val sequence = protocolInteger(inputBuffer[SEQUENCE_INDEX]) ?: return reject()
        val clientTime = protocolInteger(inputBuffer[CLIENT_TIME_INDEX]) ?: return reject()
        val vx = inputBuffer[VX_INDEX]
        val vy = inputBuffer[VY_INDEX]
        val omega = inputBuffer[OMEGA_INDEX]
        val flags = protocolInteger(inputBuffer[FLAGS_INDEX]) ?: return reject()

        if (version != FRAME_VERSION || flags and KNOWN_FLAGS_MASK.inv() != 0L ||
            !isValidAxis(vx, MAX_TRANSLATION_MPS) ||
            !isValidAxis(vy, MAX_TRANSLATION_MPS) ||
            !isValidAxis(omega, MAX_OMEGA_RADIANS_PER_SECOND)
        ) return reject()

        if (session != activeSession) {
            activeSession = session
            lastSequence = Long.MIN_VALUE
            lastClientMonotonicMs = Long.MIN_VALUE
            sessionArmed = false
        }

        if (sequence == lastSequence) {
            // NT retains the last value. An identical frame is not a new command and cannot renew
            // the receiver lease; a same-sequence mutation is a protocol violation.
            return if (sameRawFrame()) currentFrame(nowMs) else reject()
        }
        if (sequence < lastSequence || clientTime < lastClientMonotonicMs) return reject()

        val needsHandshake = !sessionArmed
        if (needsHandshake && !isNeutralHandshake(vx, vy, omega, flags)) return reject()

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
        frame.set(accepted)
        return accepted
    }

    /** Returns one immutable snapshot, neutralized after the 500 ms receiver-time lease. */
    @JvmStatic
    fun currentFrame(nowMs: Long = RobotClock.currentTimeMillis()): CommandFrame {
        val snapshot = frame.get()
        val age = nowMs - snapshot.receivedAtMs
        return if (snapshot !== neutralFrame && age in 0..LEASE_TIMEOUT_MS) snapshot else neutralFrame
    }

    private fun expireLease(nowMs: Long) {
        if (!sessionArmed) return
        val snapshot = frame.get()
        if (snapshot === neutralFrame || nowMs - snapshot.receivedAtMs !in 0..LEASE_TIMEOUT_MS) reject()
    }

    private fun reject(): CommandFrame {
        sessionArmed = false
        frame.set(neutralFrame)
        return neutralFrame
    }

    private fun isNeutralHandshake(vx: Double, vy: Double, omega: Double, flags: Long): Boolean =
        vx == 0.0 && vy == 0.0 && omega == 0.0 && flags and ACTUATING_OR_EDGE_FLAGS == 0L

    private fun isValidAxis(value: Double, maximum: Double): Boolean = value.isFinite() && abs(value) <= maximum

    private fun protocolInteger(value: Double, requirePositive: Boolean = false): Long? {
        val minimum = if (requirePositive) 1.0 else 0.0
        if (!value.isFinite() || value < minimum || value > MAX_SAFE_INTEGER) return null
        val integer = value.toLong()
        return integer.takeIf { it.toDouble() == value }
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
    @JvmStatic
    fun reset() {
        frame.set(neutralFrame)
        activeSession = Long.MIN_VALUE
        lastSequence = Long.MIN_VALUE
        lastClientMonotonicMs = Long.MIN_VALUE
        lastFrameBits.fill(0L)
        inputBuffer.fill(0.0)
        sessionArmed = false
    }

    const val LEASE_TIMEOUT_MS = 500L
    const val MAX_TRANSLATION_MPS = 8.0
    const val MAX_OMEGA_RADIANS_PER_SECOND = 4.0 * Math.PI

    private const val FRAME_VERSION = 2.0
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
}
