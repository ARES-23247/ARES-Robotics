package com.areslib

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.reducer.rootReducer

/**
 * Class implementation for Store.
 *
 * Pure Redux state definition and deterministic reducer transition handler.
 */
class Store(
    initialState: RobotState = RobotState(),
    private val reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer
) {
    @Volatile var state: RobotState = initialState
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(RobotState) -> Unit>()
    
    /**
     * Intercepts all dispatched actions (useful for telemetry logging and sequence recorders).
     */
    var actionListener: ((RobotAction) -> Unit)? = null

    /**
     * Dispatches an action to the store, executing the root reducer synchronously
     * on the caller's thread. All registered listeners are notified with the
     * updated state after reduction completes.
     *
     * Thread Safety: This method is NOT thread-safe. It must be called from
     * the main robot loop thread only.
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
     * dispatchAll declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
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
     * subscribe declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun subscribe(listener: (RobotState) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
}
