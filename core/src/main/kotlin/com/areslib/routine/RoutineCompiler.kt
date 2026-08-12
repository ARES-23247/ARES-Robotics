package com.areslib.routine

import com.areslib.action.RobotAction
import com.areslib.sequencer.ParallelDeadlineGroup
import com.areslib.sequencer.ParallelRaceGroup
import com.areslib.sequencer.ParallelTaskGroup
import com.areslib.sequencer.SequentialTaskGroup
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.sequencer.TimeWaitTask
import com.areslib.sequencer.WaitUntilTask
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import kotlin.math.roundToLong

/** Runtime adapters supplied by the generated project catalog and trajectory layer. */
data class RoutineRuntimeBindings(
    val createActionTask: (String, Map<String, String>) -> Task?,
    val createCondition: (String, Map<String, String>) -> ((RobotState) -> Boolean)?,
    val createDriveTask: (RoutineDriveStep) -> Task? = { null },
    val isActionKnown: ((String) -> Boolean)? = null,
    val isConditionKnown: ((String) -> Boolean)? = null,
    val resourcesForAction: (String) -> Set<String> = { emptySet() },
    val resourcesForDrive: (RoutineDriveStep) -> Set<String> = { setOf("drivetrain") }
)

/** Result of compiling a document tree into the existing deterministic task executor. */
data class RoutineCompilationResult(
    val task: Task?,
    val issues: List<RoutineValidationIssue>,
    val resourceKeys: Set<String>
) {
    val isSuccess: Boolean
        get() = task != null && issues.none { it.severity == RoutineValidationSeverity.ERROR }
}

/** Compiles trigger-neutral documents into tasks that produce Redux actions only. */
class RoutineCompiler(
    private val documents: Map<String, RoutineDocument>,
    private val bindings: RoutineRuntimeBindings
) {
    fun compile(routineId: String, executionId: Long): RoutineCompilationResult {
        val document = documents[routineId]
            ?: return RoutineCompilationResult(
                task = null,
                issues = listOf(compileError(routineId, "routine", "missing_routine", "Routine '$routineId' does not exist")),
                resourceKeys = emptySet()
            )
        val context = RoutineValidationContext(
            documents = documents,
            requireResolvedCalls = true,
            hasAction = bindings.isActionKnown,
            hasCondition = bindings.isConditionKnown,
            resourcesForAction = bindings.resourcesForAction,
            resourcesForDrive = bindings.resourcesForDrive
        )
        val issues = validateRoutine(document, context).toMutableList()
        if (issues.any { it.severity == RoutineValidationSeverity.ERROR }) {
            return RoutineCompilationResult(null, issues, resourcesFor(document, mutableSetOf()))
        }

        val task = compileSteps(
            owner = document,
            steps = document.steps,
            parentPath = "steps",
            executionId = executionId,
            callStack = mutableSetOf(document.documentId),
            issues = issues
        )
        return RoutineCompilationResult(
            task = task.takeUnless { issues.any { issue -> issue.severity == RoutineValidationSeverity.ERROR } },
            issues = issues,
            resourceKeys = resourcesFor(document, mutableSetOf())
        )
    }

    private fun compileSteps(
        owner: RoutineDocument,
        steps: List<RoutineStep>,
        parentPath: String,
        executionId: Long,
        callStack: MutableSet<String>,
        issues: MutableList<RoutineValidationIssue>
    ): Task {
        val tasks = steps.mapNotNull { step ->
            compileStep(owner, step, "$parentPath/${step.stepId}", executionId, callStack, issues)
        }
        return SequentialTaskGroup(tasks)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun compileStep(
        owner: RoutineDocument,
        step: RoutineStep,
        path: String,
        executionId: Long,
        callStack: MutableSet<String>,
        issues: MutableList<RoutineValidationIssue>
    ): Task? {
        val compiled = when (step.kind) {
            RoutineStepKind.ACTION -> resolveTask(owner, path, "action", issues) {
                bindings.createActionTask(requireNotNull(step.actionKey), step.arguments)
            }
            RoutineStepKind.DRIVE_TO -> resolveTask(owner, path, "drive", issues) {
                bindings.createDriveTask(requireNotNull(step.drive))
            }
            RoutineStepKind.WAIT -> TimeWaitTask(secondsToMillis(requireNotNull(step.durationSeconds)))
            RoutineStepKind.WAIT_UNTIL -> {
                val predicate = resolveCondition(owner, step, path, issues) ?: return null
                WaitUntilTask(predicate).withTimeout(secondsToMillis(requireNotNull(step.timeoutSeconds)))
            }
            RoutineStepKind.TOGETHER -> ParallelTaskGroup(
                compileChildren(owner, step.children, "$path.children", executionId, callStack, issues)
            )
            RoutineStepKind.FIRST_TO_FINISH -> ParallelRaceGroup(
                compileChildren(owner, step.children, "$path.children", executionId, callStack, issues)
            )
            RoutineStepKind.DEADLINE -> {
                val deadline = compileStep(
                    owner,
                    requireNotNull(step.deadline),
                    "$path.deadline",
                    executionId,
                    callStack,
                    issues
                ) ?: return null
                ParallelDeadlineGroup(
                    deadline,
                    compileChildren(owner, step.children, "$path.children", executionId, callStack, issues)
                )
            }
            RoutineStepKind.CALL -> {
                val calledId = requireNotNull(step.routineId)
                val called = documents[calledId]
                if (called == null) {
                    issues += compileError(owner.documentId, path, "missing_routine", "Routine '$calledId' does not exist")
                    return null
                }
                if (!callStack.add(calledId)) {
                    issues += compileError(owner.documentId, path, "recursive_routine_call", "Routine '$calledId' is recursive")
                    return null
                }
                try {
                    compileSteps(called, called.steps, "routine/$calledId/steps", executionId, callStack, issues)
                } finally {
                    callStack.remove(calledId)
                }
            }
            RoutineStepKind.REPEAT -> {
                val tasks = mutableListOf<Task>()
                repeat(requireNotNull(step.repeatCount)) { repetition ->
                    tasks += compileChildren(
                        owner,
                        step.children,
                        "$path.repeat[$repetition].children",
                        executionId,
                        callStack,
                        issues
                    )
                }
                SequentialTaskGroup(tasks)
            }
            RoutineStepKind.BRANCH -> {
                val predicate = resolveCondition(owner, step, path, issues) ?: return null
                val whenTrue = compileSteps(
                    owner,
                    step.children,
                    "$path.children",
                    executionId,
                    callStack,
                    issues
                )
                val whenFalse = compileSteps(
                    owner,
                    step.elseChildren,
                    "$path.elseChildren",
                    executionId,
                    callStack,
                    issues
                )
                ConditionalRoutineTask(predicate, whenTrue, whenFalse)
            }
        } ?: return null
        return RoutineStepLifecycleTask(executionId, owner.documentId, path, step.kind.name, compiled)
    }

    private fun compileChildren(
        owner: RoutineDocument,
        children: List<RoutineStep>,
        parentPath: String,
        executionId: Long,
        callStack: MutableSet<String>,
        issues: MutableList<RoutineValidationIssue>
    ): List<Task> = children.mapNotNull { child ->
        compileStep(owner, child, "$parentPath/${child.stepId}", executionId, callStack, issues)
    }

    private fun resolveCondition(
        owner: RoutineDocument,
        step: RoutineStep,
        path: String,
        issues: MutableList<RoutineValidationIssue>
    ): ((RobotState) -> Boolean)? {
        val key = requireNotNull(step.conditionKey)
        return try {
            bindings.createCondition(key, step.arguments).also { predicate ->
                if (predicate == null) {
                    issues += compileError(owner.documentId, path, "unknown_condition", "Condition '$key' is not executable")
                }
            }
        } catch (error: RuntimeException) {
            issues += compileError(
                owner.documentId,
                path,
                "condition_factory_failed",
                "Condition '$key' could not be created: ${error.message ?: error::class.simpleName}"
            )
            null
        }
    }

    private inline fun resolveTask(
        owner: RoutineDocument,
        path: String,
        type: String,
        issues: MutableList<RoutineValidationIssue>,
        factory: () -> Task?
    ): Task? = try {
        factory().also { task ->
            if (task == null) {
                issues += compileError(owner.documentId, path, "unknown_${type}_task", "No executable $type is registered")
            }
        }
    } catch (error: RuntimeException) {
        issues += compileError(
            owner.documentId,
            path,
            "${type}_factory_failed",
            "The $type task could not be created: ${error.message ?: error::class.simpleName}"
        )
        null
    }

    private fun resourcesFor(document: RoutineDocument, visited: MutableSet<String>): Set<String> {
        if (!visited.add(document.documentId)) return emptySet()
        return buildSet {
            document.steps.forEach { step -> addAll(resourcesFor(step, visited)) }
        }.also { visited.remove(document.documentId) }
    }

    private fun resourcesFor(step: RoutineStep, visited: MutableSet<String>): Set<String> = buildSet {
        step.actionKey?.let { addAll(bindings.resourcesForAction(it)) }
        step.drive?.let { drive ->
            addAll(bindings.resourcesForDrive(drive))
            drive.markers.forEach { addAll(bindings.resourcesForAction(it.actionKey)) }
            drive.duringActionKeys.forEach { addAll(bindings.resourcesForAction(it)) }
            drive.arrivalActionKeys.forEach { addAll(bindings.resourcesForAction(it)) }
        }
        step.routineId?.let { id -> documents[id]?.let { addAll(resourcesFor(it, visited)) } }
        step.deadline?.let { addAll(resourcesFor(it, visited)) }
        step.children.forEach { addAll(resourcesFor(it, visited)) }
        step.elseChildren.forEach { addAll(resourcesFor(it, visited)) }
    }
}

/** Adds a step-entered action while transparently preserving the wrapped task lifecycle. */
private class RoutineStepLifecycleTask(
    private val executionId: Long,
    private val routineId: String,
    private val stepPath: String,
    private val stepKind: String,
    private val delegate: Task
) : Task {
    override val name: String = "RoutineStep($stepPath:${delegate.name})"

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        val actions = mutableListOf<RobotAction>(
            RobotAction.RoutineStepEntered(
                executionId = executionId,
                routineId = routineId,
                stepPath = stepPath,
                stepKind = stepKind,
                timestampMs = RobotClock.currentTimeMillis()
            )
        )
        actions.addAll(delegate.initialize(state))
        return actions
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        if (TaskStateMachine.getStatus(delegate) == TaskStatus.FAILED) {
            TaskStateMachine.markFailed(this)
            return false
        }
        val completed = delegate.isCompleted(state, elapsedMs)
        if (TaskStateMachine.getStatus(delegate) == TaskStatus.FAILED) {
            TaskStateMachine.markFailed(this)
            return false
        }
        return completed
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        super.execute(state, elapsedMs)
        val actions = delegate.execute(state, elapsedMs)
        if (TaskStateMachine.getStatus(delegate) == TaskStatus.FAILED) {
            TaskStateMachine.markFailed(this)
        }
        return actions
    }

    override fun pause(state: RobotState): List<RobotAction> = delegate.pause(state)
    override fun resume(state: RobotState): List<RobotAction> = delegate.resume(state)

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val delegateFailed = TaskStateMachine.getStatus(delegate) == TaskStatus.FAILED
        val actions = delegate.end(state, interrupted || delegateFailed)
        delegate.releaseRuntimeState()
        super.end(state, interrupted || delegateFailed)
        return actions
    }

    override fun releaseRuntimeState() {
        delegate.releaseRuntimeState()
        super.releaseRuntimeState()
    }
}

/** Chooses exactly one precompiled branch from the state snapshot observed at initialization. */
private class ConditionalRoutineTask(
    private val predicate: (RobotState) -> Boolean,
    private val whenTrue: Task,
    private val whenFalse: Task
) : Task {
    override val name: String = "RoutineBranch"
    private var selected: Task? = null

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        selected = if (predicate(state)) whenTrue else whenFalse
        return selected!!.initialize(state)
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean {
        val task = checkNotNull(selected) { "Branch was not initialized" }
        if (TaskStateMachine.getStatus(task) == TaskStatus.FAILED) {
            TaskStateMachine.markFailed(this)
            return false
        }
        val completed = task.isCompleted(state, elapsedMs)
        if (TaskStateMachine.getStatus(task) == TaskStatus.FAILED) {
            TaskStateMachine.markFailed(this)
            return false
        }
        return completed
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> =
        checkNotNull(selected) { "Branch was not initialized" }.execute(state, elapsedMs)

    override fun pause(state: RobotState): List<RobotAction> = selected?.pause(state).orEmpty()
    override fun resume(state: RobotState): List<RobotAction> = selected?.resume(state).orEmpty()

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        val task = selected
        val failed = task != null && TaskStateMachine.getStatus(task) == TaskStatus.FAILED
        val actions = task?.end(state, interrupted || failed).orEmpty()
        task?.releaseRuntimeState()
        super.end(state, interrupted || failed)
        selected = null
        return actions
    }

    override fun releaseRuntimeState() {
        selected?.releaseRuntimeState()
        super.releaseRuntimeState()
    }
}

private fun compileError(
    documentId: String,
    path: String,
    code: String,
    message: String
): RoutineValidationIssue = RoutineValidationIssue(
    RoutineValidationSeverity.ERROR,
    documentId,
    path,
    code,
    message
)

private fun secondsToMillis(seconds: Double): Long = (seconds * 1_000.0).roundToLong()
