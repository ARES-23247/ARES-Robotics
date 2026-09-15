// ARES OWNERSHIP: USER-OWNED
package org.firstinspires.ftc.teamcode.opmodes

import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import com.areslib.ftc.FtcBaseRobot
import org.firstinspires.ftc.teamcode.dsl.AresTeleOpBase

/** Drivetrain, intake, shooter, reducers, IO and controls come from the Robot Builder documents. */
@TeleOp(name = "BIOBUZZ TeleOp", group = "BIOBUZZ")
class ARESStarterTeleOp : AresTeleOpBase() {
    override val allowGeneratedDrive = true
    override fun define() = teleOp {
        setup { check(!FtcBaseRobot.isAndroid) { "The BIOBUZZ example has only been validated in simulation." } }
        onStart { generatedHeadingLock = false }
        everyLoop { }
    }
}
