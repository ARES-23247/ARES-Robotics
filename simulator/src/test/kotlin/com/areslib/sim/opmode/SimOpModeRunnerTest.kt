package com.areslib.sim.opmode

import kotlin.test.Test
import kotlin.test.assertEquals

class SimOpModeRunnerTest {
    @Test
    fun `project TeleOps hide the library hardware-test fallback`() {
        val starter = "org.firstinspires.ftc.teamcode.opmodes.ARESStarterTeleOp"

        assertEquals(
            listOf(starter),
            SimOpModeRunner.advertisedTeleOps(
                listOf("com.areslib.ftc.hardware.AresHardwareTestOpMode", starter),
            ),
        )
    }

    @Test
    fun `library hardware test remains available without a project TeleOp`() {
        assertEquals(
            listOf("com.areslib.ftc.hardware.AresHardwareTestOpMode"),
            SimOpModeRunner.advertisedTeleOps(
                listOf("com.areslib.ftc.hardware.AresHardwareTestOpMode"),
            ),
        )
    }

    @Test
    fun `project TeleOp inventory is stable and de-duplicated`() {
        val first = "org.firstinspires.ftc.teamcode.opmodes.FirstTeleOp"
        val second = "org.firstinspires.ftc.teamcode.opmodes.SecondTeleOp"

        assertEquals(
            listOf(first, second),
            SimOpModeRunner.advertisedTeleOps(listOf(second, first, second)),
        )
    }
}
