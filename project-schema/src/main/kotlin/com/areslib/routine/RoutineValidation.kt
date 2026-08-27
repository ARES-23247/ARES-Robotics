package com.areslib.routine

/** Severity of an editor/build validation diagnostic. */
enum class RoutineValidationSeverity { WARNING, ERROR }

/** One stable, machine-readable routine diagnostic. */
data class RoutineValidationIssue(
    val severity: RoutineValidationSeverity,
    val documentId: String,
    val path: String,
    val code: String,
    val message: String
)

/**
 * Optional project knowledge used for reference and resource validation.
 *
 * These callbacks deliberately use only stable keys. The generated action catalog can adapt to
 * them without coupling the routine file format to a particular catalog representation.
 */
data class RoutineValidationContext(
    val documents: Map<String, RoutineDocument> = emptyMap(),
    val requireResolvedCalls: Boolean = false,
    val hasAction: ((String) -> Boolean)? = null,
    val hasCondition: ((String) -> Boolean)? = null,
    val resourcesForAction: (String) -> Set<String> = { emptySet() },
    val resourcesForDrive: (RoutineDriveStep) -> Set<String> = { setOf("drivetrain") }
)

/** Validates one routine and any project-level call/resource relationships visible in [context]. */
fun validateRoutine(
    routine: RoutineDocument,
    context: RoutineValidationContext = RoutineValidationContext()
): List<RoutineValidationIssue> {
    val issues = mutableListOf<RoutineValidationIssue>()
    val documents = if (context.documents.containsKey(routine.documentId)) {
        context.documents
    } else {
        context.documents + (routine.documentId to routine)
    }
    validateDocumentHeader(routine, issues)
    if (routine.steps.isEmpty()) {
        issues += routine.error("steps", "empty_routine", "Add at least one action, wait, or drive goal")
    }
    val sourceStepCount = countSourceSteps(routine.steps, MAX_SOURCE_STEPS + 1)
    if (sourceStepCount > MAX_SOURCE_STEPS) {
        issues += routine.error("steps", "routine_too_large", "Routine source may contain at most $MAX_SOURCE_STEPS steps")
    }
    val expandedStepCount = if (sourceStepCount > MAX_SOURCE_STEPS || hasExcessiveSourceDepth(routine.steps)) {
        // validateSteps emits the precise nesting diagnostic without walking deeper than the cap.
        0L
    } else {
        estimateExpandedSteps(
            routine.steps,
            documents,
            mutableSetOf(routine.documentId),
            MAX_EXPANDED_STEPS + 1L
        )
    }
    if (expandedStepCount > MAX_EXPANDED_STEPS) {
        issues += routine.error(
            "steps",
            "routine_expansion_too_large",
            "Calls and repeats may expand to at most $MAX_EXPANDED_STEPS executable steps"
        )
    }
    validateStepIds(routine, routine.steps, "steps", mutableSetOf(), issues)
    validateSteps(routine, routine.steps, "steps", 0, context, issues)
    findCallCycle(routine.documentId, documents)?.let { cycle ->
        issues += routine.error(
            "steps",
            "recursive_routine_call",
            "Routine calls form a cycle: ${cycle.joinToString(" -> ")}"
        )
    }
    return issues
}

private fun validateStepIds(
    routine: RoutineDocument,
    steps: List<RoutineStep>,
    parentPath: String,
    seen: MutableSet<String>,
    issues: MutableList<RoutineValidationIssue>
) {
    steps.forEach { step ->
        val path = "$parentPath/${step.stepId}"
        when {
            !step.stepId.matches(DOCUMENT_ID_REGEX) -> issues += routine.error(
                "$path.stepId",
                "invalid_step_id",
                "Step ID must be a stable filesystem-safe lowercase identifier"
            )
            !seen.add(step.stepId) -> issues += routine.error(
                "$path.stepId",
                "duplicate_step_id",
                "Step ID '${step.stepId}' is duplicated in this routine"
            )
        }
        step.deadline?.let { validateStepIds(routine, listOf(it), "$path.deadline", seen, issues) }
        validateStepIds(routine, step.children, "$path.children", seen, issues)
        validateStepIds(routine, step.elseChildren, "$path.elseChildren", seen, issues)
    }
}

/** Validates a complete project routine set, including duplicate IDs and cross-document calls. */
fun validateRoutineSet(
    routines: Collection<RoutineDocument>,
    context: RoutineValidationContext = RoutineValidationContext()
): List<RoutineValidationIssue> {
    val issues = mutableListOf<RoutineValidationIssue>()
    val byId = LinkedHashMap<String, RoutineDocument>()
    routines.forEach { routine ->
        if (byId.putIfAbsent(routine.documentId, routine) != null) {
            issues += routine.error(
                "documentId",
                "duplicate_document_id",
                "More than one routine uses document ID '${routine.documentId}'"
            )
        }
    }
    val projectContext = context.copy(documents = byId, requireResolvedCalls = true)
    byId.values.forEach { issues += validateRoutine(it, projectContext) }
    return issues.distinctBy { listOf(it.documentId, it.path, it.code, it.message) }
}

private fun validateDocumentHeader(
    routine: RoutineDocument,
    issues: MutableList<RoutineValidationIssue>
) {
    if (routine.schemaVersion != ARES_ROUTINE_SCHEMA_VERSION) {
        issues += routine.error(
            "routine",
            "unsupported_schema",
            "Routine schema ${routine.schemaVersion} is not supported; expected $ARES_ROUTINE_SCHEMA_VERSION"
        )
    }
    if (!routine.documentId.isStableId()) {
        issues += routine.error(
            "documentId",
            "invalid_document_id",
            "Document ID must be a filesystem-safe lowercase identifier"
        )
    }
    if (routine.revision < 1) {
        issues += routine.error("revision", "invalid_revision", "Revision must be at least 1")
    }
    if (routine.parentContentHash != null && !routine.parentContentHash.matches(SHA_256_REGEX)) {
        issues += routine.error(
            "parentContentHash",
            "invalid_parent_hash",
            "Parent content hash must be a lowercase SHA-256 value"
        )
    }
    if (routine.name.isBlank()) {
        issues += routine.error("name", "missing_name", "Routine name must not be blank")
    } else if (routine.name.length > MAX_ROUTINE_NAME_LENGTH) {
        issues += routine.error("name", "name_too_long", "Routine name exceeds $MAX_ROUTINE_NAME_LENGTH characters")
    }
    if (routine.description != null && routine.description.length > MAX_ROUTINE_DESCRIPTION_LENGTH) {
        issues += routine.error(
            "description",
            "description_too_long",
            "Routine description exceeds $MAX_ROUTINE_DESCRIPTION_LENGTH characters"
        )
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun validateSteps(
    routine: RoutineDocument,
    steps: List<RoutineStep>,
    parentPath: String,
    depth: Int,
    context: RoutineValidationContext,
    issues: MutableList<RoutineValidationIssue>
) {
    if (depth > MAX_ROUTINE_DEPTH) {
        issues += routine.error(parentPath, "routine_too_deep", "Routine nesting may not exceed $MAX_ROUTINE_DEPTH levels")
        return
    }
    steps.forEachIndexed { index, step ->
        val path = "$parentPath[$index]"
        validateArguments(routine, step.arguments, "$path.arguments", issues)
        when (step.kind) {
            RoutineStepKind.ACTION -> {
                validatePayload(routine, step, path, allowed = setOf(Payload.ACTION, Payload.ARGUMENTS), issues)
                val key = step.actionKey
                if (!key.isStableKey()) {
                    issues += routine.error(path, "invalid_action_key", "Action step must select a valid action key")
                } else if (context.hasAction?.invoke(key!!) == false) {
                    issues += routine.error(path, "unknown_action", "Action '$key' is not declared by this robot project")
                }
            }

            RoutineStepKind.DRIVE_TO -> {
                validatePayload(routine, step, path, allowed = setOf(Payload.DRIVE), issues)
                val drive = step.drive
                if (drive == null) {
                    issues += routine.error(path, "missing_drive", "Drive step is missing its target")
                } else {
                    validateDrive(routine, drive, path, context, issues)
                }
            }

            RoutineStepKind.WAIT -> {
                validatePayload(routine, step, path, allowed = setOf(Payload.DURATION), issues)
                if (!step.durationSeconds.isFiniteNonNegativeSeconds()) {
                    issues += routine.error(path, "invalid_wait", "Wait duration must be finite and non-negative")
                }
            }

            RoutineStepKind.WAIT_UNTIL -> {
                validatePayload(
                    routine,
                    step,
                    path,
                    allowed = setOf(Payload.CONDITION, Payload.TIMEOUT, Payload.ARGUMENTS),
                    issues
                )
                validateCondition(routine, step.conditionKey, path, context, issues)
                if (!step.timeoutSeconds.isFinitePositiveSeconds()) {
                    issues += routine.error(
                        path,
                        "missing_wait_timeout",
                        "A state wait must have a finite, positive timeout"
                    )
                }
            }

            RoutineStepKind.TOGETHER,
            RoutineStepKind.FIRST_TO_FINISH -> {
                validatePayload(routine, step, path, allowed = setOf(Payload.CHILDREN), issues)
                validateNonEmptyChildren(routine, step, path, issues)
                validateSteps(routine, step.children, "$path.children", depth + 1, context, issues)
                validateConcurrentResources(routine, step.children, path, context, issues)
            }

            RoutineStepKind.DEADLINE -> {
                validatePayload(routine, step, path, allowed = setOf(Payload.CHILDREN, Payload.DEADLINE), issues)
                val deadline = step.deadline
                if (deadline == null) {
                    issues += routine.error(path, "missing_deadline", "Deadline group must select one deadline step")
                } else {
                    validateSteps(routine, listOf(deadline), "$path.deadline", depth + 1, context, issues)
                    validateConcurrentResources(routine, listOf(deadline) + step.children, path, context, issues)
                }
                validateSteps(routine, step.children, "$path.children", depth + 1, context, issues)
            }

            RoutineStepKind.CALL -> {
                validatePayload(routine, step, path, allowed = setOf(Payload.ROUTINE), issues)
                val calledId = step.routineId
                if (!calledId.isStableId()) {
                    issues += routine.error(path, "invalid_routine_id", "Call step must select a valid routine ID")
                } else if (context.requireResolvedCalls && !context.documents.containsKey(calledId)) {
                    issues += routine.error(path, "missing_routine", "Called routine '$calledId' does not exist")
                }
            }

            RoutineStepKind.REPEAT -> {
                validatePayload(routine, step, path, allowed = setOf(Payload.REPEAT, Payload.CHILDREN), issues)
                if (step.repeatCount == null || step.repeatCount !in 1..MAX_REPEAT_COUNT) {
                    issues += routine.error(
                        path,
                        "invalid_repeat_count",
                        "Repeat count must be between 1 and $MAX_REPEAT_COUNT"
                    )
                }
                validateNonEmptyChildren(routine, step, path, issues)
                validateSteps(routine, step.children, "$path.children", depth + 1, context, issues)
            }

            RoutineStepKind.BRANCH -> {
                validatePayload(
                    routine,
                    step,
                    path,
                    allowed = setOf(Payload.CONDITION, Payload.ARGUMENTS, Payload.CHILDREN, Payload.ELSE_CHILDREN),
                    issues
                )
                validateCondition(routine, step.conditionKey, path, context, issues)
                if (step.children.isEmpty() && step.elseChildren.isEmpty()) {
                    issues += routine.error(path, "empty_branch", "At least one branch must contain a step")
                }
                validateSteps(routine, step.children, "$path.children", depth + 1, context, issues)
                validateSteps(routine, step.elseChildren, "$path.elseChildren", depth + 1, context, issues)
            }
        }
    }
}

private fun validateDrive(
    routine: RoutineDocument,
    drive: RoutineDriveStep,
    path: String,
    context: RoutineValidationContext,
    issues: MutableList<RoutineValidationIssue>
) {
    if (!drive.target.isFinite()) {
        issues += routine.error(path, "invalid_drive_target", "Drive target must contain only finite values")
    }
    if (!drive.motionPresetKey.isStableKey()) {
        issues += routine.error(path, "invalid_motion_preset", "Motion preset must be a stable key")
    }
    if (drive.preferredEngineKey != null && !drive.preferredEngineKey.isStableKey()) {
        issues += routine.error(path, "invalid_trajectory_engine", "Preferred trajectory engine must be a stable key")
    }
    drive.markers.forEachIndexed { markerIndex, marker ->
        if (!marker.progress.isFinite() || marker.progress !in 0.0..1.0) {
            issues += routine.error(
                "$path.drive.markers[$markerIndex]",
                "invalid_marker_progress",
                "Drive marker progress must be between 0 and 1"
            )
        }
        validateActionReference(routine, marker.actionKey, "$path.drive.markers[$markerIndex]", context, issues)
    }
    if (drive.markers.size > MAX_DRIVE_REFERENCES ||
        drive.duringActionKeys.size > MAX_DRIVE_REFERENCES ||
        drive.arrivalActionKeys.size > MAX_DRIVE_REFERENCES) {
        issues += routine.error(path, "too_many_drive_references", "Drive lists may contain at most $MAX_DRIVE_REFERENCES entries each")
    }
    drive.duringActionKeys.forEachIndexed { index, key ->
        validateActionReference(routine, key, "$path.drive.duringActionKeys[$index]", context, issues)
    }
    drive.arrivalActionKeys.forEachIndexed { index, key ->
        validateActionReference(routine, key, "$path.drive.arrivalActionKeys[$index]", context, issues)
    }
}

private fun validateActionReference(
    routine: RoutineDocument,
    key: String,
    path: String,
    context: RoutineValidationContext,
    issues: MutableList<RoutineValidationIssue>
) {
    if (!key.isStableKey()) {
        issues += routine.error(path, "invalid_action_key", "'$key' is not a valid action key")
    } else if (context.hasAction?.invoke(key) == false) {
        issues += routine.error(path, "unknown_action", "Action '$key' is not declared by this robot project")
    }
}

private fun validateCondition(
    routine: RoutineDocument,
    conditionKey: String?,
    path: String,
    context: RoutineValidationContext,
    issues: MutableList<RoutineValidationIssue>
) {
    if (!conditionKey.isStableKey()) {
        issues += routine.error(path, "invalid_condition_key", "Step must select a valid condition key")
    } else if (context.hasCondition?.invoke(conditionKey!!) == false) {
        issues += routine.error(path, "unknown_condition", "Condition '$conditionKey' is not declared by this robot project")
    }
}

private fun validateArguments(
    routine: RoutineDocument,
    arguments: Map<String, String>,
    path: String,
    issues: MutableList<RoutineValidationIssue>
) {
    if (arguments.size > MAX_ARGUMENT_COUNT) {
        issues += routine.error(path, "too_many_arguments", "A step may contain at most $MAX_ARGUMENT_COUNT arguments")
    }
    var totalLength = 0L
    arguments.forEach { (key, value) ->
        totalLength += key.length.toLong() + value.length.toLong()
        if (!key.matches(ARGUMENT_KEY_REGEX)) {
            issues += routine.error(path, "invalid_argument_key", "Argument '$key' is not a stable identifier")
        }
        if (value.length > MAX_ARGUMENT_LENGTH) {
            issues += routine.error(path, "argument_too_long", "Argument '$key' exceeds $MAX_ARGUMENT_LENGTH characters")
        }
    }
    if (totalLength > MAX_TOTAL_ARGUMENT_LENGTH) {
        issues += routine.error(path, "arguments_too_large", "Step arguments exceed $MAX_TOTAL_ARGUMENT_LENGTH total characters")
    }
}

private fun countSourceSteps(steps: List<RoutineStep>, limit: Int): Int {
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

private fun hasExcessiveSourceDepth(steps: List<RoutineStep>): Boolean {
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

private fun estimateExpandedSteps(
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

private fun validateNonEmptyChildren(
    routine: RoutineDocument,
    step: RoutineStep,
    path: String,
    issues: MutableList<RoutineValidationIssue>
) {
    if (step.children.isEmpty()) {
        issues += routine.error(path, "empty_group", "${step.kind.name} must contain at least one step")
    }
}

private fun validateConcurrentResources(
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

private fun collectResources(
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

private fun validatePayload(
    routine: RoutineDocument,
    step: RoutineStep,
    path: String,
    allowed: Set<Payload>,
    issues: MutableList<RoutineValidationIssue>
) {
    val present = buildSet {
        if (step.actionKey != null) add(Payload.ACTION)
        if (step.arguments.isNotEmpty()) add(Payload.ARGUMENTS)
        if (step.drive != null) add(Payload.DRIVE)
        if (step.durationSeconds != null) add(Payload.DURATION)
        if (step.timeoutSeconds != null) add(Payload.TIMEOUT)
        if (step.conditionKey != null) add(Payload.CONDITION)
        if (step.routineId != null) add(Payload.ROUTINE)
        if (step.repeatCount != null) add(Payload.REPEAT)
        if (step.children.isNotEmpty()) add(Payload.CHILDREN)
        if (step.deadline != null) add(Payload.DEADLINE)
        if (step.elseChildren.isNotEmpty()) add(Payload.ELSE_CHILDREN)
    }
    val unexpected = present - allowed
    if (unexpected.isNotEmpty()) {
        issues += routine.error(
            path,
            "conflicting_payload",
            "${step.kind.name} contains fields that do not belong to that step type: " +
                unexpected.joinToString { it.name.lowercase() }
        )
    }
}

private fun findCallCycle(
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

private fun RoutineStep.calledRoutineIds(): Set<String> = buildSet {
    if (kind == RoutineStepKind.CALL) routineId?.let(::add)
    deadline?.let { addAll(it.calledRoutineIds()) }
    children.forEach { addAll(it.calledRoutineIds()) }
    elseChildren.forEach { addAll(it.calledRoutineIds()) }
}

private fun RoutineDocument.error(path: String, code: String, message: String): RoutineValidationIssue =
    RoutineValidationIssue(RoutineValidationSeverity.ERROR, documentId, path, code, message)

private fun String?.isStableId(): Boolean = this != null && matches(DOCUMENT_ID_REGEX)
private fun String?.isStableKey(): Boolean = this != null && matches(STABLE_KEY_REGEX)
private fun Double?.isFiniteNonNegativeSeconds(): Boolean = this != null && isFinite() && this >= 0.0 && this <= MAX_SECONDS
private fun Double?.isFinitePositiveSeconds(): Boolean = this != null && isFinite() && this > 0.0 && this <= MAX_SECONDS
private fun RoutinePose.isFinite(): Boolean = xMeters.isFinite() && yMeters.isFinite() && headingRadians.isFinite()

private enum class Payload {
    ACTION, ARGUMENTS, DRIVE, DURATION, TIMEOUT, CONDITION, ROUTINE, REPEAT, CHILDREN, DEADLINE, ELSE_CHILDREN
}

private val DOCUMENT_ID_REGEX = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private val STABLE_KEY_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
private val ARGUMENT_KEY_REGEX = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
private val SHA_256_REGEX = Regex("[a-f0-9]{64}")
private const val MAX_ROUTINE_DEPTH = 64
private const val MAX_REPEAT_COUNT = 1_000
private const val MAX_SOURCE_STEPS = 10_000
private const val MAX_EXPANDED_STEPS = 10_000L
private const val MAX_ARGUMENT_COUNT = 64
private const val MAX_TOTAL_ARGUMENT_LENGTH = 65_536L
private const val MAX_DRIVE_REFERENCES = 256
private const val MAX_ROUTINE_NAME_LENGTH = 256
private const val MAX_ROUTINE_DESCRIPTION_LENGTH = 65_536
private const val MAX_ARGUMENT_LENGTH = 4_096
private const val MAX_SECONDS = Long.MAX_VALUE / 1_000.0
