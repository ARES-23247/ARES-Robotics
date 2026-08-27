package com.areslib.routine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoutineDslTest {
    @Test
    fun `student DSL builds the same validated neutral document as the GUI`() {
        val document = routine("score-and-park", "Score and park") {
            together {
                action("shooter.prepare") { number("rpm", 4_000.0) }
                driveTo(2.0, 1.0, 90.0) {
                    motionPreset("safe")
                    atProgress(0.5, "intake.collect")
                }
            }
            waitUntil("shooter.ready", timeoutSeconds = 2.0)
            branch("feeder.hasPiece") {
                then { action("shooter.feed") }
                otherwise { action("shooter.stop") }
            }
            repeatTimes(2) { waitSeconds(0.1) }
        }

        assertEquals("score-and-park", document.documentId)
        assertEquals(RoutineStepKind.TOGETHER, document.steps.first().kind)
        assertEquals("4000.0", document.steps.first().children.first().arguments["rpm"])
        assertEquals(Math.PI / 2.0, document.steps.first().children[1].drive!!.target.headingRadians, 1e-12)
        assertTrue(validateRoutine(document).none { it.severity == RoutineValidationSeverity.ERROR })
    }

    @Test
    fun `typed arguments reject duplicate invalid and nonfinite inputs`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            routine("duplicate", "Duplicate") {
                action("shooter.prepare") {
                    number("rpm", 4_000.0)
                    number("rpm", 5_000.0)
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            routine("nonfinite", "Nonfinite") {
                action("shooter.prepare") { number("rpm", Double.NaN) }
            }
        }
    }
}
