package com.areslib.telemetry

import com.areslib.networktables.NT4Server
import com.areslib.util.RobotClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Thread-safe bridge for one coherent dashboard command frame.
 *
 * Preferred dashboard writers publish `ARES/Input/driveFrame` as the atomic seven-double contract
 * documented by [TelemetryTopicConstants.DRIVE_INPUT_FRAME]. A new session is accepted only after
 * an explicit neutral handshake, and sequence numbers must increase. Legacy scalar writers remain
 * supported only until an atomic frame is observed; their heartbeat is bracket-read and expires.
 * Invalid or stale input returns a neutral frame instead of replaying retained motion forever.
 */
object SimInputBridge {
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
        val heartbeat: Long,
        val receivedAtMs: Long
    )

    private val neutralFrame = CommandFrame(
        vx = 0.0,
        vy = 0.0,
        omega = 0.0,
        isIntaking = false,
        isFlywheelOn = false,
        isTransferring = false,
        isTeleopMode = true,
        isFieldCentric = false,
        isRedAlliance = true,
        isButtonAPressed = false,
        isButtonBPressed = false,
        isButtonXPressed = false,
        isPoseReset = false,
        heartbeat = Long.MIN_VALUE,
        receivedAtMs = Long.MIN_VALUE
    )
    private val frame = AtomicReference(neutralFrame)
    private val legacySequence = AtomicLong(0L)
    private val atomicDriveBuffer = DoubleArray(7)
    @Volatile private var lastNetworkHeartbeat = Long.MIN_VALUE
    private var atomicProtocolObserved = false
    private var activeDriveSession = Long.MIN_VALUE
    private var lastDriveSequence = Long.MIN_VALUE
    private var lastDriveClientTimestampMs = Long.MIN_VALUE
    private var lastDriveVx = Double.NaN
    private var lastDriveVy = Double.NaN
    private var lastDriveOmega = Double.NaN
    private var atomicSessionArmed = false

    /** Atomically validates and installs a complete command frame. */
    @JvmStatic
    fun submitFrame(candidate: CommandFrame): Boolean {
        if (!isValid(candidate)) return false
        frame.set(candidate)
        return true
    }

    /**
     * Reads a stable heartbeat-bracketed NT4 snapshot and returns the active, expiry-checked frame.
     * A repeated heartbeat never refreshes [CommandFrame.receivedAtMs].
     */
    @Synchronized
    @JvmStatic
    fun pollNetworkFrame(nowMs: Long = RobotClock.currentTimeMillis()): CommandFrame {
        expireAtomicLease(nowMs)
        pollAtomicDriveFrame(nowMs)
        if (atomicProtocolObserved) return currentFrame(nowMs)

        val heartbeatBefore = readHeartbeat() ?: return currentFrame(nowMs)
        if (heartbeatBefore != lastNetworkHeartbeat) {
            val candidate = readCommandFrame(
                vx = NT4Server.getDouble(VX_TOPIC, 0.0),
                vy = NT4Server.getDouble(VY_TOPIC, 0.0),
                omega = NT4Server.getDouble(OMEGA_TOPIC, 0.0),
                heartbeat = heartbeatBefore,
                nowMs = nowMs
            )
            val heartbeatAfter = readHeartbeat()
            if (heartbeatAfter == heartbeatBefore && submitFrame(candidate)) {
                lastNetworkHeartbeat = heartbeatBefore
            }
        }
        return currentFrame(nowMs)
    }

    private fun pollAtomicDriveFrame(nowMs: Long) {
        val valueCount = NT4Server.copyDoubleArray(
            TelemetryTopicConstants.DRIVE_INPUT_FRAME,
            atomicDriveBuffer
        )
        if (valueCount < 0) return
        atomicProtocolObserved = true
        if (valueCount != DRIVE_FRAME_VALUE_COUNT) return rejectAtomicFrame()

        if (atomicDriveBuffer[DRIVE_FRAME_VERSION_INDEX] != DRIVE_FRAME_VERSION) return rejectAtomicFrame()
        val session = protocolInteger(atomicDriveBuffer[DRIVE_FRAME_SESSION_INDEX], requirePositive = true)
            ?: return rejectAtomicFrame()
        val sequence = protocolInteger(atomicDriveBuffer[DRIVE_FRAME_SEQUENCE_INDEX])
            ?: return rejectAtomicFrame()
        val clientTimestampMs = protocolInteger(atomicDriveBuffer[DRIVE_FRAME_TIMESTAMP_INDEX])
            ?: return rejectAtomicFrame()
        val vx = atomicDriveBuffer[DRIVE_FRAME_VX_INDEX]
        val vy = atomicDriveBuffer[DRIVE_FRAME_VY_INDEX]
        val omega = atomicDriveBuffer[DRIVE_FRAME_OMEGA_INDEX]

        val candidate = readCommandFrame(vx, vy, omega, sequence, nowMs)
        val candidateValid = isValid(candidate)

        if (session != activeDriveSession) {
            activeDriveSession = session
            lastDriveSequence = sequence
            lastDriveClientTimestampMs = clientTimestampMs
            rememberDriveValues(vx, vy, omega)
            atomicSessionArmed = false
            if (!candidateValid || !isNeutralDrive(vx, vy, omega)) return rejectAtomicFrame()
            atomicSessionArmed = true
            submitFrame(candidate)
            return
        }

        if (sequence == lastDriveSequence) {
            if (clientTimestampMs == lastDriveClientTimestampMs && isRetainedDriveValue(vx, vy, omega)) return
            return rejectAtomicFrame()
        }
        if (sequence < lastDriveSequence || clientTimestampMs < lastDriveClientTimestampMs) {
            return rejectAtomicFrame()
        }
        lastDriveSequence = sequence
        lastDriveClientTimestampMs = clientTimestampMs
        rememberDriveValues(vx, vy, omega)
        if (!candidateValid) return rejectAtomicFrame()
        if (!atomicSessionArmed) {
            if (!isNeutralDrive(vx, vy, omega)) return rejectAtomicFrame()
            atomicSessionArmed = true
        }
        submitFrame(candidate)
    }

    private fun rejectAtomicFrame() {
        atomicSessionArmed = false
        frame.set(neutralFrame)
    }

    private fun expireAtomicLease(nowMs: Long) {
        if (!atomicProtocolObserved || !atomicSessionArmed) return
        val snapshot = frame.get()
        val ageMs = nowMs - snapshot.receivedAtMs
        if (snapshot === neutralFrame || ageMs !in 0..HEARTBEAT_TIMEOUT_MS) {
            rejectAtomicFrame()
        }
    }

    private fun readCommandFrame(
        vx: Double,
        vy: Double,
        omega: Double,
        heartbeat: Long,
        nowMs: Long
    ) = CommandFrame(
        vx = vx,
        vy = vy,
        omega = omega,
        isIntaking = NT4Server.getBoolean(INTAKING_TOPIC, false),
        isFlywheelOn = NT4Server.getBoolean(FLYWHEEL_TOPIC, false),
        isTransferring = NT4Server.getBoolean(TRANSFERRING_TOPIC, false),
        isTeleopMode = NT4Server.getBoolean(TELEOP_TOPIC, true),
        isFieldCentric = NT4Server.getBoolean(FIELD_CENTRIC_TOPIC, false),
        isRedAlliance = NT4Server.getBoolean(RED_ALLIANCE_TOPIC, true),
        isButtonAPressed = NT4Server.getBoolean(BUTTON_A_TOPIC, false),
        isButtonBPressed = NT4Server.getBoolean(BUTTON_B_TOPIC, false),
        isButtonXPressed = NT4Server.getBoolean(BUTTON_X_TOPIC, false),
        isPoseReset = NT4Server.getBoolean(POSE_RESET_TOPIC, false),
        heartbeat = heartbeat,
        receivedAtMs = nowMs
    )

    private fun protocolInteger(value: Double, requirePositive: Boolean = false): Long? {
        if (!value.isFinite() || value < (if (requirePositive) 1.0 else 0.0) || value > MAX_SAFE_INTEGER) return null
        val asLong = value.toLong()
        return asLong.takeIf { it.toDouble() == value }
    }

    private fun isNeutralDrive(vx: Double, vy: Double, omega: Double): Boolean =
        vx == 0.0 && vy == 0.0 && omega == 0.0

    private fun rememberDriveValues(vx: Double, vy: Double, omega: Double) {
        lastDriveVx = vx
        lastDriveVy = vy
        lastDriveOmega = omega
    }

    private fun isRetainedDriveValue(vx: Double, vy: Double, omega: Double): Boolean =
        vx.toBits() == lastDriveVx.toBits() &&
            vy.toBits() == lastDriveVy.toBits() &&
            omega.toBits() == lastDriveOmega.toBits()

    /** Returns one immutable frame snapshot, neutralized when its heartbeat lease has expired. */
    @JvmStatic
    fun currentFrame(nowMs: Long = RobotClock.currentTimeMillis()): CommandFrame {
        val snapshot = frame.get()
        val ageMs = nowMs - snapshot.receivedAtMs
        return if (snapshot !== neutralFrame && ageMs in 0..HEARTBEAT_TIMEOUT_MS) snapshot else neutralFrame
    }

    private fun isValid(candidate: CommandFrame): Boolean {
        return candidate.vx.isFinite() && abs(candidate.vx) <= MAX_TRANSLATION_MPS &&
            candidate.vy.isFinite() && abs(candidate.vy) <= MAX_TRANSLATION_MPS &&
            candidate.omega.isFinite() && abs(candidate.omega) <= MAX_OMEGA_RADIANS_PER_SECOND
    }

    private fun readHeartbeat(): Long? {
        val value = NT4Server.getDouble(HEARTBEAT_TOPIC, Double.NaN)
        return value.takeIf(Double::isFinite)?.toLong()
    }

    /** Clears all retained network/manual input. Intended for simulator lifecycle and tests. */
    @Synchronized
    @JvmStatic
    fun reset() {
        frame.set(neutralFrame)
        lastNetworkHeartbeat = Long.MIN_VALUE
        legacySequence.set(0L)
        atomicProtocolObserved = false
        activeDriveSession = Long.MIN_VALUE
        lastDriveSequence = Long.MIN_VALUE
        lastDriveClientTimestampMs = Long.MIN_VALUE
        lastDriveVx = Double.NaN
        lastDriveVy = Double.NaN
        lastDriveOmega = Double.NaN
        atomicSessionArmed = false
        atomicDriveBuffer.fill(0.0)
    }

    // Source-compatible accessors for older simulator tools. New code should submit/read a frame.
    @Deprecated("Submit an atomic CommandFrame instead")
    @JvmStatic
    var rawWebVx: Double
        get() = currentFrame().vx
        set(value) { submitLegacy(vx = value) }

    @Deprecated("Submit an atomic CommandFrame instead")
    @JvmStatic
    var rawWebVy: Double
        get() = currentFrame().vy
        set(value) { submitLegacy(vy = value) }

    @Deprecated("Submit an atomic CommandFrame instead")
    @JvmStatic
    var rawWebOmega: Double
        get() = currentFrame().omega
        set(value) { submitLegacy(omega = value) }

    val webVx: Double get() = currentFrame().vx
    val webVy: Double get() = currentFrame().vy
    val webOmega: Double get() = currentFrame().omega

    private fun submitLegacy(vx: Double? = null, vy: Double? = null, omega: Double? = null) {
        val prior = currentFrame()
        submitFrame(
            prior.copy(
                vx = vx ?: prior.vx,
                vy = vy ?: prior.vy,
                omega = omega ?: prior.omega,
                heartbeat = legacySequence.incrementAndGet(),
                receivedAtMs = RobotClock.currentTimeMillis()
            )
        )
    }

    const val HEARTBEAT_TIMEOUT_MS = 500L
    const val MAX_TRANSLATION_MPS = 8.0
    const val MAX_OMEGA_RADIANS_PER_SECOND = 4.0 * Math.PI

    private const val DRIVE_FRAME_VALUE_COUNT = 7
    private const val DRIVE_FRAME_VERSION = 1.0
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0
    private const val DRIVE_FRAME_VERSION_INDEX = 0
    private const val DRIVE_FRAME_SESSION_INDEX = 1
    private const val DRIVE_FRAME_SEQUENCE_INDEX = 2
    private const val DRIVE_FRAME_TIMESTAMP_INDEX = 3
    private const val DRIVE_FRAME_VX_INDEX = 4
    private const val DRIVE_FRAME_VY_INDEX = 5
    private const val DRIVE_FRAME_OMEGA_INDEX = 6

    private const val VX_TOPIC = "ARES/Input/vx"
    private const val VY_TOPIC = "ARES/Input/vy"
    private const val OMEGA_TOPIC = "ARES/Input/omega"
    private const val HEARTBEAT_TOPIC = "ARES/Input/heartbeat"
    private const val INTAKING_TOPIC = "ARES/Input/isIntaking"
    private const val FLYWHEEL_TOPIC = "ARES/Input/isFlywheelOn"
    private const val TRANSFERRING_TOPIC = "ARES/Input/isTransferring"
    private const val TELEOP_TOPIC = "ARES/Input/isTeleopMode"
    private const val FIELD_CENTRIC_TOPIC = "ARES/Input/isFieldCentric"
    private const val RED_ALLIANCE_TOPIC = "ARES/Input/isRedAlliance"
    private const val BUTTON_A_TOPIC = "ARES/Input/isButtonAPressed"
    private const val BUTTON_B_TOPIC = "ARES/Input/isButtonBPressed"
    private const val BUTTON_X_TOPIC = "ARES/Input/isButtonXPressed"
    private const val POSE_RESET_TOPIC = "ARES/Input/isPoseReset"

}
