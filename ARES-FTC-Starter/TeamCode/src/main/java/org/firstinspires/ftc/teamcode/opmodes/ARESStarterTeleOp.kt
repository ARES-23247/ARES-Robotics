// ARES OWNERSHIP: GENERATED STARTER
package org.firstinspires.ftc.teamcode.opmodes

import com.qualcomm.robotcore.eventloop.opmode.TeleOp
import org.firstinspires.ftc.teamcode.dsl.AresTeleOpBase

/** Generic simulation-first TeleOp driven by the checked-in controller binding document. */
@TeleOp(name = "ARES Zero-Code TeleOp", group = "ARES")
class ARESStarterTeleOp : AresTeleOpBase() {
    override val allowGeneratedDrive: Boolean = true
    override fun define() = teleOp {
        // The checked-in .arescontrols document owns periodic driver behavior. This explicit
        // lifecycle hook keeps the shared FTC DSL's bounded-loop contract visible and testable.
        everyLoop { }
    }
}
