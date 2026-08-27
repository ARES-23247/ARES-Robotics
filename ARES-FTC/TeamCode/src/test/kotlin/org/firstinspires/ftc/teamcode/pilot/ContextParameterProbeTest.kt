package org.firstinspires.ftc.teamcode.pilot

import com.areslib.ftc.dsl.AresOpModeDsl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Probe for what the STABLE subset of Kotlin 2.4 context parameters allows on this toolchain.
 * Not a permanent test: it documents the pilot's findings and guards the compiler behavior
 * we started relying on.
 */
class ContextParameterProbeTest {
    @Suppress("DSL_SCOPE_VIOLATION")
    class Ctx(val robot: String, val driver: String)

    // Shape A: helper that declares a context parameter.
    context(ctx: Ctx)
    private fun robotName(): String = ctx.robot

    @Test
    fun `implicit receiver satisfies a context parameter`() {
        // Inside a receiver lambda (exactly the shape of FtcTeleOpContext blocks),
        // can a context(FtcTeleOpContext) helper resolve without explicit arguments?
        val ctx = Ctx("marvin", "gamepad1")
        val viaReceiver = with(ctx) { robotName() }
        assertEquals("marvin", viaReceiver)
    }

    @Test
    fun `nested lambdas inherit the context for helpers`() {
        val ctx = Ctx("marvin", "gamepad1")
        // Deeper nesting: a button-handler style lambda inside a receiver block.
        val names = with(ctx) {
            listOf({ robotName() }, { driver }).map { it() }
        }
        assertEquals(listOf("marvin", "gamepad1"), names)
    }
}
