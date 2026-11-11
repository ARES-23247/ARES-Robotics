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
            controller("driver", profile = "vader5-pro", devicePort = 0) {
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
        assertEquals(0, scheme.controllers.single().devicePort)
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

    @Test
    fun `fluent axis shaping whilePressed alias default name and drive targets work without boilerplate`() {
        val scheme = controlScheme("competition_default") {
            controller("driver", profile = "vader5-pro", devicePort = 0) {
                axis("left_stick_y")
                    .deadband(0.06)
                    .curve(2.0)
                    .invert()
                    .scale(0.85)
                    .slewRate(3.5, 5.0)
                    .drive(DriveAxisKeys.VX)

                trigger("right_trigger")
                    .deadband(0.05)
                    .curve(1.5)
                    .whilePressed {
                        action("shooter.spin") { number("rpm", 4200.0) }
                    }
            }
        }

        assertEquals("Competition Default", scheme.name)
        val driveBinding = scheme.bindings.first { it.target.kind == ControlTargetKind.DRIVE }
        assertEquals(DriveAxisKeys.VX, driveBinding.target.key)
        val driveTransform = requireNotNull(driveBinding.source.transform)
        assertEquals(0.06, driveTransform.deadband, 1e-9)
        assertEquals(2.0, driveTransform.exponent, 1e-9)
        assertTrue(driveTransform.inverted)
        assertEquals(-0.85, driveTransform.outputMinimum, 1e-9)
        assertEquals(0.85, driveTransform.outputMaximum, 1e-9)
        assertEquals(3.5, driveBinding.analogPolicy?.riseRatePerSecond)
        assertEquals(5.0, driveBinding.analogPolicy?.fallRatePerSecond)

        val triggerBinding = scheme.bindings.first { it.source.kind == ControlSourceKind.AXIS_THRESHOLD }
        assertEquals(ControlEvent.HELD, triggerBinding.event)
        assertEquals(0.05, triggerBinding.source.transform?.deadband)
        assertEquals(1.5, triggerBinding.source.transform?.exponent)
        assertTrue(validateControlScheme(scheme).none { it.severity == ControlValidationSeverity.ERROR })
    }
}
