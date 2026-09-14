package org.aresfirst.starter.frc

import com.areslib.input.InputFrame
import com.areslib.telemetry.schema.DesktopDriveProtocol
import com.areslib.telemetry.schema.DesktopDriveFrameGate
import com.areslib.telemetry.schema.DesktopDriveReceiverStatus as ReceiverStatus
import kotlin.math.abs

/** One fresh, validated ARES Studio control frame for the FRC desktop simulator. */
internal data class FrcStudioDriveCommand(
    val vxMetersPerSecond: Double,
    val vyMetersPerSecond: Double,
    val omegaRadiansPerSecond: Double,
    val isTeleopMode: Boolean,
    val isFieldCentric: Boolean,
    val buttonA: Boolean,
    val buttonB: Boolean,
    val buttonX: Boolean,
    val receivedAtMs: Long,
)

/**
 * Fail-closed receiver for the shared ARES Studio v2 drive frame.
 *
 * Receiver time, not the sender clock or a retained NetworkTables value, owns the 500 ms lease.
 * Every new session must begin neutral. Invalid, stale, and out-of-order frames disconnect the
 * generated controller boundary and require another neutral handshake before motion can resume.
 */
internal class FrcStudioDriveFrameGate {
    private var activeSession = Long.MIN_VALUE
    private var lastSequence = Long.MIN_VALUE
    private var lastClientTime = Long.MIN_VALUE
    private var armed = false
    private var current: FrcStudioDriveCommand? = null
    private var status = ReceiverStatus.WAITING_FOR_FRAME
    private var lastAcceptedSession = Long.MIN_VALUE
    private var lastAcceptedSequence = Long.MIN_VALUE
    private var lastAcceptedAtMs = Long.MIN_VALUE
    private var rejectedFrameCount = 0L

    fun accept(raw: DoubleArray, nowMs: Long): Boolean {
        // Expire before a new command can renew the lease, even when no poll ran in between.
        current(nowMs)
        if (raw.size != FRAME_VALUE_COUNT || raw[VERSION_INDEX] != FRAME_VERSION) {
            return reject(ReceiverStatus.INVALID_FRAME)
        }
        val session = protocolInteger(raw[SESSION_INDEX], requirePositive = true)
        if (session < 0L) return reject(ReceiverStatus.INVALID_FRAME)
        val sequence = protocolInteger(raw[SEQUENCE_INDEX])
        if (sequence < 0L) return reject(ReceiverStatus.INVALID_FRAME)
        val clientTime = protocolInteger(raw[CLIENT_TIME_INDEX])
        if (clientTime < 0L) return reject(ReceiverStatus.INVALID_FRAME)
        val flags = protocolInteger(raw[FLAGS_INDEX])
        if (flags < 0L) return reject(ReceiverStatus.INVALID_FRAME)
        val vx = raw[VX_INDEX]
        val vy = raw[VY_INDEX]
        val omega = raw[OMEGA_INDEX]
        if (
            flags and KNOWN_FLAGS_MASK.inv() != 0L ||
            !validAxis(vx, MAX_TRANSLATION_MPS) ||
            !validAxis(vy, MAX_TRANSLATION_MPS) ||
            !validAxis(omega, MAX_OMEGA_RPS)
        ) return reject(ReceiverStatus.INVALID_FRAME)

        if (session != activeSession) {
            activeSession = session
            lastSequence = Long.MIN_VALUE
            lastClientTime = Long.MIN_VALUE
            armed = false
        }
        if (sequence <= lastSequence || clientTime < lastClientTime) {
            return reject(ReceiverStatus.OUT_OF_ORDER)
        }
        val neutral = isNeutral(vx, vy, omega, flags)
        if (!armed && !neutral) {
            return reject(ReceiverStatus.WAITING_FOR_NEUTRAL)
        }

        lastSequence = sequence
        lastClientTime = clientTime
        lastAcceptedSession = session
        lastAcceptedSequence = sequence
        lastAcceptedAtMs = nowMs
        armed = true
        current = FrcStudioDriveCommand(
            vxMetersPerSecond = vx,
            vyMetersPerSecond = vy,
            omegaRadiansPerSecond = omega,
            isTeleopMode = flags has FLAG_TELEOP,
            isFieldCentric = flags has FLAG_FIELD_CENTRIC,
            buttonA = flags has FLAG_BUTTON_A,
            buttonB = flags has FLAG_BUTTON_B,
            buttonX = flags has FLAG_BUTTON_X,
            receivedAtMs = nowMs,
        )
        status = if (neutral) {
            ReceiverStatus.ARMED_NEUTRAL
        } else {
            ReceiverStatus.ACTIVE
        }
        return true
    }

    /**
     * NT timestamps use their own microsecond clock. Their age may only shorten the RobotClock
     * lease; never compare either absolute NT time or controller nanoTime with robot milliseconds.
     * Missing, future, expired or unrepresentable timestamps disarm without installing a frame.
     */
    fun acceptQueued(raw: DoubleArray, timestampMicros: Long, transportNowMicros: Long, nowMs: Long): Boolean {
        current(nowMs)
        if (timestampMicros <= 0L || transportNowMicros < timestampMicros) return reject(ReceiverStatus.EXPIRED)
        val ageMicros = transportNowMicros - timestampMicros
        if (ageMicros > LEASE_TIMEOUT_MS * 1000L) return reject(ReceiverStatus.EXPIRED)
        // Round age up so sub-millisecond queue latency cannot extend the lease.
        val ageMs = (ageMicros + 999L) / 1000L
        if (nowMs < Long.MIN_VALUE + ageMs) return reject(ReceiverStatus.EXPIRED)
        return accept(raw, nowMs - ageMs)
    }

    fun current(nowMs: Long): FrcStudioDriveCommand? {
        val snapshot = current ?: return null
        if (nowMs >= snapshot.receivedAtMs && nowMs - snapshot.receivedAtMs in 0..LEASE_TIMEOUT_MS) return snapshot
        reject(ReceiverStatus.EXPIRED)
        return null
    }

    fun receiverReady(nowMs: Long): Boolean {
        current(nowMs) ?: return false
        return status == ReceiverStatus.ARMED_NEUTRAL || status == ReceiverStatus.ACTIVE
    }

    /** Copies the same nine-value acknowledgement contract used by the FTC simulator. */
    fun copyAcknowledgement(destination: DoubleArray, nowMs: Long): Int {
        require(destination.size >= ACK_VALUE_COUNT) {
            "FRC drive acknowledgement requires at least $ACK_VALUE_COUNT values"
        }
        val applied = current(nowMs)
        val ageMs = when {
            lastAcceptedSession == Long.MIN_VALUE -> -1L
            nowMs < lastAcceptedAtMs -> 0L
            else -> (nowMs - lastAcceptedAtMs).let { if (it < 0L) Long.MAX_VALUE else it }
        }
        destination[0] = ACK_VERSION
        destination[1] = status.code.toDouble()
        destination[2] = protocolValue(lastAcceptedSession)
        destination[3] = protocolValue(lastAcceptedSequence)
        destination[4] = ageMs.toDouble()
        destination[5] = applied?.vxMetersPerSecond ?: 0.0
        destination[6] = applied?.vyMetersPerSecond ?: 0.0
        destination[7] = applied?.omegaRadiansPerSecond ?: 0.0
        destination[8] = rejectedFrameCount.toDouble()
        return ACK_VALUE_COUNT
    }

    internal fun statusCode(): Int = status.code

    private fun reject(nextStatus: ReceiverStatus): Boolean {
        armed = false
        current = null
        status = nextStatus
        if (rejectedFrameCount < Long.MAX_VALUE) rejectedFrameCount++
        return false
    }

    private fun isNeutral(vx: Double, vy: Double, omega: Double, flags: Long): Boolean =
        vx == 0.0 && vy == 0.0 && omega == 0.0 && flags and ACTUATING_FLAGS == 0L

    private fun validAxis(value: Double, maximum: Double): Boolean = value.isFinite() && abs(value) <= maximum

    private fun protocolInteger(value: Double, requirePositive: Boolean = false): Long {
        val minimum = if (requirePositive) 1.0 else 0.0
        if (!value.isFinite() || value < minimum || value > MAX_SAFE_INTEGER) return -1L
        val integer = value.toLong()
        return if (integer.toDouble() == value) integer else -1L
    }

    private fun protocolValue(value: Long): Double = if (value == Long.MIN_VALUE) -1.0 else value.toDouble()

    private infix fun Long.has(flag: Long): Boolean = this and flag != 0L

    companion object {
        const val LEASE_TIMEOUT_MS = 500L
        const val ACK_VALUE_COUNT = DesktopDriveFrameGate.ACK_VALUE_COUNT
        private const val FRAME_VALUE_COUNT = DesktopDriveProtocol.VALUE_COUNT
        private const val FRAME_VERSION = DesktopDriveProtocol.VERSION
        private const val ACK_VERSION = DesktopDriveFrameGate.ACK_VERSION
        private const val MAX_SAFE_INTEGER = DesktopDriveProtocol.MAX_SAFE_INTEGER_DOUBLE
        private const val MAX_TRANSLATION_MPS = DesktopDriveProtocol.MAX_TRANSLATION_METERS_PER_SECOND
        private const val MAX_OMEGA_RPS = DesktopDriveProtocol.MAX_ANGULAR_RADIANS_PER_SECOND
        private const val VERSION_INDEX = DesktopDriveProtocol.VERSION_INDEX
        private const val SESSION_INDEX = DesktopDriveProtocol.SESSION_INDEX
        private const val SEQUENCE_INDEX = DesktopDriveProtocol.SEQUENCE_INDEX
        private const val CLIENT_TIME_INDEX = DesktopDriveProtocol.CLIENT_TIME_INDEX
        private const val VX_INDEX = DesktopDriveProtocol.VX_INDEX
        private const val VY_INDEX = DesktopDriveProtocol.VY_INDEX
        private const val OMEGA_INDEX = DesktopDriveProtocol.OMEGA_INDEX
        private const val FLAGS_INDEX = DesktopDriveProtocol.FLAGS_INDEX
        private const val FLAG_TELEOP = DesktopDriveProtocol.FLAG_TELEOP
        private const val FLAG_FIELD_CENTRIC = DesktopDriveProtocol.FLAG_FIELD_CENTRIC
        private const val FLAG_BUTTON_A = DesktopDriveProtocol.FLAG_BUTTON_A
        private const val FLAG_BUTTON_B = DesktopDriveProtocol.FLAG_BUTTON_B
        private const val FLAG_BUTTON_X = DesktopDriveProtocol.FLAG_BUTTON_X
        private const val KNOWN_FLAGS_MASK = DesktopDriveProtocol.KNOWN_FLAGS_MASK
        private const val ACTUATING_FLAGS = DesktopDriveProtocol.ACTUATING_OR_EDGE_FLAGS
    }
}

/** Maps canonical field commands back through the generated Xbox controller boundary. */
internal fun FrcStudioDriveCommand.copyIntoControllerFrame(
    frame: InputFrame,
    nowNanos: Long,
    maximumTranslationMps: Double,
    maximumAngularRps: Double,
) {
    require(maximumTranslationMps.isFinite() && maximumTranslationMps > 0.0)
    require(maximumAngularRps.isFinite() && maximumAngularRps > 0.0)
    frame.beginSample(
        connected = true,
        reportedAxisCount = 6,
        reportedButtonCount = 124,
        sampleTimeNanos = nowNanos,
    )
    // The checked-in controller profile inverts Xbox left-Y, left-X, and right-X exactly once.
    frame.setAxis(1, (-vxMetersPerSecond / maximumTranslationMps).coerceIn(-1.0, 1.0))
    frame.setAxis(0, (-vyMetersPerSecond / maximumTranslationMps).coerceIn(-1.0, 1.0))
    frame.setAxis(4, (-omegaRadiansPerSecond / maximumAngularRps).coerceIn(-1.0, 1.0))
    frame.setButton(0, buttonA)
    frame.setButton(1, buttonB)
    frame.setButton(2, buttonX)
}
