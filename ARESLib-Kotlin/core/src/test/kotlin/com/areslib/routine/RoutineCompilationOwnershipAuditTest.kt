package com.areslib.routine

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.sequencer.*
import com.areslib.state.RobotState
import com.areslib.state.RoutineExecutionStatus
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RoutineCompilationOwnershipAuditTest {
    private val created = mutableListOf<Task>()
    @BeforeEach fun setClock() { RobotClock.useMockTime(1_000L) }
    @AfterEach fun releaseTasks() {
        created.forEach { TaskTimeoutManager.reset(it); TaskCallbacks.reset(it); TaskStateMachine.reset(it) }
        RobotClock.useSystemTime()
    }

    @Test fun numericDefaultsMustPassTheSameFiniteAndRangeChecksAsExplicitValues() {
        val reader = CapabilityArgumentReader("arm.move", emptyMap(), setOf("height"))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 2.1)) {
            assertThrows(IllegalArgumentException::class.java) {
                reader.requiredNumber("height", invalid, minimum = 0.0, maximum = 2.0)
            }
        }
        assertEquals(1.0, reader.optionalNumber("height", 1.0, 0.0, 2.0))
        assertNull(reader.optionalNumber("height", minimum = 0.0, maximum = 2.0))
    }

    @Test fun invalidNumericBoundsAreRejectedEvenWhenTheOptionalArgumentIsAbsent() {
        val reader = CapabilityArgumentReader("arm.move", emptyMap(), setOf("height"))
        for ((minimum, maximum) in listOf(Double.NaN to 2.0, 0.0 to Double.POSITIVE_INFINITY, 2.0 to 1.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                reader.optionalNumber("height", minimum = minimum, maximum = maximum)
            }
        }
    }

    @Test fun anExplicitEmptyOtherwiseBranchStillCountsAsADeclaration() {
        assertThrows(IllegalStateException::class.java) {
            routine("duplicate-branch", "Duplicate branch") {
                branch("ready") {
                    then { waitSeconds(0.0) }
                    otherwise { }
                    otherwise { waitSeconds(1.0) }
                }
            }
        }
    }

    @Test fun failedCompilationReleasesCallbacksOnPreviouslyCreatedTasks() {
        var callbacks = 0
        val first = leaf().onComplete { callbacks++ }
        val result = compile(routine("failure", "Failure") { action("first"); action("missing") }) { key ->
            if (key == "first") first else null
        }
        assertFalse(result.isSuccess)
        TaskCallbacks.invokeComplete(first)
        assertEquals(0, callbacks, "Rejected compilation retained the first task's callback")
    }

    @Test fun cancellingBeforeInitializationReleasesEveryCompiledBranch() {
        var callbacks = 0
        val leaves = mutableListOf<Task>()
        val document = routine("queued", "Queued") {
            branch("ready") {
                then { action("true") }
                otherwise { action("false") }
            }
            action("later")
        }
        val compiled = requireNotNull(compile(document) {
            leaf().onComplete { callbacks++ }.also(leaves::add)
        }.task)
        compiled.releaseRuntimeState()
        leaves.forEach(TaskCallbacks::invokeComplete)
        assertEquals(0, callbacks, "Unstarted tasks remained in the callback registry")
    }

    @Test fun completingOneBranchReleasesTheUnselectedBranchWithoutExecutingIt() {
        var unusedStarted = 0
        var unusedCallbacks = 0
        lateinit var unused: Task
        val result = compile(routine("choose", "Choose") {
            branch("ready") {
                then { action("selected") }
                otherwise { action("unused") }
            }
        }) { key ->
            if (key == "unused") leaf(onInitialize = { unusedStarted++ }).onComplete { unusedCallbacks++ }.also { unused = it }
            else leaf()
        }
        val executor = TaskExecutor(); executor.addTask(requireNotNull(result.task))
        executor.update(RobotState(), 1_000L)
        assertEquals(0, executor.size)
        assertEquals(0, unusedStarted)
        TaskCallbacks.invokeComplete(unused)
        assertEquals(0, unusedCallbacks)
    }

    @Test fun aChildFailureDuringNormalEndCannotStartTheNextStep() {
        var nextStarted = 0
        val store = Store()
        val manager = manager(store) { key ->
            if (key == "first") leaf(onEnd = { task, _ -> TaskStateMachine.markFailed(task) })
            else leaf(onInitialize = { nextStarted++ })
        }
        manager.register(routine("end-failure", "End failure") { action("first"); action("next") })
        manager.request("end-failure"); manager.update()
        assertEquals(0, nextStarted)
        assertEquals(RoutineExecutionStatus.FAILED, store.state.routineState.lastTerminalExecution?.status)
    }

    @Test fun aCancelledChildCannotBeReportedAsSuccessfulOrAdvanceTheSequence() {
        var nextStarted = 0
        val store = Store()
        val manager = manager(store) { key ->
            if (key == "cancel") leaf(onCheck = { task -> TaskStateMachine.transitionTo(task, TaskStatus.CANCELLED); true })
            else leaf(onInitialize = { nextStarted++ })
        }
        manager.register(routine("cancelled", "Cancelled") { action("cancel"); action("next") })
        manager.request("cancelled"); manager.update()
        assertEquals(0, nextStarted)
        assertEquals(RoutineExecutionStatus.CANCELLED, store.state.routineState.lastTerminalExecution?.status)
    }

    @Test fun nestedBranchExecutionFailureIsReportedInTheSameUpdate() {
        val store = Store()
        val manager = manager(store) {
            leaf(onCheck = { false }, onExecute = { TaskStateMachine.markFailed(it) })
        }
        manager.register(routine("nested", "Nested") {
            branch("ready") { then { action("fail") } }
        })
        manager.request("nested"); manager.update()
        assertEquals(0, manager.activeCount)
        assertEquals(RoutineExecutionStatus.FAILED, store.state.routineState.lastTerminalExecution?.status)
    }

    @Test fun elapsedTimeoutIsCheckedBeforeTheDelegatesCompletionPredicate() {
        var checks = 0
        val task = leaf(onCheck = { checks++; checks > 1 }).withTimeout(10L)
        val compiled = requireNotNull(compile(routine("timed", "Timed") { action("timed") }) { task }.task)
        val executor = TaskExecutor(); executor.addTask(compiled)
        executor.update(RobotState(), 1_000L)
        // The explicit executor clock advances while RobotClock stays fixed: no watchdog race.
        executor.update(RobotState(), 1_011L)
        assertEquals(1, checks)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(compiled))
    }

    @Test fun failureCallbacksSurvivePropagationUntilTheyAreDeliveredExactlyOnce() {
        var failures = 0
        val store = Store()
        val manager = manager(store) {
            leaf(onCheck = { task -> TaskStateMachine.markFailed(task); false }).onFail { failures++ }
        }
        manager.register(routine("callback", "Callback") { action("fail") })
        manager.request("callback"); manager.update(); manager.update()
        assertEquals(1, failures)
        assertEquals(RoutineExecutionStatus.FAILED, store.state.routineState.lastTerminalExecution?.status)
    }

    @Test fun factoriesMustReturnFreshTasksForRepeatedSteps() {
        val shared = leaf()
        val result = compile(routine("aliased", "Aliased") { repeatTimes(2) { action("same") } }) { shared }
        assertFalse(result.isSuccess, "Lifecycle wrappers concealed an aliased task from tree validation")
        assertNull(result.task)
    }

    @Test fun taskConstructionFailureBecomesADiagnosticAndReleasesEarlierFactoryTasks() {
        var callbacks = 0
        val first = leaf().onComplete { callbacks++ }
        val broken = object : Task {
            override val name: String get() = error("broken task metadata")
            override fun isCompleted(state: RobotState, elapsedMs: Long) = true
        }.also(created::add)
        val result = assertDoesNotThrow<RoutineCompilationResult> {
            compile(routine("construction", "Construction") { action("first"); action("broken") }) {
                if (it == "first") first else broken
            }
        }
        assertFalse(result.isSuccess)
        TaskCallbacks.invokeComplete(first)
        assertEquals(0, callbacks)
    }

    @Test fun aThrowingFailureCallbackDoesNotDiscardTheReturnedNeutralAction() {
        val store = Store()
        store.dispatch(RobotAction.SetIndicatorLight("status", 0.8, 1_000L))
        val failing = object : Task {
            override val name = "Callback failure"
            override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
                TaskStateMachine.markFailed(this); return false
            }
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                super.end(state, interrupted)
                return listOf(RobotAction.SetIndicatorLight("status", 0.0, 1_000L))
            }
        }.also(created::add).onFail { error("diagnostic callback failed") }
        val manager = manager(store) { failing }
        manager.register(routine("throwing-callback", "Throwing callback") { action("fail") })
        manager.request("throwing-callback"); manager.update()
        assertEquals(0.0, store.state.superstructure.indicatorLights["status"])
        assertEquals(RoutineExecutionStatus.FAILED, store.state.routineState.lastTerminalExecution?.status)
    }

    @Test fun metadataCleanupFailureStillReleasesOtherBranchesAndPreservesNeutralActions() {
        var callbacks = 0
        lateinit var unused: Task
        val brokenCleanup = object : Task {
            override val name = "Broken metadata cleanup"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = true
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                super.end(state, interrupted)
                return listOf(RobotAction.SetIndicatorLight("status", 0.0, 1_000L))
            }
            override fun releaseRuntimeState() { error("metadata release failed") }
        }.also(created::add)
        val store = Store(); store.dispatch(RobotAction.SetIndicatorLight("status", 0.8, 1_000L))
        val manager = manager(store) { key ->
            if (key == "selected") brokenCleanup else leaf().onComplete { callbacks++ }.also { unused = it }
        }
        manager.register(routine("cleanup", "Cleanup") {
            branch("ready") {
                then { action("selected") }
                otherwise { action("unused") }
            }
        })
        manager.request("cleanup"); assertDoesNotThrow { manager.update() }
        assertEquals(0.0, store.state.superstructure.indicatorLights["status"])
        assertEquals(RoutineExecutionStatus.FAILED, store.state.routineState.lastTerminalExecution?.status)
        TaskCallbacks.invokeComplete(unused)
        assertEquals(0, callbacks)
    }

    @Test fun aFactoryCannotTransferAnotherExecutorsRunningTaskOrEraseItsCallbacks() {
        var callback = 0
        val running = leaf(onCheck = { false }).onComplete { callback++ }
        running.initialize(RobotState())
        val result = compile(routine("already-owned", "Already owned") { action("running") }) { running }
        assertFalse(result.isSuccess)
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(running))
        TaskCallbacks.invokeComplete(running)
        assertEquals(1, callback)
    }

    @Test fun pauseFreezesNestedDeadlinesAndDoesNotInitializeOrResumeDormantBranches() {
        var pauses = 0; var resumes = 0; var unusedStarts = 0
        val selected = object : Task {
            override val name = "Pause probe"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = false
            override fun pause(state: RobotState): List<RobotAction> { pauses++; return emptyList() }
            override fun resume(state: RobotState): List<RobotAction> { resumes++; return emptyList() }
        }.also(created::add).withTimeout(10L)
        val result = compile(routine("pause", "Pause") {
            branch("ready") {
                then { action("selected") }
                otherwise { action("unused") }
            }
        }) { key -> if (key == "selected") selected else leaf(onInitialize = { unusedStarts++ }) }
        val compiled = requireNotNull(result.task)
        compiled.initialize(RobotState()); compiled.isCompleted(RobotState(), 0L)
        compiled.pause(RobotState())
        RobotClock.useMockTime(2_000L); TaskTimeoutManager.runWatchdogCheck(2_000L)
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(selected))
        compiled.resume(RobotState())
        RobotClock.useMockTime(2_005L); TaskTimeoutManager.runWatchdogCheck(2_005L)
        assertEquals(TaskStatus.RUNNING, TaskStateMachine.getStatus(selected))
        assertEquals(1, pauses); assertEquals(1, resumes); assertEquals(0, unusedStarts)
        compiled.end(RobotState(), true); compiled.releaseRuntimeState()
    }

    @Test fun factoryBuiltInTreesCannotShareAChildAcrossDifferentRoutineSteps() {
        val shared = leaf()
        val result = compile(routine("hidden-alias", "Hidden alias") { action("one"); action("two") }) {
            SequentialTaskGroup(listOf(shared))
        }
        assertFalse(result.isSuccess)
    }

    @Test fun distinctEqualTasksRetainSeparateOwnershipAndCleanup() {
        var releases = 0
        class EqualTask : Task {
            override val name = "Equal"
            override fun isCompleted(state: RobotState, elapsedMs: Long) = true
            override fun equals(other: Any?) = other is EqualTask
            override fun hashCode() = 0
            override fun releaseRuntimeState() { releases++; super.releaseRuntimeState() }
        }
        val result = compile(routine("equal", "Equal") { action("one"); action("two") }) {
            EqualTask().also(created::add)
        }
        assertTrue(result.isSuccess)
        requireNotNull(result.task).releaseRuntimeState()
        assertEquals(2, releases)
        result.task.releaseRuntimeState()
        assertEquals(2, releases)
    }

    private fun manager(store: Store, factory: (String) -> Task?): RoutineManager = RoutineManager(
        bindings(factory), { store.state }, store::dispatch
    )

    private fun compile(document: RoutineDocument, factory: (String) -> Task?): RoutineCompilationResult =
        RoutineCompiler(mapOf(document.documentId to document), bindings(factory)).compile(document.documentId, 1L)

    private fun bindings(factory: (String) -> Task?) = RoutineRuntimeBindings(
        createActionTask = { key, _ -> factory(key) }, createCondition = { _, _ -> { _ -> true } }
    )

    private fun leaf(
        onInitialize: () -> Unit = {},
        onCheck: (Task) -> Boolean = { true },
        onExecute: (Task) -> Unit = {},
        onEnd: (Task, Boolean) -> Unit = { _, _ -> }
    ): Task = object : Task {
        override val name = "Probe"
        override fun initialize(state: RobotState): List<RobotAction> {
            super.initialize(state); onInitialize(); return emptyList()
        }
        override fun isCompleted(state: RobotState, elapsedMs: Long) = onCheck(this)
        override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
            super.execute(state, elapsedMs); onExecute(this); return emptyList()
        }
        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
            onEnd(this, interrupted); return super.end(state, interrupted)
        }
    }.also(created::add)
}
