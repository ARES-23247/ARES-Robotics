package com.areslib.routine

import java.util.ArrayDeque

internal fun suspendConflictingRoutineExecutions(
    routineId: String,
    requestedResources: Set<String>,
    activeInStartOrder: ArrayList<RoutineManager.ActiveExecution>,
    active: LinkedHashMap<Long, RoutineManager.ActiveExecution>,
    suspended: ArrayDeque<RoutineManager.ActiveExecution>,
    destination: MutableList<RoutineManager.ActiveExecution>,
) {
    var activeIndex = 0
    while (activeIndex < activeInStartOrder.size) {
        val execution = activeInStartOrder[activeIndex]
        val conflicts = execution.routineId == routineId ||
            (requestedResources.isNotEmpty() && requestedResources.any(execution.resourceKeys::contains)) ||
            (requestedResources.isEmpty() && execution.resourceKeys.isEmpty())
        if (conflicts) {
            activeInStartOrder.removeAt(activeIndex)
            active.remove(execution.executionId)
            suspended.addFirst(execution)
            destination.add(execution)
        } else {
            activeIndex++
        }
    }
}

internal inline fun resumeNonConflictingExecutions(
    suspended: ArrayDeque<RoutineManager.ActiveExecution>,
    active: LinkedHashMap<Long, RoutineManager.ActiveExecution>,
    activeInStartOrder: ArrayList<RoutineManager.ActiveExecution>,
    resumedExecutions: ArrayList<RoutineManager.ActiveExecution>,
    findConflict: (Set<String>) -> String?,
) {
    if (suspended.isEmpty()) return
    val iterator = suspended.iterator()
    while (iterator.hasNext()) {
        val candidate = iterator.next()
        if (findConflict(candidate.resourceKeys) == null) {
            iterator.remove()
            active[candidate.executionId] = candidate
            activeInStartOrder.add(candidate)
            resumedExecutions.add(candidate)
        }
    }
}

internal fun detachMatchingSuspended(
    suspended: ArrayDeque<RoutineManager.ActiveExecution>,
    matchesRoutineId: (String) -> Boolean,
    destination: MutableList<RoutineManager.DetachedExecution>,
) {
    val iterator = suspended.iterator()
    while (iterator.hasNext()) {
        val execution = iterator.next()
        if (matchesRoutineId(execution.routineId)) {
            iterator.remove()
            destination.add(
                RoutineManager.DetachedExecution(
                    execution.executionId,
                    execution.routineId,
                    execution.task,
                    execution.executor,
                )
            )
        }
    }
}

internal fun detachSuspendedByExecutionId(
    suspended: ArrayDeque<RoutineManager.ActiveExecution>,
    executionId: Long,
): RoutineManager.DetachedExecution? {
    val iterator = suspended.iterator()
    while (iterator.hasNext()) {
        val execution = iterator.next()
        if (execution.executionId == executionId) {
            iterator.remove()
            return RoutineManager.DetachedExecution(
                execution.executionId,
                execution.routineId,
                execution.task,
                execution.executor,
            )
        }
    }
    return null
}
