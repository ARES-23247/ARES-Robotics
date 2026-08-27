package org.firstinspires.ftc.teamcode.opmodes

import com.qualcomm.robotcore.eventloop.opmode.Autonomous
import com.areslib.state.Alliance
import org.firstinspires.ftc.teamcode.dsl.AresAutoBase


/** Red-alliance validation entry point for the generated `test-auto` routine. */
@Autonomous(name = "TestAuto - RED", group = "ARES")
class TestAutoRed : AresAutoBase() {
    override val lockedAutonomousEntryId = "test-auto"
    override val lockedAutonomousAlliance = Alliance.RED
}

/**
 * Blue-alliance validation entry point for `test-auto`; the base transforms goals and start pose.
 */
@Autonomous(name = "TestAuto - BLUE", group = "ARES")
class TestAutoBlue : AresAutoBase() {
    override val lockedAutonomousEntryId = "test-auto"
    override val lockedAutonomousAlliance = Alliance.BLUE
}
