package com.areslib.routine

import com.areslib.auto.autonomous
import com.areslib.auto.degrees
import com.areslib.auto.meters
import com.areslib.pathing.CommandKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

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
    fun `legacy auto migration separates autonomous starting pose without losing steps`() {
        val legacy = autonomous("Left Auto") {
            startAt(1.0.meters, 2.0.meters, 90.degrees)
            run(CommandKey("intake.collect"))
            together {
                waitFor(250.milliseconds)
                run(CommandKey("shooter.prepare"))
            }
        }

        val migration = migrateAutoRoutine(legacy)

        assertEquals("left-auto", migration.document.documentId)
        assertEquals(1.0, migration.entryPoint.startingPose.xMeters)
        assertEquals(2.0, migration.entryPoint.startingPose.yMeters)
        assertEquals(Math.PI / 2.0, migration.entryPoint.startingPose.headingRadians, 1e-9)
        assertEquals(RoutineStepKind.ACTION, migration.document.steps.first().kind)
        assertEquals(RoutineStepKind.TOGETHER, migration.document.steps.last().kind)
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
}
