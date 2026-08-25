package org.aresfirst.starter.frc

import com.areslib.input.InputFrame
import com.areslib.util.RobotClock
import edu.wpi.first.networktables.DoubleArrayPublisher
import edu.wpi.first.networktables.DoubleArraySubscriber
import edu.wpi.first.networktables.NetworkTableInstance
import edu.wpi.first.networktables.PubSubOption
import edu.wpi.first.networktables.StringPublisher
import edu.wpi.first.networktables.StringSubscriber
import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.simulation.DriverStationSim
import org.aresfirst.starter.frc.generated.drivebase.GeneratedAresDrivebaseConfig
import org.aresfirst.starter.frc.generatedruntime.FrcControllerPortSampler
import org.aresfirst.starter.frc.generatedruntime.WpilibFrcControllerPortSampler
import java.security.MessageDigest
import java.util.UUID
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

internal enum class FrcStudioRequestedMode { DISABLED, TELEOP, AUTONOMOUS }

internal fun decodeFrcStudioRequestedMode(command: String?): FrcStudioRequestedMode =
    when (command?.trim()?.uppercase()) {
        FrcStudioSimulationBridge.DRIVER_STATION_ENABLE_TELEOP -> FrcStudioRequestedMode.TELEOP
        FrcStudioSimulationBridge.DRIVER_STATION_ENABLE_AUTONOMOUS -> FrcStudioRequestedMode.AUTONOMOUS
        else -> FrcStudioRequestedMode.DISABLED
    }

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
        if (raw.size != FRAME_VALUE_COUNT || raw[VERSION_INDEX] != FRAME_VERSION) {
            return reject(ReceiverStatus.INVALID_FRAME)
        }
        val session = protocolInteger(raw[SESSION_INDEX], requirePositive = true)
            ?: return reject(ReceiverStatus.INVALID_FRAME)
        val sequence = protocolInteger(raw[SEQUENCE_INDEX])
            ?: return reject(ReceiverStatus.INVALID_FRAME)
        val clientTime = protocolInteger(raw[CLIENT_TIME_INDEX])
            ?: return reject(ReceiverStatus.INVALID_FRAME)
        val flags = protocolInteger(raw[FLAGS_INDEX])
            ?: return reject(ReceiverStatus.INVALID_FRAME)
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
        if (!armed && !isNeutral(vx, vy, omega, flags)) {
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
        status = if (isNeutral(vx, vy, omega, flags)) {
            ReceiverStatus.ARMED_NEUTRAL
        } else {
            ReceiverStatus.ACTIVE
        }
        return true
    }

    fun current(nowMs: Long): FrcStudioDriveCommand? {
        val snapshot = current ?: return null
        if (nowMs - snapshot.receivedAtMs in 0..LEASE_TIMEOUT_MS) return snapshot
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
        val ageMs = if (lastAcceptedAtMs == Long.MIN_VALUE) -1L else (nowMs - lastAcceptedAtMs).coerceAtLeast(0L)
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
        rejectedFrameCount++
        return false
    }

    private fun isNeutral(vx: Double, vy: Double, omega: Double, flags: Long): Boolean =
        vx == 0.0 && vy == 0.0 && omega == 0.0 && flags and ACTUATING_FLAGS == 0L

    private fun validAxis(value: Double, maximum: Double): Boolean = value.isFinite() && abs(value) <= maximum

    private fun protocolInteger(value: Double, requirePositive: Boolean = false): Long? {
        val minimum = if (requirePositive) 1.0 else 0.0
        if (!value.isFinite() || value < minimum || value > MAX_SAFE_INTEGER) return null
        return value.toLong().takeIf { it.toDouble() == value }
    }

    private fun protocolValue(value: Long): Double = if (value == Long.MIN_VALUE) -1.0 else value.toDouble()

    private infix fun Long.has(flag: Long): Boolean = this and flag != 0L

    private enum class ReceiverStatus(val code: Int) {
        WAITING_FOR_FRAME(0),
        WAITING_FOR_NEUTRAL(1),
        ARMED_NEUTRAL(2),
        ACTIVE(3),
        EXPIRED(4),
        INVALID_FRAME(5),
        OUT_OF_ORDER(6),
    }

    companion object {
        const val LEASE_TIMEOUT_MS = 500L
        const val ACK_VALUE_COUNT = 9
        private const val FRAME_VALUE_COUNT = 8
        private const val FRAME_VERSION = 2.0
        private const val ACK_VERSION = 1.0
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0
        private const val MAX_TRANSLATION_MPS = 8.0
        private const val MAX_OMEGA_RPS = 4.0 * Math.PI
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
        private const val FLAG_BUTTON_A = 1L shl 6
        private const val FLAG_BUTTON_B = 1L shl 7
        private const val FLAG_BUTTON_X = 1L shl 8
        private const val FLAG_POSE_RESET = 1L shl 9
        private const val KNOWN_FLAGS_MASK = (1L shl 10) - 1L
        private const val ACTUATING_FLAGS = FLAG_INTAKE or FLAG_FLYWHEEL or FLAG_TRANSFER or
            FLAG_BUTTON_A or FLAG_BUTTON_B or FLAG_BUTTON_X or FLAG_POSE_RESET
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

/** A validated field update plus the exact payload digest Studio expects in its receipt. */
internal data class FrcStudioFieldApplication(
    val contract: StarterFieldContract,
    val sha256: String,
    val changed: Boolean,
)

internal fun encodeFrcStudioFieldReceipt(
    application: FrcStudioFieldApplication,
    simulatorSession: String,
    sequence: Long,
): String {
    require(simulatorSession.isNotBlank()) { "simulator field receipt session must not be blank" }
    require(sequence > 0L) { "simulator field receipt sequence must be positive" }
    val config = application.contract.config
    return buildString(320) {
        append("{\"session\":\"")
        append(simulatorSession.replace("\\", "\\\\").replace("\"", "\\\""))
        append("\",\"sequence\":")
        append(sequence)
        append(",\"configId\":\"")
        append(config.id.replace("\\", "\\\\").replace("\"", "\\\""))
        append("\",\"revision\":")
        append(config.revision)
        append(",\"sha256\":\"")
        append(application.sha256)
        append("\",\"obstacleCount\":")
        append(config.obstacles.size)
        append(",\"elementCount\":")
        append(config.elements.size)
        append(",\"aprilTagCount\":")
        append(config.apriltags.size)
        append('}')
    }
}

/**
 * Deterministic revision gate for canonical FRC field documents received from Studio.
 *
 * Re-sending the exact active revision is accepted so a reconnected UI can obtain a fresh
 * receipt without resetting physics. Reusing one revision for different bytes or sending an
 * older revision for the same field ID fails closed.
 */
internal class FrcStudioFieldGate(
    private val loader: (ByteArray) -> StarterFieldContract? = ::loadStarterFieldContract,
) {
    private var activeConfigId = ""
    private var activeRevision = Long.MIN_VALUE
    private var activeSha256 = ""

    var rejectionReason: String? = null
        private set

    fun accept(payload: String): FrcStudioFieldApplication? {
        rejectionReason = null
        if (payload.isBlank()) return reject("Canonical field payload is empty")
        val contract = loader(payload.toByteArray(Charsets.UTF_8))
            ?: return reject(StarterFieldContractLoader.error ?: "Canonical FRC field is invalid")
        val config = contract.config
        val digest = sha256(payload)
        if (config.id == activeConfigId) {
            if (config.revision < activeRevision) {
                return reject("Ignored stale field revision ${config.revision}; active revision is $activeRevision")
            }
            if (config.revision == activeRevision && digest != activeSha256) {
                return reject("Field revision ${config.revision} was reused with different content")
            }
        }
        val changed = config.id != activeConfigId || config.revision != activeRevision || digest != activeSha256
        activeConfigId = config.id
        activeRevision = config.revision
        activeSha256 = digest
        return FrcStudioFieldApplication(contract, digest, changed)
    }

    private fun reject(reason: String): FrcStudioFieldApplication? {
        rejectionReason = reason
        return null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/**
 * Simulation-only ARES Studio bridge for the generic FRC starter.
 *
 * It lets a novice enable TeleOp and drive entirely from ARES Robotics Studio, without depending
 * on an external WPILib Simulation GUI. Real robot builds never instantiate this class. The
 * bridge disables the simulated Driver Station whenever the desktop control lease expires.
 */
internal class FrcStudioSimulationBridge(
    instance: NetworkTableInstance = NetworkTableInstance.getDefault(),
    private val fallbackSampler: FrcControllerPortSampler = WpilibFrcControllerPortSampler(),
    private val gate: FrcStudioDriveFrameGate = FrcStudioDriveFrameGate(),
    private val fieldGate: FrcStudioFieldGate = FrcStudioFieldGate(),
    private val onFieldApplied: (StarterFieldContract) -> Unit = {},
) : FrcControllerPortSampler, AutoCloseable {
    private val driveSubscriber: DoubleArraySubscriber = instance
        .getDoubleArrayTopic(DRIVE_FRAME_TOPIC)
        .subscribe(doubleArrayOf(), PubSubOption.keepDuplicates(true), PubSubOption.pollStorage(32))
    private val commandSubscriber: StringSubscriber = instance
        .getStringTopic(DRIVER_STATION_COMMAND_TOPIC)
        .subscribe(DRIVER_STATION_DISABLE)
    private val acknowledgementPublisher: DoubleArrayPublisher = instance
        .getDoubleArrayTopic(DRIVE_ACK_TOPIC)
        .publish()
    private val statePublisher: StringPublisher = instance
        .getStringTopic(DRIVER_STATION_STATE_TOPIC)
        .publish()
    private val fieldSubscriber: StringSubscriber = instance
        .getStringTopic(FIELD_CONFIG_TOPIC)
        .subscribe("", PubSubOption.keepDuplicates(true), PubSubOption.pollStorage(8))
    private val fieldReceiptPublisher: StringPublisher = instance
        .getStringTopic(FIELD_APPLIED_RECEIPT_TOPIC)
        .publish()
    private val fieldErrorPublisher: StringPublisher = instance
        .getStringTopic(FIELD_APPLY_ERROR_TOPIC)
        .publish()
    private val networkTables = instance
    private val simulatorSession = UUID.randomUUID().toString()
    private var fieldReceiptSequence = 0L
    private val acknowledgement = DoubleArray(FrcStudioDriveFrameGate.ACK_VALUE_COUNT)
    private var studioControlRequested = false
    private var latestCommand: FrcStudioDriveCommand? = null
    private var closed = false

    override fun prepare(port: Int) = fallbackSampler.prepare(port)

    override fun sampleInto(port: Int, frame: InputFrame, nowNanos: Long) {
        if (!studioControlRequested || port != 0) {
            fallbackSampler.sampleInto(port, frame, nowNanos)
            return
        }
        val command = latestCommand
        if (
            command == null ||
            !command.isTeleopMode ||
            !command.isFieldCentric ||
            !DriverStation.isTeleopEnabled()
        ) {
            frame.beginSample(connected = false, sampleTimeNanos = nowNanos)
            return
        }
        command.copyIntoControllerFrame(
            frame = frame,
            nowNanos = nowNanos,
            maximumTranslationMps = GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND,
            maximumAngularRps = GeneratedAresDrivebaseConfig.MAX_ANGULAR_SPEED_RADIANS_PER_SECOND,
        )
    }

    /** Polls commands, applies simulated Driver Station state, and publishes one atomic ack. */
    fun update(nowMs: Long = RobotClock.currentTimeMillis()) {
        check(!closed) { "FRC Studio simulation bridge is closed" }
        updateFieldDocuments()
        for (update in driveSubscriber.readQueue()) gate.accept(update.value, nowMs)
        latestCommand = gate.current(nowMs)

        val requestedMode = decodeFrcStudioRequestedMode(commandSubscriber.get())
        studioControlRequested = requestedMode == FrcStudioRequestedMode.TELEOP
        val autonomousRequested = requestedMode == FrcStudioRequestedMode.AUTONOMOUS
        val shouldEnableTeleOp = studioControlRequested &&
            gate.receiverReady(nowMs) &&
            latestCommand?.isTeleopMode == true &&
            latestCommand?.isFieldCentric == true
        applyDriverStationState(
            enabled = shouldEnableTeleOp || autonomousRequested,
            autonomous = autonomousRequested,
        )

        gate.copyAcknowledgement(acknowledgement, nowMs)
        acknowledgementPublisher.set(acknowledgement)
        statePublisher.set(
            when {
                autonomousRequested -> DRIVER_STATION_AUTONOMOUS_ENABLED
                shouldEnableTeleOp -> DRIVER_STATION_TELEOP_ENABLED
                studioControlRequested -> DRIVER_STATION_WAITING_FOR_CONTROL
                else -> DRIVER_STATION_DISABLED
            }
        )
    }

    /** Applies queued canonical field documents independently of Driver Station state. */
    internal fun updateFieldDocuments() {
        for (update in fieldSubscriber.readQueue()) {
            val application = fieldGate.accept(update.value)
            if (application == null) {
                fieldErrorPublisher.set(fieldGate.rejectionReason ?: "Canonical FRC field was rejected")
                networkTables.flush()
                continue
            }
            if (application.changed) onFieldApplied(application.contract)
            fieldErrorPublisher.set("")
            fieldReceiptSequence++
            fieldReceiptPublisher.set(
                encodeFrcStudioFieldReceipt(application, simulatorSession, fieldReceiptSequence)
            )
            networkTables.flush()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        applyDriverStationState(enabled = false, autonomous = false)
        statePublisher.set(DRIVER_STATION_DISABLED)
        driveSubscriber.close()
        commandSubscriber.close()
        fieldSubscriber.close()
        acknowledgementPublisher.close()
        statePublisher.close()
        fieldReceiptPublisher.close()
        fieldErrorPublisher.close()
    }

    private fun applyDriverStationState(enabled: Boolean, autonomous: Boolean) {
        DriverStationSim.setDsAttached(true)
        DriverStationSim.setAutonomous(autonomous)
        DriverStationSim.setTest(false)
        DriverStationSim.setEnabled(enabled)
        DriverStationSim.notifyNewData()
    }

    companion object {
        const val DRIVER_STATION_COMMAND_TOPIC = "ARES/Simulation/FrcDriverStationCommand"
        const val DRIVER_STATION_STATE_TOPIC = "ARES/Simulation/FrcDriverStationState"
        const val DRIVER_STATION_ENABLE_TELEOP = "ENABLE_TELEOP"
        const val DRIVER_STATION_ENABLE_AUTONOMOUS = "ENABLE_AUTONOMOUS"
        const val DRIVER_STATION_DISABLE = "DISABLE"
        const val DRIVER_STATION_TELEOP_ENABLED = "TELEOP_ENABLED"
        const val DRIVER_STATION_AUTONOMOUS_ENABLED = "AUTONOMOUS_ENABLED"
        const val DRIVER_STATION_WAITING_FOR_CONTROL = "WAITING_FOR_CONTROL"
        const val DRIVER_STATION_DISABLED = "DISABLED"
        const val FIELD_CONFIG_TOPIC = "ARES/Input/fieldConfig"
        const val FIELD_APPLIED_RECEIPT_TOPIC = "ARES/Field/AppliedReceipt"
        const val FIELD_APPLY_ERROR_TOPIC = "ARES/Field/ApplyError"
        private const val DRIVE_FRAME_TOPIC = "ARES/Input/driveFrame"
        private const val DRIVE_ACK_TOPIC = "ARES/Control/DriveInputAck"
    }
}
