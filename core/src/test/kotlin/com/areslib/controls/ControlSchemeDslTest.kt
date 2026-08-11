package com.areslib.controls

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlSchemeDslTest {
    @Test
    fun `novice DSL expresses buttons chords macros and analog triggers`() {
        val scheme = controlScheme("competition", "Competition controls") {
            controller("driver", profile = "vader5-pro") {
                button("a").debounce(0.04).onPress { action("intake.collect") }
                chord("left_bumper", "right_bumper")
                    .chordWindow(0.08)
                    .onPress { routine("score.sequence", RoutineInvocationPolicy.TOGGLE_CANCEL) }
                trigger("right_trigger").maximumActive(4.0).onPress { action("shooter.prepare") }
                axis("left_stick_x").onlyOnChange().onValue("speed") { action("drive.strafe") }
            }
        }

        assertEquals(4, scheme.bindings.size)
        assertTrue(scheme.bindings.first { it.source.kind == ControlSourceKind.CHORD }.suppressConstituentBindings)
        assertEquals(
            RoutineInvocationPolicy.TOGGLE_CANCEL,
            scheme.bindings.first { it.target.kind == ControlTargetKind.ROUTINE }.target.routinePolicy
        )
        assertTrue(validateControlScheme(scheme).none { it.severity == ControlValidationSeverity.ERROR })
    }
}
