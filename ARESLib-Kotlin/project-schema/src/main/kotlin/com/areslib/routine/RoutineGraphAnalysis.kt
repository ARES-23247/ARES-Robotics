package com.areslib.routine

internal const val MAX_ROUTINE_DEPTH = 64

internal fun countSourceSteps(steps: List<RoutineStep>, limit: Int): Int {
    var count = 0
    val remaining = java.util.ArrayDeque<RoutineStep>()
    for (index in steps.indices.reversed()) remaining.addLast(steps[index])
    while (remaining.isNotEmpty() && count <= limit) {
        val step = remaining.removeLast()
        count++
        step.deadline?.let(remaining::addLast)
        step.children.forEach(remaining::addLast)
        step.elseChildren.forEach(remaining::addLast)
    }
    return count
}

internal fun hasExcessiveSourceDepth(steps: List<RoutineStep>): Boolean {
    data class Pending(val step: RoutineStep, val depth: Int)
    val remaining = java.util.ArrayDeque<Pending>()
    steps.forEach { remaining.addLast(Pending(it, 0)) }
    while (remaining.isNotEmpty()) {
        val (step, depth) = remaining.removeLast()
        if (depth > MAX_ROUTINE_DEPTH) return true
        val childDepth = depth + 1
        step.deadline?.let { remaining.addLast(Pending(it, childDepth)) }
        step.children.forEach { remaining.addLast(Pending(it, childDepth)) }
        step.elseChildren.forEach { remaining.addLast(Pending(it, childDepth)) }
    }
    return false
}

internal fun estimateExpandedSteps(
    steps: List<RoutineStep>,
    documents: Map<String, RoutineDocument>,
    callStack: MutableSet<String>,
    limit: Long
): Long {
    fun saturatedAdd(left: Long, right: Long): Long =
        if (left >= limit || right >= limit || left > limit - right) limit else left + right
    fun saturatedMultiply(left: Long, right: Long): Long =
        if (left == 0L || right == 0L) 0L else if (left >= limit || right >= limit || left > limit / right) limit else left * right

    lateinit var estimateStep: (RoutineStep) -> Long

    fun estimateList(children: List<RoutineStep>): Long {
        var total = 0L
        for (child in children) {
            total = saturatedAdd(total, estimateStep(child))
            if (total >= limit) break
        }
        return total
    }

    fun estimateCalled(id: String?): Long {
        val called = id?.let(documents::get) ?: return 0L
        if (!callStack.add(id)) return limit
        return try {
            estimateList(called.steps)
        } finally {
            callStack.remove(id)
        }
    }

    estimateStep = { step ->
        val nested = when (step.kind) {
            RoutineStepKind.CALL -> estimateCalled(step.routineId)
            RoutineStepKind.REPEAT -> saturatedMultiply(
                (step.repeatCount ?: 0).coerceAtLeast(0).toLong(),
                estimateList(step.children)
            )
            else -> {
                var count = estimateList(step.children)
                count = saturatedAdd(count, estimateList(step.elseChildren))
                step.deadline?.let { count = saturatedAdd(count, estimateStep(it)) }
                count
            }
        }
        saturatedAdd(1L, nested)
    }

    return estimateList(steps)
}

internal fun validateConcurrentResources(
    routine: RoutineDocument,
    branches: List<RoutineStep>,
    path: String,
    context: RoutineValidationContext,
    issues: MutableList<RoutineValidationIssue>
) {
    val owners = mutableMapOf<String, Int>()
    branches.forEachIndexed { branchIndex, branch ->
        collectResources(branch, context, mutableSetOf()).forEach { resource ->
            val priorBranch = owners.putIfAbsent(resource, branchIndex)
            if (priorBranch != null && priorBranch != branchIndex) {
                issues += routine.error(
                    path,
                    "parallel_resource_conflict",
                    "Parallel branches ${priorBranch + 1} and ${branchIndex + 1} both require '$resource'"
                )
            }
        }
    }
}

internal fun collectResources(
    step: RoutineStep,
    context: RoutineValidationContext,
    visitedRoutines: MutableSet<String>
): Set<String> = when (step.kind) {
    RoutineStepKind.ACTION -> step.actionKey?.let(context.resourcesForAction).orEmpty()
    RoutineStepKind.DRIVE_TO -> buildSet {
        step.drive?.let { drive ->
            addAll(context.resourcesForDrive(drive))
            drive.markers.forEach { addAll(context.resourcesForAction(it.actionKey)) }
            drive.duringActionKeys.forEach { addAll(context.resourcesForAction(it)) }
            drive.arrivalActionKeys.forEach { addAll(context.resourcesForAction(it)) }
        }
    }
    RoutineStepKind.CALL -> {
        val id = step.routineId
        val called = id?.let(context.documents::get)
        if (id == null || called == null || !visitedRoutines.add(id)) {
            emptySet()
        } else {
            called.steps.flatMapTo(mutableSetOf()) { collectResources(it, context, visitedRoutines) }
                .also { visitedRoutines.remove(id) }
        }
    }
    else -> buildSet {
        step.deadline?.let { addAll(collectResources(it, context, visitedRoutines)) }
        step.children.forEach { addAll(collectResources(it, context, visitedRoutines)) }
        step.elseChildren.forEach { addAll(collectResources(it, context, visitedRoutines)) }
    }
}

internal fun findCallCycle(
    startId: String,
    documents: Map<String, RoutineDocument>
): List<String>? {
    val visited = mutableSetOf<String>()
    val stack = mutableListOf<String>()
    val onStack = mutableSetOf<String>()

    fun visit(id: String): List<String>? {
        if (id in onStack) {
            val cycleStart = stack.indexOf(id).coerceAtLeast(0)
            return stack.subList(cycleStart, stack.size).toList() + id
        }
        if (!visited.add(id)) return null
        stack += id
        onStack += id
        val calledIds = documents[id]?.steps.orEmpty().flatMapTo(mutableSetOf()) { it.calledRoutineIds() }
        calledIds.forEach { calledId ->
            visit(calledId)?.let { return it }
        }
        stack.removeAt(stack.lastIndex)
        onStack.remove(id)
        return null
    }
    return visit(startId)
}

internal fun RoutineStep.calledRoutineIds(): Set<String> = buildSet {
    if (kind == RoutineStepKind.CALL) routineId?.let(::add)
    deadline?.let { addAll(it.calledRoutineIds()) }
    children.forEach { addAll(it.calledRoutineIds()) }
    elseChildren.forEach { addAll(it.calledRoutineIds()) }
}

internal fun RoutineDocument.error(path: String, code: String, message: String): RoutineValidationIssue =
    RoutineValidationIssue(RoutineValidationSeverity.ERROR, documentId, path, code, message)
