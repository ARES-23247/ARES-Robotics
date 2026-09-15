package com.areslib.sequencer

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide callback registry keyed strictly by task object identity.
 *
 * At most one completion and one failure callback are retained for each task. Invocation is
 * synchronous on the executor thread. Terminal invocation removes both callbacks before executing
 * user code, making callbacks one-shot and releasing captured state even when the callback throws.
 */
object TaskCallbacks {
    private class TaskIdentity(val task: Task) {
        override fun hashCode(): Int = System.identityHashCode(task)
        override fun equals(other: Any?): Boolean = other is TaskIdentity && task === other.task
    }

    private val completeCallbacks = ConcurrentHashMap<TaskIdentity, () -> Unit>()
    private val failCallbacks = ConcurrentHashMap<TaskIdentity, () -> Unit>()

    /** Replaces the completion callback associated with [task]. */
    fun registerComplete(task: Task, callback: () -> Unit) {
        completeCallbacks[TaskIdentity(task)] = callback
    }

    /** Replaces the failure callback associated with [task]. */
    fun registerFail(task: Task, callback: () -> Unit) {
        failCallbacks[TaskIdentity(task)] = callback
    }

    /** Invokes [task]'s completion callback when registered. */
    fun invokeComplete(task: Task) {
        val key = TaskIdentity(task)
        val callback = completeCallbacks.remove(key)
        failCallbacks.remove(key)
        callback?.invoke()
    }

    /** Invokes [task]'s failure callback when registered. */
    fun invokeFail(task: Task) {
        val key = TaskIdentity(task)
        val callback = failCallbacks.remove(key)
        completeCallbacks.remove(key)
        callback?.invoke()
    }

    /** Removes all callbacks and retained references for [task]. */
    fun reset(task: Task) {
        val key = TaskIdentity(task)
        completeCallbacks.remove(key)
        failCallbacks.remove(key)
    }
}
