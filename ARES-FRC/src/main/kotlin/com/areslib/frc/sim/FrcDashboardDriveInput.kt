package com.areslib.frc.sim

import com.areslib.telemetry.GamepadState
import edu.wpi.first.networktables.DoubleArraySubscriber
import edu.wpi.first.networktables.DoubleArrayPublisher
import edu.wpi.first.networktables.NetworkTableInstance
import edu.wpi.first.networktables.PubSubOption
import edu.wpi.first.networktables.StringPublisher
import edu.wpi.first.networktables.StringSubscriber
import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.RobotController
import edu.wpi.first.wpilibj.simulation.DriverStationSim
import kotlin.math.abs

/** An accepted, receiver-time-leased desktop command for FRC simulation only. */
internal data class FrcDashboardDriveCommand(
    val vxMetersPerSecond: Double,
    val vyMetersPerSecond: Double,
    val omegaRadiansPerSecond: Double,
    val isTeleopMode: Boolean,
    val isFieldCentric: Boolean,
    val isRedAlliance: Boolean,
    val buttonA: Boolean,
    val buttonB: Boolean,
    val buttonX: Boolean,
    val receivedAtMs: Long,
)

/**
 * Fail-closed validator for the shared eight-double ARES desktop drive protocol.
 *
 * A new or expired session must first send a neutral frame. Commands expire from receiver time,
 * not from a retained NetworkTables value, so a stopped dashboard cannot leave motion latched.
 */
internal class FrcDashboardDriveFrameGate {
    private var activeSession = Long.MIN_VALUE
    private var lastSequence = Long.MIN_VALUE
    private var lastClientTime = Long.MIN_VALUE
    private var armed = false
    private var current: FrcDashboardDriveCommand? = null
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
        current = FrcDashboardDriveCommand(
            vxMetersPerSecond = vx,
            vyMetersPerSecond = vy,
            omegaRadiansPerSecond = omega,
            isTeleopMode = flags has FLAG_TELEOP,
            isFieldCentric = flags has FLAG_FIELD_CENTRIC,
            isRedAlliance = flags has FLAG_RED_ALLIANCE,
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

    fun current(nowMs: Long): FrcDashboardDriveCommand? {
        val snapshot = current ?: return null
        if (nowMs - snapshot.receivedAtMs in 0..LEASE_TIMEOUT_MS) return snapshot
        reject(ReceiverStatus.EXPIRED)
        return null
    }

    fun receiverReady(nowMs: Long): Boolean {
        current(nowMs) ?: return false
        return status == ReceiverStatus.ARMED_NEUTRAL || status == ReceiverStatus.ACTIVE
    }

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
        private const val FLAG_RED_ALLIANCE = 1L shl 5
        private const val FLAG_BUTTON_A = 1L shl 6
        private const val FLAG_BUTTON_B = 1L shl 7
        private const val FLAG_BUTTON_X = 1L shl 8
        private const val FLAG_POSE_RESET = 1L shl 9
        private const val KNOWN_FLAGS_MASK = (1L shl 10) - 1L
        private const val ACTUATING_FLAGS = FLAG_INTAKE or FLAG_FLYWHEEL or FLAG_TRANSFER or
            FLAG_BUTTON_A or FLAG_BUTTON_B or FLAG_BUTTON_X or FLAG_POSE_RESET
    }
}

/** Reads queued NT4 updates and applies only fresh, explicitly field-centric TeleOp commands. */
internal class FrcDashboardDriveInput(
    instance: NetworkTableInstance = NetworkTableInstance.getDefault(),
    private val subscriber: DoubleArraySubscriber = instance
        .getDoubleArrayTopic(DRIVE_FRAME_TOPIC)
        .subscribe(
            doubleArrayOf(),
            PubSubOption.keepDuplicates(true),
            PubSubOption.pollStorage(32),
        ),
    private val gate: FrcDashboardDriveFrameGate = FrcDashboardDriveFrameGate(),
) : AutoCloseable {
    private val driverStationCommandSubscriber: StringSubscriber = instance
        .getStringTopic(DRIVER_STATION_COMMAND_TOPIC)
        .subscribe(
            DRIVER_STATION_DISABLE,
            PubSubOption.keepDuplicates(true),
            PubSubOption.pollStorage(8),
        )
    private val acknowledgementPublisher: DoubleArrayPublisher = instance
        .getDoubleArrayTopic(DRIVE_ACK_TOPIC)
        .publish()
    private val driverStationStatePublisher: StringPublisher = instance
        .getStringTopic(DRIVER_STATION_STATE_TOPIC)
        .publish()
    private val acknowledgement = DoubleArray(FrcDashboardDriveFrameGate.ACK_VALUE_COUNT)
    private var studioOwnsDriverStation = false
    private var studioControlRequested = false

    fun poll(nowMs: Long = RobotController.getFPGATime() / 1_000L): FrcDashboardDriveCommand? {
        subscriber.readQueue().forEach { update -> gate.accept(update.value, nowMs) }
        driverStationCommandSubscriber.readQueue().forEach { update ->
            studioOwnsDriverStation = true
            studioControlRequested = update.value.trim().uppercase() == DRIVER_STATION_ENABLE_TELEOP
        }
        val command = gate.current(nowMs)?.takeIf { it.isTeleopMode && it.isFieldCentric }
        if (studioOwnsDriverStation) {
            applyDriverStationState(
                studioControlRequested && gate.receiverReady(nowMs) && command != null,
            )
        }

        gate.copyAcknowledgement(acknowledgement, nowMs)
        acknowledgementPublisher.set(acknowledgement)
        driverStationStatePublisher.set(
            when {
                studioOwnsDriverStation && studioControlRequested && command == null -> DRIVER_STATION_WAITING_FOR_CONTROL
                DriverStation.isTeleopEnabled() -> DRIVER_STATION_TELEOP_ENABLED
                else -> DRIVER_STATION_DISABLED
            }
        )
        return command
    }

    override fun close() {
        if (studioOwnsDriverStation) applyDriverStationState(enabled = false)
        driverStationStatePublisher.set(DRIVER_STATION_DISABLED)
        subscriber.close()
        driverStationCommandSubscriber.close()
        acknowledgementPublisher.close()
        driverStationStatePublisher.close()
    }

    private fun applyDriverStationState(enabled: Boolean) {
        DriverStationSim.setDsAttached(true)
        DriverStationSim.setAutonomous(false)
        DriverStationSim.setTest(false)
        DriverStationSim.setEnabled(enabled)
        DriverStationSim.notifyNewData()
    }

    companion object {
        private const val DRIVE_FRAME_TOPIC = "ARES/Input/driveFrame"
        private const val DRIVE_ACK_TOPIC = "ARES/Control/DriveInputAck"
        private const val DRIVER_STATION_COMMAND_TOPIC = "ARES/Simulation/FrcDriverStationCommand"
        private const val DRIVER_STATION_STATE_TOPIC = "ARES/Simulation/FrcDriverStationState"
        private const val DRIVER_STATION_ENABLE_TELEOP = "ENABLE_TELEOP"
        private const val DRIVER_STATION_DISABLE = "DISABLE"
        private const val DRIVER_STATION_TELEOP_ENABLED = "TELEOP_ENABLED"
        private const val DRIVER_STATION_WAITING_FOR_CONTROL = "WAITING_FOR_CONTROL"
        private const val DRIVER_STATION_DISABLED = "DISABLED"
    }
}

/** Converts canonical FRC field axes back through the normal cached controller boundary. */
internal fun FrcDashboardDriveCommand.applyTo(controllerState: GamepadState) {
    controllerState.leftStickY = (-vxMetersPerSecond / FRC_MAX_TRANSLATION_MPS).coerceIn(-1.0, 1.0).toFloat()
    controllerState.leftStickX = (-vyMetersPerSecond / FRC_MAX_TRANSLATION_MPS).coerceIn(-1.0, 1.0).toFloat()
    controllerState.rightStickX = (-omegaRadiansPerSecond / Math.PI).coerceIn(-1.0, 1.0).toFloat()
    controllerState.a = buttonA
    controllerState.b = buttonB
    controllerState.x = buttonX
}

private const val FRC_MAX_TRANSLATION_MPS = 4.5
