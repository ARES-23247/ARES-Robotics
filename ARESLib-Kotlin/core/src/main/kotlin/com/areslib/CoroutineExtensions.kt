package com.areslib

import com.areslib.state.RobotState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Extension to convert the Redux [Store] updates into a cold [Flow] of [RobotState] instances.
 * Emits the current state immediately upon subscription, followed by all subsequent state transitions.
 * Automatically unsubscribes from the store when the flow collection is cancelled.
 */
fun Store.asFlow(): Flow<RobotState> = callbackFlow {
    // Emit initial state immediately
    trySend(state)
    
    // Subscribe to store updates
    val unsubscribe = subscribe { newState ->
        trySend(newState)
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
 * @return True if the condition was successfully met before timing out; false otherwise.
 */
suspend fun Flow<RobotState>.waitUntil(
    timeoutMs: Long = 5000L,
    condition: (RobotState) -> Boolean
): Boolean {
    val result = withTimeoutOrNull(timeoutMs) {
        this@waitUntil.first { condition(it) }
    }
    return result != null
}
