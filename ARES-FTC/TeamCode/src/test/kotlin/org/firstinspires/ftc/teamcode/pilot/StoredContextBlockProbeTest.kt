package org.firstinspires.ftc.teamcode.pilot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Probes whether a STORED context-typed lambda can be invoked without the experimental
 * explicit-context-argument flag, by invoking it inside a scope whose implicit receiver
 * satisfies the context requirement. This is the shape a context-parameter lifecycle DSL
 * would need at its runtime boundary.
 */
class StoredContextBlockProbeTest {
    class Ctx(val robot: String, val driver: String)

    private var storedBlock: (context(Ctx) () -> String)? = null

    private fun <T> T.runContextBlock(block: context(T) () -> String): String = block()

    @Test
    fun `stored context lambda invokes through an extension receiver`() {
        storedBlock = { ctx -> "robot=${ctx.robot}" }
        val ctx = Ctx("marvin", "gamepad1")
        assertEquals("robot=marvin", ctx.runContextBlock(requireNotNull(storedBlock)))
    }
}
