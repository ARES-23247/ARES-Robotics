package com.areslib.sequencer

import com.areslib.state.RobotState

/**
 * Owner-side completion gate. Rejects failed/cancelled/overdue tasks before domain code can run,
 * then rechecks after that code so its status changes or time spent there cannot become success.
 * Exceptions propagate to the lifecycle owner. No user code runs under the watchdog monitor.
 */
internal fun Task.completionReady(state: RobotState, elapsedMs: Long): Boolean {
    if (!TaskTimeoutManager.permitsCompletion(this, elapsedMs)) return false
    val ready = isCompleted(state, elapsedMs)
    return TaskTimeoutManager.permitsCompletion(this, elapsedMs) && ready
}
