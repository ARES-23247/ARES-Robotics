package com.areslib.routine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoutineDocumentTest {
    @Test
    fun `codec round trips a neutral routine with deterministic argument ordering`() {
        val document = RoutineDocument(
            documentId = "score-piece",
            name = "Score Piece",
            steps = listOf(
                RoutineStep.action("shooter.prepare", linkedMapOf("rpm" to "4000", "hood" to "close")),
                RoutineStep.waitUntil("shooter.ready", 1.5),
                RoutineStep.action("feeder.run")
            )
        )

        val encoded = AresRoutineCodec.encode(document)
        val decoded = AresRoutineCodec.decode(encoded)

        assertEquals(document, decoded)
        assertTrue(encoded.indexOf("hood") < encoded.indexOf("rpm"))
        assertEquals(64, AresRoutineCodec.contentHash(document).length)
    }

    @Test
    fun `codec rejects unknown fields and state waits without timeout`() {
        val noTimeout = RoutineDocument(
            documentId = "unsafe-wait",
            name = "Unsafe Wait",
            steps = listOf(
                RoutineStep(kind = RoutineStepKind.WAIT_UNTIL, conditionKey = "shooter.ready")
            )
        )

        assertThrows(IllegalArgumentException::class.java) { AresRoutineCodec.encode(noTimeout) }
        assertThrows(IllegalArgumentException::class.java) {
            AresRoutineCodec.decode(
                """{"documentId":"bad","name":"Bad","steps":[],"surprise":true}"""
            )
        }
    }

    @Test
    fun `project validation detects call cycles and parallel resource conflicts`() {
        val first = RoutineDocument(
            documentId = "first",
            name = "First",
            steps = listOf(RoutineStep.call("second"))
        )
        val second = RoutineDocument(
            documentId = "second",
            name = "Second",
            steps = listOf(
                RoutineStep.call("first"),
                RoutineStep.together(
                    listOf(
                        RoutineStep.action("intake.collect"),
                        RoutineStep.action("intake.stop")
                    )
                )
            )
        )

        val issues = validateRoutineSet(
            listOf(first, second),
            RoutineValidationContext(
                hasAction = { true },
                resourcesForAction = { setOf("intake") }
            )
        )

        assertTrue(issues.any { it.code == "recursive_routine_call" })
        assertTrue(issues.any { it.code == "parallel_resource_conflict" })
    }

    @Test
    fun `validation caps source expansion arguments and input envelope`() {
        val expansionBomb = RoutineDocument(
            documentId = "expansion-bomb",
            name = "Expansion Bomb",
            steps = listOf(
                RoutineStep.repeat(
                    101,
                    listOf(RoutineStep.repeat(101, listOf(RoutineStep.action("safe.action"))))
                )
            )
        )
        assertTrue(validateRoutine(expansionBomb).any { it.code == "routine_expansion_too_large" })

        val tooManySourceSteps = RoutineDocument(
            documentId = "too-many",
            name = "Too Many",
            steps = List(10_001) { RoutineStep.action("safe.action") }
        )
        assertTrue(validateRoutine(tooManySourceSteps).any { it.code == "routine_too_large" })

        val tooManyArguments = RoutineDocument(
            documentId = "argument-bomb",
            name = "Argument Bomb",
            steps = listOf(
                RoutineStep.action("safe.action", (0..64).associate { "key$it" to "value" })
            )
        )
        assertTrue(validateRoutine(tooManyArguments).any { it.code == "too_many_arguments" })

        assertThrows(IllegalArgumentException::class.java) {
            AresRoutineCodec.decode(" ".repeat(AresRoutineCodec.MAX_ROUTINE_JSON_CHARACTERS + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AresRoutineCodec.decode("{".repeat(201))
        }
    }
}
