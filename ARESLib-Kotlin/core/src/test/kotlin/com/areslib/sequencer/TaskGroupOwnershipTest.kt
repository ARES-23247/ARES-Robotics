package com.areslib.sequencer

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import java.util.LinkedList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class TaskGroupOwnershipTest {
    private val state = RobotState()
    private val owned = mutableListOf<Task>()
    @AfterEach fun cleanup() { owned.forEach { it.reset() } }
    private fun <T : Task> own(task: T): T = task.also { owned.add(it) }
    private open class Probe(val finishAt: Long = 1, override val requiredResources: Long = 0) : Task {
        override val name = "ownership-probe"
        var starts = 0
        var ticks = 0
        val endings = mutableListOf<Boolean>()
        override fun initialize(state: RobotState): List<RobotAction> { starts++; return super.initialize(state) }
        override fun isCompleted(state: RobotState, elapsedMs: Long) = elapsedMs >= finishAt
        override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> { ticks++; return super.execute(state, elapsedMs) }
        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> { endings.add(interrupted); return super.end(state, interrupted) }
    }
    private class EqualProbe(finishAt: Long) : Probe(finishAt) {
        var hash = 1
        override fun equals(other: Any?) = other is EqualProbe
        override fun hashCode() = hash
    }
    private val factories = listOf<(List<Task>) -> Task>(
        { SequentialTaskGroup(it) }, { ParallelTaskGroup(it) }, { ParallelRaceGroup(it) },
        { ParallelDeadlineGroup(it.first(), it.drop(1)) }
    )

    @Test fun `parallel completes equal but distinct children independently`() {
        val first = own(EqualProbe(1)); val second = own(EqualProbe(2))
        val group = own(ParallelTaskGroup(listOf(first, second)))
        group.initialize(state)
        assertFalse(group.isCompleted(state, 1))
        group.execute(state, 1)
        assertEquals(1, second.ticks)
        assertTrue(group.isCompleted(state, 2))
        assertEquals(listOf(false), first.endings)
        assertEquals(listOf(false), second.endings)
        group.end(state, false)
    }

    @Test fun `equal companion cannot count as completed deadline`() {
        val deadline = own(EqualProbe(2)); val other = own(EqualProbe(1))
        val group = own(ParallelDeadlineGroup(deadline, listOf(other)))
        group.initialize(state)
        assertFalse(group.isCompleted(state, 1))
        assertTrue(group.isCompleted(state, 2))
        group.end(state, false)
        assertEquals(listOf(false), deadline.endings)
    }

    @Test fun `mutating task hash after completion cannot repeat its end`() {
        val first = own(EqualProbe(1)); val second = own(Probe(100))
        val group = own(ParallelTaskGroup(listOf(first, second)))
        group.initialize(state); assertFalse(group.isCompleted(state, 1))
        first.hash = 2
        assertFalse(group.isCompleted(state, 2))
        assertEquals(listOf(false), first.endings)
        group.end(state, true)
    }

    @Test fun `sequential rejects repeated task identity`() { repeated(factories[0]) }
    @Test fun `parallel rejects repeated zero-resource task identity`() { repeated(factories[1]) }
    @Test fun `race rejects repeated zero-resource task identity`() { repeated(factories[2]) }
    @Test fun `deadline rejects repeated zero-resource task identity`() { repeated(factories[3]) }
    private fun repeated(factory: (List<Task>) -> Task) {
        val p = own(Probe())
        assertFailsWith<IllegalArgumentException> { factory(listOf(p, p)) }
        assertEquals(0, p.starts)
    }

    @Test fun `nested groups cannot share a leaf instance`() {
        for (factory in factories) {
            for (nestedFactory in factories) {
                val leaf = own(Probe())
                val left = own(nestedFactory(listOf(leaf)))
                val right = own(SequentialTaskGroup(listOf(leaf)))
                assertFailsWith<IllegalArgumentException> { factory(listOf(left, right)) }
            }
        }
    }

    @Test fun `groups own membership when the caller replaces list contents`() {
        for (factory in factories) {
            val first = own(Probe(100, TaskResources.DRIVE))
            val second = own(Probe(100, TaskResources.INTAKE))
            val replacement = own(Probe(100, TaskResources.DRIVE))
            val input = arrayListOf<Task>(first, second)
            val group = own(factory(input))
            val name = group.name
            input.clear(); input.add(replacement)
            group.initialize(state)
            assertEquals(1, first.starts)
            assertEquals(0, replacement.starts)
            group.end(state, true)
            assertEquals(listOf(true), first.endings)
            assertEquals(name, group.name)
            assertEquals(TaskResources.DRIVE or TaskResources.INTAKE, group.requiredResources)
        }
    }

    @Test fun `clearing caller list after initialize cannot omit cleanup`() {
        for (factory in factories) {
            val first = own(Probe(100)); val input = arrayListOf<Task>(first)
            val group = own(factory(input))
            group.initialize(state); input.clear(); group.end(state, true)
            assertEquals(listOf(true), first.endings)
        }
    }

    @Test fun `linked input is not indexed again by runtime group traversal`() {
        val input = object : LinkedList<Task>() {
            var indexedReads = 0
            override fun get(index: Int): Task { indexedReads++; return super.get(index) }
        }
        repeat(100) { input.add(own(Probe(100))) }
        val group = own(ParallelTaskGroup(input))
        input.indexedReads = 0
        group.initialize(state)
        repeat(20) { group.isCompleted(state, 0); group.execute(state, 0) }
        group.end(state, true)
        assertEquals(0, input.indexedReads)
    }

    @Test fun `resource mask is read once per child at construction`() {
        var reads = 0
        val p = own(object : Probe() {
            override val requiredResources: Long get() { reads++; return TaskResources.DRIVE }
        })
        val group = own(ParallelTaskGroup(listOf(p)))
        assertEquals(1, reads)
        assertEquals(TaskResources.DRIVE, group.requiredResources)
        group.initialize(state); group.execute(state, 0); group.end(state, true)
        assertEquals(1, reads)
    }

    @Test fun `nested resource unions still reject parallel conflicts but allow sequential sharing`() {
        val first = own(Probe(1, TaskResources.DRIVE)); val second = own(Probe(1, TaskResources.DRIVE))
        val sequential = own(SequentialTaskGroup(listOf(first, second)))
        assertEquals(TaskResources.DRIVE, sequential.requiredResources)
        val other = own(Probe(1, TaskResources.DRIVE))
        val failure = assertFailsWith<IllegalArgumentException> { ParallelTaskGroup(listOf(sequential, other)) }
        assertTrue(failure.message.orEmpty().contains("drive"))
        val intake = own(Probe(1, TaskResources.INTAKE))
        assertEquals(TaskResources.DRIVE or TaskResources.INTAKE,
            own(ParallelTaskGroup(listOf(sequential, intake))).requiredResources)
    }

    @Test fun `owned child snapshots cannot be mutated through internal access`() {
        for (factory in factories) {
            val group = own(factory(listOf(own(Probe()))))
            val children = when (group) {
                is SequentialTaskGroup -> group.tasks
                is ParallelTaskGroup -> group.tasks
                is ParallelRaceGroup -> group.tasks
                is ParallelDeadlineGroup -> group.tasks
                else -> error("unexpected group")
            }
            assertFailsWith<UnsupportedOperationException> { (children as MutableList<Task>).clear() }
        }
    }

    @Test fun `reinitializing a completed group clears identity completion bookkeeping`() {
        for (factory in factories) {
            val first = own(Probe(0)); val second = own(Probe(0))
            val group = own(factory(listOf(first, second)))
            repeat(2) {
                group.initialize(state)
                assertTrue(group.isCompleted(state, 0))
                group.end(state, false)
            }
            assertEquals(2, first.starts)
            assertEquals(2, first.endings.size)
            assertEquals(2, second.starts)
            assertEquals(2, second.endings.size)
        }
    }

    @Test fun `ownership never calls user equality or hash methods`() {
        fun create() = own(object : Probe(0) {
            override fun hashCode(): Int = error("user hash must not run")
            override fun equals(other: Any?): Boolean = error("user equality must not run")
        })
        val group = own(ParallelTaskGroup(listOf(create(), create())))
        group.initialize(state)
        assertTrue(group.isCompleted(state, 0))
        group.end(state, false)
    }

    @Test fun `sequence DSL rejects reused task instances across nested branches`() {
        val shared = own(Probe())
        assertFailsWith<IllegalArgumentException> {
            robotSequence {
                sequence { task(shared) }
                parallel { task(shared) }
            }
        }
    }
}
