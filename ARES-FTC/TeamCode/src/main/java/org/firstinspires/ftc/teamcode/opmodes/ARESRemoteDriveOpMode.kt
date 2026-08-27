package org.firstinspires.ftc.teamcode.opmodes

import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import com.areslib.action.RobotAction
import com.areslib.networktables.NT4Server
import com.areslib.telemetry.SimInputBridge
import com.areslib.util.RobotClock
import org.firstinspires.ftc.teamcode.dsl.AresTeleOpBase

/**
 * Accepts leased drive commands from the local ARES NT4 client. Flag bit 4 selects the same
 * field-relative (`1`) or robot-relative (`0`) interpretation used by the shared simulator.
 *
 * Motion is accepted only from the version-2 atomic `ARES/Input/driveFrame` topic. A new session or
 * expired 200 ms receiver lease must publish a complete neutral frame; only a later sequence may
 * move. No scalar axes, heartbeat, or command topic is read. Non-finite/malformed input, retained
 * frames, sequence/client-clock rollback, and networking exceptions command zero velocity.
 */
@TeleOp(name = "ARES Remote Drive (NT4)", group = "ARES")
class ARESRemoteDriveOpMode : AresTeleOpBase() {
    private val driveFrameGate = RemoteDriveFrameGate()
    private val networkFrameBuffer = DoubleArray(RemoteDriveFrameGate.FRAME_SIZE)
    private val driveIntent = RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0)
    private var lastStatusTelemetryMs = 0L

    override fun define() = teleOp {

        setup {
            // Remote commands already arrive as a time series; do not add joystick EMA slew limiting.
            robot.base.mecanumIO.slewRateLimit = null
            robot.addTelemetry("Status", "Remote NT4 client drive mode initialized.")
        }

        everyLoop {
            try {
                val now = RobotClock.currentTimeMillis()
                val valueCount = try {
                    NT4Server.copyDoubleArray(DRIVE_FRAME_TOPIC, networkFrameBuffer)
                } catch (_: Exception) {
                    -1
                }
                val frameFresh = driveFrameGate.observe(
                    encodedFrame = if (valueCount == RemoteDriveFrameGate.FRAME_SIZE) networkFrameBuffer else null,
                    timestampMs = now,
                    maxTranslationMps = robot.base.drive.maxSpeedMps,
                    maxOmegaRadiansPerSecond = robot.base.drive.maxAngularSpeedRadiansPerSecond
                )

                if (now - lastStatusTelemetryMs >= STATUS_TELEMETRY_PERIOD_MS) {
                    lastStatusTelemetryMs = now
                    robot.addTelemetry(
                        "Status",
                        when {
                            frameFresh && driveFrameGate.motionAuthorized -> "DRIVING"
                            frameFresh -> "V2 NEUTRAL HANDSHAKE ACCEPTED"
                            else -> "DISCONNECTED / WAITING FOR V2 NEUTRAL FRAME"
                        }
                    )
                    robot.addTelemetry("vx", driveFrameGate.vx)
                    robot.addTelemetry("vy", driveFrameGate.vy)
                    robot.addTelemetry("omega", driveFrameGate.omega)
                }
                // Apply motion last, after every network read and telemetry operation in this
                // loop has completed. Any earlier exception reaches the hard-zero catch path.
                if (frameFresh && driveFrameGate.motionAuthorized) {
                    dispatchDriveIntent(
                        robot,
                        driveFrameGate.vx,
                        driveFrameGate.vy,
                        driveFrameGate.omega,
                        driveFrameGate.isFieldCentric
                    )
                } else {
                    dispatchDriveIntent(robot, 0.0, 0.0, 0.0, driveFrameGate.isFieldCentric)
                }
            } catch (e: Exception) {
                dispatchDriveIntent(robot, 0.0, 0.0, 0.0, isFieldCentric = true)
                robot.addTelemetry("Status", "WATCHDOG ERROR: ${e.message}")
            }
        }
    }

    /** Reuses one mutable action; the synchronous reducer snapshots every field during dispatch. */
    private fun dispatchDriveIntent(
        robot: AresRobot,
        vx: Double,
        vy: Double,
        omega: Double,
        isFieldCentric: Boolean,
    ) {
        driveIntent.targetXVelocity = vx
        driveIntent.targetYVelocity = vy
        driveIntent.targetAngularVelocity = omega
        driveIntent.timestampMs = RobotClock.currentTimeMillis()
        driveIntent.isFieldCentric = isFieldCentric
        driveIntent.fromHeadingHold = false
        driveIntent.isXLock = false
        robot.base.store.dispatch(driveIntent)
    }

    private companion object {
        const val DRIVE_FRAME_TOPIC = "ARES/Input/driveFrame"
        const val STATUS_TELEMETRY_PERIOD_MS = 100L
    }
}

/**
 * Fail-closed state machine for atomic remote-drive frames.
 *
 * Payload: `[2, sessionNonce, sequence, clientMonotonicMs, vx, vy, omega, flags]`. Metadata and
 * flags are exact integers in JavaScript's safe range, session is positive, sequence increases,
 * and client time never decreases. The receiver lease is based only on [timestampMs]; retained NT4
 * values cannot renew it. A session/re-arm begins with zero axes plus zero actuator/edge flags.
 */
internal class RemoteDriveFrameGate(
    private val timeoutMs: Long = 200L
) {
    private var hasSession = false
    private var sessionNonce = 0L
    private var lastSequence = -1L
    private var lastClientMonotonicMs = -1L
    private var lastAcceptedTimeMs = 0L
    private var neutralHandshakeComplete = false
    private val lastRawBits = LongArray(FRAME_SIZE)

    var vx: Double = 0.0
        private set
    var vy: Double = 0.0
        private set
    var omega: Double = 0.0
        private set
    var motionAuthorized: Boolean = false
        private set
    var isFieldCentric: Boolean = false
        private set

    init {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
    }

    fun observe(
        encodedFrame: DoubleArray?,
        timestampMs: Long,
        maxTranslationMps: Double,
        maxOmegaRadiansPerSecond: Double
    ): Boolean {
        if (timestampMs < 0L) {
            disarmForHandshake(clearSession = true)
            return false
        }
        if (!isFresh(timestampMs)) disarmForHandshake(clearSession = false)
        if (encodedFrame == null || encodedFrame.size != FRAME_SIZE ||
            !maxTranslationMps.isFinite() || maxTranslationMps <= 0.0 ||
            !maxOmegaRadiansPerSecond.isFinite() || maxOmegaRadiansPerSecond <= 0.0
        ) {
            disarmForHandshake(clearSession = true)
            return false
        }

        val version = encodedFrame[VERSION_INDEX]
        val parsedSession = exactNonNegativeLong(encodedFrame[SESSION_INDEX])
        val parsedSequence = exactNonNegativeLong(encodedFrame[SEQUENCE_INDEX])
        val parsedClientTimestamp = exactNonNegativeLong(encodedFrame[CLIENT_TIMESTAMP_INDEX])
        val parsedFlags = exactNonNegativeLong(encodedFrame[FLAGS_INDEX])
        val candidateVx = encodedFrame[VX_INDEX]
        val candidateVy = encodedFrame[VY_INDEX]
        val candidateOmega = encodedFrame[OMEGA_INDEX]
        val translationLimit = minOf(maxTranslationMps, SimInputBridge.MAX_TRANSLATION_MPS)
        val omegaLimit = minOf(maxOmegaRadiansPerSecond, SimInputBridge.MAX_OMEGA_RADIANS_PER_SECOND)
        if (version != PROTOCOL_VERSION || parsedSession == null || parsedSession == 0L ||
            parsedSequence == null || parsedClientTimestamp == null || parsedFlags == null ||
            parsedFlags and KNOWN_FLAGS_MASK.inv() != 0L ||
            !candidateVx.isFinite() || !candidateVy.isFinite() ||
            !candidateOmega.isFinite() || kotlin.math.abs(candidateVx) > translationLimit ||
            kotlin.math.abs(candidateVy) > translationLimit ||
            kotlin.math.abs(candidateOmega) > omegaLimit
        ) {
            disarmForHandshake(clearSession = false)
            return false
        }

        if (!hasSession || parsedSession != sessionNonce) {
            disarmForHandshake(clearSession = false)
            hasSession = true
            sessionNonce = parsedSession
            lastSequence = -1L
            lastClientMonotonicMs = -1L
        }

        if (parsedSequence < lastSequence) {
            disarmForHandshake(clearSession = false)
            return false
        }
        // Polling an unchanged atomic topic may return the same committed sequence many times.
        // It may hold the last coherent command only within the receiver-side freshness lease.
        if (parsedSequence == lastSequence) {
            return if (sameRawFrame(encodedFrame)) isFresh(timestampMs) else {
                disarmForHandshake(clearSession = false)
                false
            }
        }
        if (parsedClientTimestamp < lastClientMonotonicMs) {
            disarmForHandshake(clearSession = false)
            return false
        }
        lastSequence = parsedSequence
        lastClientMonotonicMs = parsedClientTimestamp
        rememberRawFrame(encodedFrame)

        if (!neutralHandshakeComplete) {
            return acceptNeutralHandshake(
                candidateVx,
                candidateVy,
                candidateOmega,
                parsedFlags,
                timestampMs
            )
        }

        vx = candidateVx
        vy = candidateVy
        omega = candidateOmega
        isFieldCentric = parsedFlags and FLAG_FIELD_CENTRIC != 0L
        motionAuthorized = true
        lastAcceptedTimeMs = timestampMs
        return true
    }

    private fun acceptNeutralHandshake(
        candidateVx: Double,
        candidateVy: Double,
        candidateOmega: Double,
        flags: Long,
        timestampMs: Long
    ): Boolean {
        if (candidateVx != 0.0 || candidateVy != 0.0 || candidateOmega != 0.0 ||
            flags and ACTUATING_OR_EDGE_FLAGS != 0L
        ) {
            return false
        }
        neutralHandshakeComplete = true
        motionAuthorized = false
        vx = 0.0
        vy = 0.0
        omega = 0.0
        isFieldCentric = flags and FLAG_FIELD_CENTRIC != 0L
        lastAcceptedTimeMs = timestampMs
        return true
    }

    private fun isFresh(timestampMs: Long): Boolean {
        if (!neutralHandshakeComplete) return false
        if (timestampMs < lastAcceptedTimeMs) return false
        val elapsedMs = timestampMs - lastAcceptedTimeMs
        return elapsedMs < timeoutMs
    }

    private fun disarmForHandshake(clearSession: Boolean) {
        neutralHandshakeComplete = false
        motionAuthorized = false
        vx = 0.0
        vy = 0.0
        omega = 0.0
        isFieldCentric = false
        lastAcceptedTimeMs = 0L
        if (clearSession) {
            hasSession = false
            sessionNonce = 0L
            lastSequence = -1L
            lastClientMonotonicMs = -1L
            lastRawBits.fill(0L)
        }
    }

    private fun sameRawFrame(frame: DoubleArray): Boolean {
        for (index in 0 until FRAME_SIZE) {
            if (frame[index].toBits() != lastRawBits[index]) return false
        }
        return true
    }

    private fun rememberRawFrame(frame: DoubleArray) {
        for (index in 0 until FRAME_SIZE) lastRawBits[index] = frame[index].toBits()
    }

    private fun exactNonNegativeLong(value: Double): Long? {
        if (!value.isFinite() || value < 0.0 || value > MAX_SAFE_INTEGER_AS_DOUBLE) return null
        val parsed = value.toLong()
        return parsed.takeIf { it.toDouble() == value }
    }

    companion object {
        const val FRAME_SIZE = 8
        const val VERSION_INDEX = 0
        const val SESSION_INDEX = 1
        const val SEQUENCE_INDEX = 2
        const val CLIENT_TIMESTAMP_INDEX = 3
        const val VX_INDEX = 4
        const val VY_INDEX = 5
        const val OMEGA_INDEX = 6
        const val FLAGS_INDEX = 7
        const val PROTOCOL_VERSION = 2.0
        const val MAX_SAFE_INTEGER_AS_DOUBLE = 9_007_199_254_740_991.0
        const val FLAG_INTAKE = 1L shl 0
        const val FLAG_FLYWHEEL = 1L shl 1
        const val FLAG_TRANSFER = 1L shl 2
        const val FLAG_FIELD_CENTRIC = 1L shl 4
        const val FLAG_BUTTON_A = 1L shl 6
        const val FLAG_BUTTON_B = 1L shl 7
        const val FLAG_BUTTON_X = 1L shl 8
        const val FLAG_POSE_RESET = 1L shl 9
        const val KNOWN_FLAGS_MASK = (1L shl 10) - 1L
        const val ACTUATING_OR_EDGE_FLAGS = FLAG_INTAKE or FLAG_FLYWHEEL or FLAG_TRANSFER or
            FLAG_BUTTON_A or FLAG_BUTTON_B or FLAG_BUTTON_X or FLAG_POSE_RESET
    }
}
