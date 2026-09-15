package org.aresfirst.starter.frc

import com.areslib.input.InputFrame
import com.areslib.frc.runtime.FrcControllerPortSampler
import com.areslib.frc.runtime.WpilibFrcControllerPortSampler
import com.areslib.util.RobotClock
import edu.wpi.first.networktables.DoubleArrayPublisher
import edu.wpi.first.networktables.DoubleArraySubscriber
import edu.wpi.first.networktables.NetworkTablesJNI
import edu.wpi.first.networktables.NetworkTableInstance
import edu.wpi.first.networktables.PubSubOption
import edu.wpi.first.networktables.StringPublisher
import edu.wpi.first.networktables.StringSubscriber
import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.simulation.DriverStationSim
import org.aresfirst.starter.frc.generated.drivebase.GeneratedAresDrivebaseConfig
import java.util.UUID

internal enum class FrcStudioRequestedMode { DISABLED, TELEOP, AUTONOMOUS }

internal fun decodeFrcStudioRequestedMode(command: String?): FrcStudioRequestedMode =
    when (command?.trim()?.uppercase()) {
        FrcStudioSimulationBridge.DRIVER_STATION_ENABLE_TELEOP -> FrcStudioRequestedMode.TELEOP
        FrcStudioSimulationBridge.DRIVER_STATION_ENABLE_AUTONOMOUS -> FrcStudioRequestedMode.AUTONOMOUS
        else -> FrcStudioRequestedMode.DISABLED
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
    private val transportTimeMicros: () -> Long = NetworkTablesJNI::now,
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
    private var closed = false

    override fun prepare(port: Int) {
        check(!closed) { "FRC Studio simulation bridge is closed" }
        fallbackSampler.prepare(port)
    }

    override fun sampleInto(port: Int, frame: InputFrame, nowNanos: Long) {
        if (closed) {
            frame.beginSample(connected = false, sampleTimeNanos = nowNanos)
            return
        }
        if (!studioControlRequested || port != 0) {
            fallbackSampler.sampleInto(port, frame, nowNanos)
            return
        }
        // InputFrame timestamps use a different clock origin. Recheck the millisecond lease
        // through RobotClock instead of refreshing old intent with a new sample timestamp.
        val command = gate.current(RobotClock.currentTimeMillis())
        if (command == null && DriverStation.isTeleopEnabled()) {
            applyDriverStationState(enabled = false, autonomous = false)
        }
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
        val queuedDriveFrames = driveSubscriber.readQueue()
        if (queuedDriveFrames.isNotEmpty()) {
            val transportNow = transportTimeMicros()
            for (update in queuedDriveFrames) gate.acceptQueued(update.value, update.timestamp, transportNow, nowMs)
        }
        val latestCommand = gate.current(nowMs)

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
        check(!closed) { "FRC Studio simulation bridge is closed" }
        for (update in fieldSubscriber.readQueue()) {
            val application = fieldGate.accept(update.value, onFieldApplied)
            if (application == null) {
                fieldErrorPublisher.set(fieldGate.rejectionReason ?: "Canonical FRC field was rejected")
                networkTables.flush()
                continue
            }
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
        studioControlRequested = false
        closeStarterResources(listOf(
            AutoCloseable { applyDriverStationState(enabled = false, autonomous = false) },
            AutoCloseable { statePublisher.set(DRIVER_STATION_DISABLED) },
            driveSubscriber, commandSubscriber, fieldSubscriber, acknowledgementPublisher,
            statePublisher, fieldReceiptPublisher, fieldErrorPublisher,
        ))
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
