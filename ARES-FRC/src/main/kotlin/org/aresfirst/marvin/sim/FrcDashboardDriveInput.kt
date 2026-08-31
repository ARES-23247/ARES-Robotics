package org.aresfirst.marvin.sim

import com.areslib.telemetry.GamepadState
import com.areslib.telemetry.schema.DesktopDriveFrameGate
import edu.wpi.first.networktables.DoubleArraySubscriber
import edu.wpi.first.networktables.DoubleArrayPublisher
import edu.wpi.first.networktables.NetworkTableInstance
import edu.wpi.first.networktables.PubSubOption
import edu.wpi.first.networktables.StringPublisher
import edu.wpi.first.networktables.StringSubscriber
import edu.wpi.first.wpilibj.DriverStation
import edu.wpi.first.wpilibj.RobotController
import edu.wpi.first.wpilibj.simulation.DriverStationSim
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
    private val gate: DesktopDriveFrameGate = DesktopDriveFrameGate(timeoutMs = 500L),
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
    private val acknowledgement = DoubleArray(DesktopDriveFrameGate.ACK_VALUE_COUNT)
    private var studioOwnsDriverStation = false
    private var studioControlRequested = false

    fun poll(nowMs: Long = RobotController.getFPGATime() / 1_000L): DesktopDriveFrameGate? {
        subscriber.readQueue().forEach { update -> gate.observe(update.value, nowMs) }
        driverStationCommandSubscriber.readQueue().forEach { update ->
            studioOwnsDriverStation = true
            studioControlRequested = update.value.trim().uppercase() == DRIVER_STATION_ENABLE_TELEOP
        }
        val command = gate.takeIf { it.receiverReady(nowMs) && it.isTeleopMode && it.isFieldCentric }
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
internal fun DesktopDriveFrameGate.applyTo(controllerState: GamepadState) {
    controllerState.leftStickY = (-vxMetersPerSecond / FRC_MAX_TRANSLATION_MPS).coerceIn(-1.0, 1.0).toFloat()
    controllerState.leftStickX = (-vyMetersPerSecond / FRC_MAX_TRANSLATION_MPS).coerceIn(-1.0, 1.0).toFloat()
    controllerState.rightStickX = (-omegaRadiansPerSecond / Math.PI).coerceIn(-1.0, 1.0).toFloat()
    controllerState.a = buttonA
    controllerState.b = buttonB
    controllerState.x = buttonX
}

private const val FRC_MAX_TRANSLATION_MPS = 4.5
