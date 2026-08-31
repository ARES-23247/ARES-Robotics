package org.firstinspires.ftc.teamcode.opmodes

import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import com.areslib.action.RobotAction
import com.areslib.networktables.NT4Server
import com.areslib.telemetry.schema.DesktopDriveFrameGate
import com.areslib.telemetry.schema.DesktopDriveProtocol
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
    private val driveFrameGate = DesktopDriveFrameGate(timeoutMs = 200L)
    private val networkFrameBuffer = DoubleArray(DesktopDriveProtocol.VALUE_COUNT)
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
                    encodedFrame = if (valueCount == DesktopDriveProtocol.VALUE_COUNT) networkFrameBuffer else null,
                    timestampMs = now,
                    maxTranslationMetersPerSecond = robot.base.drive.maxSpeedMps,
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
                    robot.addTelemetry("vx", driveFrameGate.vxMetersPerSecond)
                    robot.addTelemetry("vy", driveFrameGate.vyMetersPerSecond)
                    robot.addTelemetry("omega", driveFrameGate.omegaRadiansPerSecond)
                }
                // Apply motion last, after every network read and telemetry operation in this
                // loop has completed. Any earlier exception reaches the hard-zero catch path.
                if (frameFresh && driveFrameGate.motionAuthorized) {
                    dispatchDriveIntent(
                        robot,
                        driveFrameGate.vxMetersPerSecond,
                        driveFrameGate.vyMetersPerSecond,
                        driveFrameGate.omegaRadiansPerSecond,
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
