package com.areslib.telemetry.schema

import kotlin.math.abs

/** Stable wire constants for the allocation-free ARES desktop drive frame. */
public object DesktopDriveProtocol {
    public const val VALUE_COUNT: Int = 8
    public const val VERSION: Double = 2.0
    public const val MAX_SAFE_INTEGER_LONG: Long = 9_007_199_254_740_991L
    public const val MAX_SAFE_INTEGER_DOUBLE: Double = 9_007_199_254_740_991.0
    public const val MAX_TRANSLATION_METERS_PER_SECOND: Double = 8.0
    public const val MAX_ANGULAR_RADIANS_PER_SECOND: Double = 12.566370614359172

    public const val VERSION_INDEX: Int = 0
    public const val SESSION_INDEX: Int = 1
    public const val SEQUENCE_INDEX: Int = 2
    public const val CLIENT_TIME_INDEX: Int = 3
    public const val VX_INDEX: Int = 4
    public const val VY_INDEX: Int = 5
    public const val OMEGA_INDEX: Int = 6
    public const val FLAGS_INDEX: Int = 7

    public const val FLAG_INTAKE: Long = 1L shl 0
    public const val FLAG_FLYWHEEL: Long = 1L shl 1
    public const val FLAG_TRANSFER: Long = 1L shl 2
    public const val FLAG_TELEOP: Long = 1L shl 3
    public const val FLAG_FIELD_CENTRIC: Long = 1L shl 4
    public const val FLAG_RED_ALLIANCE: Long = 1L shl 5
    public const val FLAG_BUTTON_A: Long = 1L shl 6
    public const val FLAG_BUTTON_B: Long = 1L shl 7
    public const val FLAG_BUTTON_X: Long = 1L shl 8
    public const val FLAG_POSE_RESET: Long = 1L shl 9
    public const val KNOWN_FLAGS_MASK: Long = (1L shl 10) - 1L
    public const val ACTUATING_OR_EDGE_FLAGS: Long = FLAG_INTAKE or FLAG_FLYWHEEL or
        FLAG_TRANSFER or FLAG_BUTTON_A or FLAG_BUTTON_B or FLAG_BUTTON_X or FLAG_POSE_RESET
}

/** Receiver state encoded in the desktop-drive acknowledgement frame. */
public enum class DesktopDriveReceiverStatus(public val code: Int) {
    WAITING_FOR_FRAME(0),
    WAITING_FOR_NEUTRAL(1),
    ARMED_NEUTRAL(2),
    ACTIVE(3),
    EXPIRED(4),
    INVALID_FRAME(5),
    OUT_OF_ORDER(6),
}

/**
 * Shared fail-closed receiver for the ARES desktop drive protocol.
 *
 * A new or expired session must publish one exact neutral frame before any later sequence can
 * authorize motion. Re-reading an identical retained frame never renews the receiver-time lease.
 * The class mutates preallocated scalar/bit buffers and allocates nothing while observing frames.
 */
public class DesktopDriveFrameGate(
    public val timeoutMs: Long = 200L,
) {
    private var hasSession: Boolean = false
    private var sessionNonce: Long = 0L
    private var lastSequence: Long = -1L
    private var lastClientTimeMs: Long = -1L
    private var lastAcceptedAtMs: Long = 0L
    private var neutralHandshakeComplete: Boolean = false
    private val lastRawBits: LongArray = LongArray(DesktopDriveProtocol.VALUE_COUNT)
    private var rejectedFrameCount: Long = 0L

    public var vxMetersPerSecond: Double = 0.0
        private set
    public var vyMetersPerSecond: Double = 0.0
        private set
    public var omegaRadiansPerSecond: Double = 0.0
        private set
    public var flags: Long = 0L
        private set
    public var motionAuthorized: Boolean = false
        private set
    public var status: DesktopDriveReceiverStatus = DesktopDriveReceiverStatus.WAITING_FOR_FRAME
        private set

    public val isTeleopMode: Boolean
        get() = flags has DesktopDriveProtocol.FLAG_TELEOP
    public val isFieldCentric: Boolean
        get() = flags has DesktopDriveProtocol.FLAG_FIELD_CENTRIC
    public val isRedAlliance: Boolean
        get() = flags has DesktopDriveProtocol.FLAG_RED_ALLIANCE
    public val buttonA: Boolean
        get() = flags has DesktopDriveProtocol.FLAG_BUTTON_A
    public val buttonB: Boolean
        get() = flags has DesktopDriveProtocol.FLAG_BUTTON_B
    public val buttonX: Boolean
        get() = flags has DesktopDriveProtocol.FLAG_BUTTON_X

    init {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
    }

    /** Validates and observes one atomic frame using only receiver time for freshness. */
    public fun observe(
        encodedFrame: DoubleArray?,
        timestampMs: Long,
        maxTranslationMetersPerSecond: Double = DesktopDriveProtocol.MAX_TRANSLATION_METERS_PER_SECOND,
        maxOmegaRadiansPerSecond: Double = DesktopDriveProtocol.MAX_ANGULAR_RADIANS_PER_SECOND,
    ): Boolean {
        if (timestampMs < 0L || timestampMs < lastAcceptedAtMs) {
            return reject(DesktopDriveReceiverStatus.OUT_OF_ORDER, clearSession = timestampMs < 0L)
        }
        if (neutralHandshakeComplete && !isFresh(timestampMs)) {
            disarm(DesktopDriveReceiverStatus.EXPIRED, clearSession = false)
        }
        if (
            encodedFrame == null || encodedFrame.size != DesktopDriveProtocol.VALUE_COUNT ||
            !maxTranslationMetersPerSecond.isFinite() || maxTranslationMetersPerSecond <= 0.0 ||
            !maxOmegaRadiansPerSecond.isFinite() || maxOmegaRadiansPerSecond <= 0.0
        ) return reject(DesktopDriveReceiverStatus.INVALID_FRAME, clearSession = true)

        val session = exactInteger(encodedFrame[DesktopDriveProtocol.SESSION_INDEX], positive = true)
        val sequence = exactInteger(encodedFrame[DesktopDriveProtocol.SEQUENCE_INDEX], positive = false)
        val clientTime = exactInteger(encodedFrame[DesktopDriveProtocol.CLIENT_TIME_INDEX], positive = false)
        val parsedFlags = exactInteger(encodedFrame[DesktopDriveProtocol.FLAGS_INDEX], positive = false)
        val vx = encodedFrame[DesktopDriveProtocol.VX_INDEX]
        val vy = encodedFrame[DesktopDriveProtocol.VY_INDEX]
        val omega = encodedFrame[DesktopDriveProtocol.OMEGA_INDEX]
        val translationLimit = minOf(
            maxTranslationMetersPerSecond,
            DesktopDriveProtocol.MAX_TRANSLATION_METERS_PER_SECOND,
        )
        val omegaLimit = minOf(
            maxOmegaRadiansPerSecond,
            DesktopDriveProtocol.MAX_ANGULAR_RADIANS_PER_SECOND,
        )
        if (
            encodedFrame[DesktopDriveProtocol.VERSION_INDEX] != DesktopDriveProtocol.VERSION ||
            session == null || sequence == null || clientTime == null || parsedFlags == null ||
            parsedFlags and DesktopDriveProtocol.KNOWN_FLAGS_MASK.inv() != 0L ||
            !validAxis(vx, translationLimit) || !validAxis(vy, translationLimit) ||
            !validAxis(omega, omegaLimit)
        ) return reject(DesktopDriveReceiverStatus.INVALID_FRAME, clearSession = false)

        if (!hasSession || session != sessionNonce) {
            disarm(DesktopDriveReceiverStatus.WAITING_FOR_NEUTRAL, clearSession = false)
            hasSession = true
            sessionNonce = session
            lastSequence = -1L
            lastClientTimeMs = -1L
        }
        if (sequence < lastSequence || clientTime < lastClientTimeMs) {
            return reject(DesktopDriveReceiverStatus.OUT_OF_ORDER, clearSession = false)
        }
        if (sequence == lastSequence) {
            return if (sameRawFrame(encodedFrame) && isFresh(timestampMs)) true
            else reject(DesktopDriveReceiverStatus.OUT_OF_ORDER, clearSession = false)
        }

        lastSequence = sequence
        lastClientTimeMs = clientTime
        rememberRawFrame(encodedFrame)
        if (!neutralHandshakeComplete) {
            if (!isNeutral(vx, vy, omega, parsedFlags)) {
                status = DesktopDriveReceiverStatus.WAITING_FOR_NEUTRAL
                return false
            }
            neutralHandshakeComplete = true
            motionAuthorized = false
            setCommand(0.0, 0.0, 0.0, parsedFlags)
            lastAcceptedAtMs = timestampMs
            status = DesktopDriveReceiverStatus.ARMED_NEUTRAL
            return true
        }

        setCommand(vx, vy, omega, parsedFlags)
        motionAuthorized = true
        lastAcceptedAtMs = timestampMs
        status = if (isNeutral(vx, vy, omega, parsedFlags)) {
            DesktopDriveReceiverStatus.ARMED_NEUTRAL
        } else {
            DesktopDriveReceiverStatus.ACTIVE
        }
        return true
    }

    /** Returns whether the current command remains within its receiver-time lease. */
    public fun isFresh(timestampMs: Long): Boolean {
        if (!neutralHandshakeComplete || timestampMs < lastAcceptedAtMs) return false
        return timestampMs - lastAcceptedAtMs < timeoutMs
    }

    /** Disarms an expired command and reports whether a usable neutral/active frame remains. */
    public fun receiverReady(timestampMs: Long): Boolean {
        if (!isFresh(timestampMs)) {
            if (neutralHandshakeComplete) disarm(DesktopDriveReceiverStatus.EXPIRED, clearSession = false)
            return false
        }
        return true
    }

    /** Writes the stable nine-value acknowledgement without allocating. */
    public fun copyAcknowledgement(destination: DoubleArray, timestampMs: Long): Int {
        require(destination.size >= ACK_VALUE_COUNT) {
            "Desktop drive acknowledgement requires at least $ACK_VALUE_COUNT values"
        }
        val ready = receiverReady(timestampMs)
        val ageMs = if (!hasSession) -1L else (timestampMs - lastAcceptedAtMs).coerceAtLeast(0L)
        destination[0] = ACK_VERSION
        destination[1] = status.code.toDouble()
        destination[2] = if (hasSession) sessionNonce.toDouble() else -1.0
        destination[3] = if (lastSequence >= 0L) lastSequence.toDouble() else -1.0
        destination[4] = ageMs.toDouble()
        destination[5] = if (ready) vxMetersPerSecond else 0.0
        destination[6] = if (ready) vyMetersPerSecond else 0.0
        destination[7] = if (ready) omegaRadiansPerSecond else 0.0
        destination[8] = rejectedFrameCount.toDouble()
        return ACK_VALUE_COUNT
    }

    private fun reject(nextStatus: DesktopDriveReceiverStatus, clearSession: Boolean): Boolean {
        rejectedFrameCount++
        disarm(nextStatus, clearSession)
        return false
    }

    private fun disarm(nextStatus: DesktopDriveReceiverStatus, clearSession: Boolean) {
        neutralHandshakeComplete = false
        motionAuthorized = false
        setCommand(0.0, 0.0, 0.0, 0L)
        lastAcceptedAtMs = 0L
        status = nextStatus
        if (clearSession) {
            hasSession = false
            sessionNonce = 0L
            lastSequence = -1L
            lastClientTimeMs = -1L
            lastRawBits.fill(0L)
        }
    }

    private fun setCommand(vx: Double, vy: Double, omega: Double, nextFlags: Long) {
        vxMetersPerSecond = vx
        vyMetersPerSecond = vy
        omegaRadiansPerSecond = omega
        flags = nextFlags
    }

    private fun sameRawFrame(frame: DoubleArray): Boolean {
        for (index in 0 until DesktopDriveProtocol.VALUE_COUNT) {
            if (frame[index].toBits() != lastRawBits[index]) return false
        }
        return true
    }

    private fun rememberRawFrame(frame: DoubleArray) {
        for (index in 0 until DesktopDriveProtocol.VALUE_COUNT) lastRawBits[index] = frame[index].toBits()
    }

    private fun exactInteger(value: Double, positive: Boolean): Long? {
        val minimum = if (positive) 1.0 else 0.0
        if (!value.isFinite() || value < minimum || value > DesktopDriveProtocol.MAX_SAFE_INTEGER_DOUBLE) return null
        return value.toLong().takeIf { it.toDouble() == value }
    }

    private fun validAxis(value: Double, maximum: Double): Boolean = value.isFinite() && abs(value) <= maximum

    private fun isNeutral(vx: Double, vy: Double, omega: Double, candidateFlags: Long): Boolean =
        vx == 0.0 && vy == 0.0 && omega == 0.0 &&
            candidateFlags and DesktopDriveProtocol.ACTUATING_OR_EDGE_FLAGS == 0L

    private infix fun Long.has(flag: Long): Boolean = this and flag != 0L

    public companion object {
        public const val ACK_VALUE_COUNT: Int = 9
        public const val ACK_VERSION: Double = 1.0
    }
}
