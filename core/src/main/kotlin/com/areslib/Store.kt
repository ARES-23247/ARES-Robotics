package com.areslib

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.reducer.rootReducer

/**
 * Synchronous Redux-style state container for robot state transitions.
 *
 * Reductions are serialized by this instance, and [state] is published through a volatile
 * reference so readers on telemetry or simulator threads see the latest immutable snapshot.
 * [actionListener] and subscribers run synchronously on the dispatching thread; subscriber
 * callbacks run after the store lock is released. A slow or throwing callback therefore affects
 * its caller but cannot leave the reducer half-applied.
 *
 * Callers should normally dispatch from the robot loop to keep action ordering deterministic.
 * Concurrent dispatches are safe from lost updates, but their ordering is whichever caller enters
 * the monitor first.
 *
 * @param initialState State visible before the first dispatch.
 * @param reducer Pure transition function. It must not mutate [RobotState] or perform hardware IO.
 */
class Store(
    initialState: RobotState = RobotState(),
    private val reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer
) {
    @Volatile var state: RobotState = initialState
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(RobotState) -> Unit>()
    
    /**
     * Optional synchronous observer invoked immediately before each reduction while holding the
     * store lock. Configure it during initialization; it must return quickly and must not dispatch
     * recursively into this store.
     */
    var actionListener: ((RobotAction) -> Unit)? = null

    /**
     * Dispatches an action to the store, executing the root reducer synchronously
     * on the caller's thread. All registered listeners are notified with the
     * updated state after reduction completes.
     *
     * Concurrent calls are serialized, although single-loop ownership is recommended for
     * deterministic ordering.
     *
     * @param action The [RobotAction] describing the state transition.
     */
    fun dispatch(action: RobotAction) {
        val currentState: RobotState
        synchronized(this) {
            actionListener?.invoke(action)
            state = reducer(state, action)
            currentState = state
        }
        listeners.forEach { it(currentState) }
    }

    /**
     * Reduces [actions] atomically with respect to other dispatches, then notifies subscribers once
     * with the final state. The action observer is still invoked once per action.
     */
    fun dispatchAll(vararg actions: RobotAction) {
        val currentState: RobotState
        synchronized(this) {
            val actionCount = actions.size
            for (i in 0 until actionCount) {
                actionListener?.invoke(actions[i])
                state = reducer(state, actions[i])
            }
            currentState = state
        }
        listeners.forEach { it(currentState) }
    }

    /**
     * Registers a state observer and returns an idempotent unsubscription callback.
     *
     * Registration does not immediately emit the current state. Observers execute synchronously on
     * the dispatching thread, outside the store lock, in copy-on-write list order.
     */
    fun subscribe(listener: (RobotState) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
}
