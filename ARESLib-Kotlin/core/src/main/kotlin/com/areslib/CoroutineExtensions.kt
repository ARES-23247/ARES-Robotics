package com.areslib

import com.areslib.state.RobotState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Extension to convert the Redux [Store] updates into a cold [Flow] of [RobotState] instances.
 * Emits the initial state and subsequent Store notifications. Registration and the initial
 * snapshot are atomic with respect to reductions. Callbacks retain Store's ordering contract;
 * concurrent dispatchers can notify out of reduction order, so use one dispatch owner.
 * If the buffer fills, collection fails visibly instead of silently omitting a transition.
 * Dispatch never waits for buffer space. Callers may configure capacity with `buffer`;
 * an explicit dropping overflow policy remains the caller's choice.
 * Automatically unsubscribes from the store when the flow collection is cancelled.
 */
fun Store.asFlow(): Flow<RobotState> = callbackFlow {
    fun offerState(newState: RobotState) {
        val result = trySend(newState)
        if (result.isFailure && !result.isClosed) {
            close(IllegalStateException("Store flow buffer overflow; increase capacity or consume updates faster"))
        }
    }

    // Register before emitting (an immediate collector can dispatch reentrantly). Keep
    // concurrent callbacks behind the initial snapshot without holding Store during collection.
    val observationLock = Any()
    val unsubscribe = synchronized(observationLock) {
        synchronized(this@asFlow) {
            val remove = subscribe { newState ->
                synchronized(observationLock) { offerState(newState) }
            }
            offerState(state)
            remove
        }
    }
    
    // When the flow collector is cancelled or finished, clean up the subscription
    awaitClose {
        unsubscribe()
    }
}

/**
 * Extension to suspend the coroutine until a specific state condition is met or a timeout expires.
 *
 * @param timeoutMs Maximum time to wait in milliseconds. Defaults to 5000ms.
 * @param condition Lambda returning true when the desired state is reached.
 * @return True if the condition was met; false on timeout or normal completion without a match.
 * Upstream failures, predicate failures, and caller cancellation propagate.
 */
suspend fun Flow<RobotState>.waitUntil(
    timeoutMs: Long = 5000L,
    condition: (RobotState) -> Boolean
): Boolean {
    val result = withTimeoutOrNull(timeoutMs) {
        this@waitUntil.firstOrNull { condition(it) }
    }
    return result != null
}
