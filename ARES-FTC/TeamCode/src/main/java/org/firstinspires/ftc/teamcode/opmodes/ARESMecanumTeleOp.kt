package org.firstinspires.ftc.teamcode.opmodes

import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import org.firstinspires.ftc.teamcode.dsl.AresTeleOpBase

/**
 * Primary field-centric driver OpMode for the four-motor DECODE robot.
 *
 * Restores a valid Auto pose/alliance from process-local storage, otherwise starts red. Both
 * translation axes are alliance-mirrored by [org.firstinspires.ftc.teamcode.opmodes.robot.AresDriveController];
 * heading remains CCW-positive radians. Light controls are owned by the GUI-authored control scheme
 * and generated action catalog rather than this OpMode.
 */
@TeleOp(name = "Direct Mecanum Drivetrain", group = "ARES")
class ARESMecanumTeleOp : AresTeleOpBase() {

    /** Scheme-authored drive bindings replace the hand-written gamepad drive when present. */
    override val allowGeneratedDrive: Boolean = true

    override fun define() = teleOp {
        
        var isHeadingLockEnabled = true
        
        controls {
            driver.leftStickButton.onPress("Toggle Heading Lock") {
                isHeadingLockEnabled = !isHeadingLockEnabled
                generatedHeadingLock = isHeadingLockEnabled
            }

            driver.y.onPress("Reset Field Centric Pose") {
                robot.resetPoseForAlliance()
            }
            driver.x.onPress("Toggle Alliance") {
                robot.toggleAlliance()
                robot.resetPoseForAlliance()
            }
        }

        setup {
            robot.base.mecanumIO.slewRateLimit = 4.0 // Ramp up to full speed in 0.25 seconds
        }
        
        everyLoop {
            // Generated drive bindings already shaped and mirrored the axes; only OpModes without
            // scheme-authored drive fall back to the hand-written controller here.
            if (!generatedDriveActive) {
                robot.driveWithGamepad(driver, useHeadingLock = isHeadingLockEnabled)
            }
        }
    }
}
