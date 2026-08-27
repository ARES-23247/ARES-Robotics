package org.firstinspires.ftc.teamcode.opmodes

import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import org.firstinspires.ftc.teamcode.dsl.AresTeleOpBase

/**
 * Dedicated live-tuning and characterization mode.
 *
 * Selecting this OpMode locally enables the calibration receiver, but it remains disarmed until
 * the dashboard publishes a fresh nonblank `SysId/EnableToken` while `SysId/Command` is `STOP`.
 * Retained tokens/commands cannot arm it. Normal TeleOp and Auto never enable or poll SysId.
 */
@TeleOp(name = "ARES Live Tuning TeleOp", group = "Tuning")
class ARESTuningTeleOp : AresTeleOpBase() {

    override fun define() = teleOp {
        
        controls {
            driver.y.onPress("Reset Field Centric Pose") {
                robot.resetPoseForAlliance()
            }
            driver.x.onPress("Toggle Alliance") {
                robot.toggleAlliance()
                robot.resetPoseForAlliance()
            }
        }

        setup {
            robot.base.store.dispatch(com.areslib.action.RobotAction.SetAlliance(com.areslib.state.Alliance.RED))
            robot.base.mecanumIO.slewRateLimit = 4.0
            robot.addTelemetry("Tuning", "PRESS PLAY, THEN SEND A FRESH TOKEN + STOP TO ARM")
        }

        onStart {
            // Enabling after the SDK START transition snapshots every INIT-retained token/command
            // as stale. Calibration can never own or energize hardware while the DS is in INIT.
            robot.enableCalibrationMode()
            robot.addTelemetry("Tuning", "LOCAL CALIBRATION MODE; SEND FRESH TOKEN + STOP TO ARM")
        }
        
        everyLoop {
            // Manual drive is available only while remote calibration is not armed. Once armed,
            // the shared controller is the sole drivetrain/mechanism authority.
            if (!robot.base.isCalibrationModeArmed) {
                robot.driveWithGamepad(driver, useHeadingLock = true)
            }
        }
    }
}
