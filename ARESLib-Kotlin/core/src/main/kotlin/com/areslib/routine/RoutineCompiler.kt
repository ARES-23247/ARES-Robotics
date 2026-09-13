package com.areslib.routine

import com.areslib.action.RobotAction
import com.areslib.sequencer.ParallelDeadlineGroup
import com.areslib.sequencer.ParallelRaceGroup
import com.areslib.sequencer.ParallelTaskGroup
import com.areslib.sequencer.SequentialTaskGroup
import com.areslib.sequencer.Task
import com.areslib.sequencer.TimeWaitTask
import com.areslib.sequencer.WaitUntilTask
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import java.util.ArrayDeque
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

/**
 * Compiles a validated reachable routine tree into one owned task invocation.
 * Factories must return fresh, unstarted tasks and perform no hardware work. Compilation owns
 * callback/timeout cleanup even for rejected trees, unselected branches and queued cancellation.
 * Documents and binding inputs must remain stable during compilation. Run and cancel the result
 * through the robot's existing lifecycle owner; compilation does not start another control loop.
 */
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
        val ownership = RoutineTaskOwnership()
        val issues = mutableListOf<RoutineValidationIssue>()
        var resources: Set<String> = emptySet()
        var result: Task? = null
        try {
            val context = RoutineValidationContext(
                documents = documents,
                requireResolvedCalls = true,
                hasAction = bindings.isActionKnown,
                hasCondition = bindings.isConditionKnown,
                resourcesForAction = bindings.resourcesForAction,
                resourcesForDrive = bindings.resourcesForDrive
            )
            validateReachable(document, context, issues)
            if (issues.none { it.severity == RoutineValidationSeverity.ERROR }) {
                // Resolve claims before factories acquire runtime metadata.
                resources = resourcesFor(document, mutableSetOf())
                val tree = compileSteps(
                    owner = document, steps = document.steps, parentPath = "steps",
                    executionId = executionId, callStack = mutableSetOf(document.documentId),
                    issues = issues, ownership = ownership
                )
                if (issues.none { it.severity == RoutineValidationSeverity.ERROR }) {
                    result = CompiledRoutineTask(tree, ownership)
                }
            }
        } catch (failure: RuntimeException) {
            issues += compileError(document.documentId, "routine", "task_compilation_failed",
                "Routine tasks could not be compiled: ${failure.message ?: failure::class.simpleName}")
        } finally {
            if (result == null) {
                try { ownership.releaseAll() } catch (failure: RuntimeException) {
                    issues += compileError(document.documentId, "routine", "task_cleanup_failed",
                        "Rejected routine metadata cleanup failed: ${failure.message ?: failure::class.simpleName}")
                }
            }
        }
        return RoutineCompilationResult(result, issues, resources)
    }

    /** Validate only reachable documents, once each, before invoking any task factory. */
    private fun validateReachable(
        root: RoutineDocument,
        context: RoutineValidationContext,
        issues: MutableList<RoutineValidationIssue>
    ) {
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<RoutineDocument>().also { it.add(root) }
        while (pending.isNotEmpty()) {
            val document = pending.removeFirst()
            if (!visited.add(document.documentId)) continue
            val diagnostics = validateRoutine(document, context)
            issues.addAll(diagnostics)
            if (diagnostics.any { it.severity == RoutineValidationSeverity.ERROR }) continue
            val steps = ArrayDeque(document.steps)
            while (steps.isNotEmpty()) {
                val step = steps.removeFirst()
                step.routineId?.let { documents[it]?.let(pending::addLast) }
                step.deadline?.let(steps::addLast)
                steps.addAll(step.children)
                steps.addAll(step.elseChildren)
            }
        }
    }

    private fun compileSteps(
        owner: RoutineDocument,
        steps: List<RoutineStep>,
        parentPath: String,
        executionId: Long,
        callStack: MutableSet<String>,
        issues: MutableList<RoutineValidationIssue>,
        ownership: RoutineTaskOwnership
    ): Task {
        val tasks = steps.mapNotNull { step ->
            compileStep(owner, step, "$parentPath/${step.stepId}", executionId, callStack, issues, ownership)
        }
        return ownership.internalNode(SequentialTaskGroup(tasks))
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun compileStep(
        owner: RoutineDocument,
        step: RoutineStep,
        path: String,
        executionId: Long,
        callStack: MutableSet<String>,
        issues: MutableList<RoutineValidationIssue>,
        ownership: RoutineTaskOwnership
    ): Task? {
        val compiled = when (step.kind) {
            RoutineStepKind.ACTION -> resolveTask(owner, path, "action", issues, ownership) {
                bindings.createActionTask(requireNotNull(step.actionKey), step.arguments)
            }
            RoutineStepKind.DRIVE_TO -> resolveTask(owner, path, "drive", issues, ownership) {
                bindings.createDriveTask(requireNotNull(step.drive))
            }
            RoutineStepKind.WAIT -> ownership.acquire(TimeWaitTask(secondsToMillis(requireNotNull(step.durationSeconds))))
            RoutineStepKind.WAIT_UNTIL -> {
                val predicate = resolveCondition(owner, step, path, issues) ?: return null
                ownership.acquire(WaitUntilTask(predicate).withTimeout(secondsToMillis(requireNotNull(step.timeoutSeconds))))
            }
            RoutineStepKind.TOGETHER -> ParallelTaskGroup(
                compileChildren(owner, step.children, "$path.children", executionId, callStack, issues, ownership)
            )
            RoutineStepKind.FIRST_TO_FINISH -> ParallelRaceGroup(
                compileChildren(owner, step.children, "$path.children", executionId, callStack, issues, ownership)
            )
            RoutineStepKind.DEADLINE -> {
                val deadline = compileStep(
                    owner,
                    requireNotNull(step.deadline),
                    "$path.deadline",
                    executionId,
                    callStack,
                    issues, ownership
                ) ?: return null
                ParallelDeadlineGroup(
                    deadline,
                    compileChildren(owner, step.children, "$path.children", executionId, callStack, issues, ownership)
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
                    compileSteps(called, called.steps, "routine/$calledId/steps", executionId, callStack, issues, ownership)
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
                        issues, ownership
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
                    issues, ownership
                )
                val whenFalse = compileSteps(
                    owner,
                    step.elseChildren,
                    "$path.elseChildren",
                    executionId,
                    callStack,
                    issues, ownership
                )
                ConditionalRoutineTask(predicate, whenTrue, whenFalse, ownership)
            }
        } ?: return null
        ownership.internalNode(compiled)
        return ownership.register(RoutineStepLifecycleTask(executionId, owner.documentId, path, step.kind.name, compiled, ownership))
    }

    private fun compileChildren(
        owner: RoutineDocument,
        children: List<RoutineStep>,
        parentPath: String,
        executionId: Long,
        callStack: MutableSet<String>,
        issues: MutableList<RoutineValidationIssue>,
        ownership: RoutineTaskOwnership
    ): List<Task> = children.mapNotNull { child ->
        compileStep(owner, child, "$parentPath/${child.stepId}", executionId, callStack, issues, ownership)
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
        ownership: RoutineTaskOwnership,
        factory: () -> Task?
    ): Task? = try {
        factory().also { task ->
            if (task == null) {
                issues += compileError(owner.documentId, path, "unknown_${type}_task", "No executable $type is registered")
            } else {
                ownership.acquire(task)
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

/** Adds step telemetry without weakening the child's task lifecycle. */
private class RoutineStepLifecycleTask(
    private val executionId: Long,
    private val routineId: String,
    private val stepPath: String,
    private val stepKind: String,
    override val delegate: Task,
    ownership: RoutineTaskOwnership
) : RoutineTaskWrapper(ownership) {
    override val name: String = "RoutineStep($stepPath:${delegate.name})"
    override val requiredResources: Long = delegate.requiredResources

    override fun initialize(state: RobotState): List<RobotAction> {
        val actions = mutableListOf<RobotAction>(
            RobotAction.RoutineStepEntered(executionId, routineId, stepPath, stepKind, RobotClock.currentTimeMillis())
        )
        actions.addAll(super.initialize(state))
        return actions
    }
}

/** Both branches belong to the compilation; only the selected branch receives runtime calls. */
private class ConditionalRoutineTask(
    private val predicate: (RobotState) -> Boolean,
    private val whenTrue: Task,
    private val whenFalse: Task,
    ownership: RoutineTaskOwnership
) : RoutineTaskWrapper(ownership) {
    override val name: String = "RoutineBranch"
    override val requiredResources: Long = whenTrue.requiredResources or whenFalse.requiredResources
    private var selected: Task? = null
    override val delegate: Task? get() = selected

    override fun initialize(state: RobotState): List<RobotAction> {
        selected = if (predicate(state)) whenTrue else whenFalse
        return super.initialize(state)
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
