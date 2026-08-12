package com.areslib.controls

import com.areslib.routine.RoutineArgumentsBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ControlSchemeDslTest {
    @Test
    fun `novice DSL expresses buttons chords macros and analog triggers`() {
        val scheme = controlScheme("competition", "Competition controls") {
            controller("driver", profile = "vader5-pro") {
                button("a").debounce(0.04).onPress { action("intake.collect") }
                chord("left_bumper", "right_bumper")
                    .chordWindow(0.08)
                    .onPress { routine("score.sequence", RoutineInvocationPolicy.TOGGLE_CANCEL) }
                trigger("right_trigger").maximumActive(4.0).onPress {
                    action("shooter.prepare") {
                        number("rpm", 4_000.0)
                        option("mode", "speaker")
                    }
                }
                axis("left_stick_x").onlyOnChange().onValue("speed") { action("drive.strafe") }
            }
        }

        assertEquals(4, scheme.bindings.size)
        assertTrue(scheme.bindings.first { it.source.kind == ControlSourceKind.CHORD }.suppressConstituentBindings)
        assertEquals(
            mapOf("rpm" to "4000.0", "mode" to "speaker"),
            scheme.bindings.first { it.target.key == "shooter.prepare" }.target.arguments
        )
        assertEquals(
            RoutineInvocationPolicy.TOGGLE_CANCEL,
            scheme.bindings.first { it.target.kind == ControlTargetKind.ROUTINE }.target.routinePolicy
        )
        assertTrue(validateControlScheme(scheme).none { it.severity == ControlValidationSeverity.ERROR })
    }

    @Test
    fun `control action arguments reject duplicates invalid keys and nonfinite numbers`() {
        assertThrows<IllegalStateException> {
            targetWithArguments {
                text("mode", "speaker")
                text("mode", "amp")
            }
        }
        assertThrows<IllegalArgumentException> { targetWithArguments { text("bad key", "value") } }
        assertThrows<IllegalArgumentException> { targetWithArguments { number("rpm", Double.NaN) } }
    }

    private fun targetWithArguments(arguments: RoutineArgumentsBuilder.() -> Unit) =
        BindingTargetBuilder().apply { action("test.action", arguments) }.build()
}
