package com.ares.analytics.viewmodel.routine

import com.areslib.routine.*
import kotlin.test.*

class RoutinePreviewAnalyzerAuditTest {
    private fun root(steps: List<RoutineStep>, id: String = "root") = RoutineDocument(documentId = id, name = id, steps = steps)
    private fun repeat(count: Int, children: List<RoutineStep>) = RoutineStep.wait(0.0).copy(
        kind = RoutineStepKind.REPEAT, repeatCount = count, children = children, durationSeconds = null)
    private fun call(id: String) = RoutineStep.wait(0.0).copy(kind = RoutineStepKind.CALL, routineId = id, durationSeconds = null)

    @Test fun `invalid nested duration yields a warning and no partial executable preview`() {
        val steps = listOf(RoutineStep.wait(0.25), repeat(2, listOf(RoutineStep.wait(Double.NaN))))
        val result = analyzeRoutinePreview(root(steps), emptyList())
        assertNotNull(result.warning)
        assertTrue(result.steps.isEmpty())
        assertTrue(result.drives.isEmpty())
    }

    @Test fun `ambiguous called routine identity cannot silently choose the last body`() {
        val result = analyzeRoutinePreview(root(listOf(call("child"))),
            listOf(root(listOf(RoutineStep.wait(1.0)), "child"), root(listOf(RoutineStep.wait(2.0)), "child")))
        assertNotNull(result.warning)
        assertTrue(result.steps.isEmpty())
    }

    @Test fun `call cycles discard previously expanded steps`() {
        val result = analyzeRoutinePreview(root(listOf(RoutineStep.wait(0.25), call("child"))), listOf(root(listOf(call("root")), "child")))
        assertNotNull(result.warning)
        assertTrue(result.steps.isEmpty())
    }

    @Test fun `repeat and called bodies expand in deterministic serial order`() {
        val first = RoutineStep.action("first")
        val body = listOf(RoutineStep.wait(0.25), RoutineStep.action("body"))
        val result = analyzeRoutinePreview(root(listOf(first, repeat(2, listOf(call("child"))))), listOf(root(body, "child")))
        assertNull(result.warning)
        assertEquals(listOf(first) + body + body, result.steps)
    }

    @Test fun `empty repeats are bounded and zero repeats skip invalid inactive bodies`() {
        assertNull(analyzeRoutinePreview(root(listOf(repeat(4096, emptyList()))), emptyList()).warning)
        val zero = analyzeRoutinePreview(root(listOf(repeat(0, listOf(call("missing"))))), emptyList())
        assertNull(zero.warning)
        assertTrue(zero.steps.isEmpty())
        assertNotNull(analyzeRoutinePreview(root(listOf(repeat(4097, emptyList()))), emptyList()).warning)
    }
    @Test fun `expansion and depth limits admit the boundary and reject excess without partial results`() {
        val atLimit = analyzeRoutinePreview(root(List(4096) { RoutineStep.wait(0.0) }), emptyList())
        assertNull(atLimit.warning)
        assertEquals(4096, atLimit.steps.size)
        val tooMany = analyzeRoutinePreview(root(List(4097) { RoutineStep.wait(0.0) }), emptyList())
        assertNotNull(tooMany.warning)
        assertTrue(tooMany.steps.isEmpty())
        fun nested(depth: Int): List<RoutineStep> = (0 until depth).fold(listOf(RoutineStep.wait(0.0))) { body, _ ->
            listOf(repeat(1, body))
        }
        assertNull(analyzeRoutinePreview(root(nested(64)), emptyList()).warning)
        val tooDeep = analyzeRoutinePreview(root(nested(65)), emptyList())
        assertNotNull(tooDeep.warning)
        assertTrue(tooDeep.steps.isEmpty())
    }

    @Test fun `unreferenced duplicate routines and saved root copies do not affect the edited body`() {
        val edited = root(listOf(RoutineStep.wait(0.75)))
        val result = analyzeRoutinePreview(edited, listOf(root(listOf(call("missing"))), root(emptyList(), "unused"), root(emptyList(), "unused")))
        assertNull(result.warning)
        assertEquals(edited.steps, result.steps)
    }

}
