package com.areslib

import com.areslib.action.RobotAction
import com.areslib.math.estimation.PoseEstimatorRuntime
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
 * Each store also owns one fixed-capacity EKF runtime. Raw drive and vision actions are processed
 * there before a private derived action carries only observable estimator output through [reducer].
 * Mutable replay history is therefore neither global nor reachable from any published [state].
 * If estimator preparation or a reducer throws, that private history may already have changed.
 * This store then rejects further dispatches, preserving the last published snapshot and the
 * original failure as the cause. The lifecycle owner must neutralize hardware and replace the
 * failed store/robot before resuming; constructing a new store does not restore delayed history.
 * Observer failures outside reduction do not invalidate estimator history.
 *
 * @param initialState State visible before the first dispatch.
 * @param reducer Pure transition function. It must not mutate [RobotState] or perform hardware IO.
 */
class Store(
    initialState: RobotState = RobotState(),
    private val reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer
) {
    private val poseEstimatorRuntime = PoseEstimatorRuntime(initialState.drive.poseEstimator)
    // Accessed only under the store monitor. No history copies or allocations on the healthy path.
    private var reductionFailure: Throwable? = null

    @Volatile var state: RobotState = initialState
        private set

    @Volatile private var listeners = emptyArray<(RobotState) -> Unit>()
    
    /**
     * Optional synchronous observer invoked immediately before each reduction while holding the
     * store lock. Configure it during initialization; it must return quickly and must not dispatch
     * recursively into this store.
     */
    var actionListener: ((RobotAction) -> Unit)? = null

    /**
     * Dispatches an action to the store, executing estimator middleware and the configured reducer
     * synchronously on the caller's thread. After the public and private-derived reductions commit,
     * listeners are notified in registration order. A throwing listener stops notification and
     * propagates to the caller; the state remains committed.
     *
     * Concurrent calls are serialized, although single-loop ownership is recommended for
     * deterministic ordering.
     *
     * @param action The [RobotAction] describing the state transition.
     */
    fun dispatch(action: RobotAction) {
        val currentState: RobotState
        synchronized(this) {
            checkReductionHealthy()
            actionListener?.invoke(action)
            state = reduceWithRuntime(state, action)
            currentState = state
        }
        val snapshot = listeners
        for (i in snapshot.indices) {
            snapshot[i](currentState)
        }
    }

    /**
     * Reduces [actions] atomically with respect to other dispatches, then notifies subscribers once
     * with the final state. The action observer is still invoked once per action.
     * This serializes a batch; it is not a rollback transaction. A failure retains earlier committed
     * actions, skips the final notification, and propagates to the caller. A reduction failure also
     * invalidates this store. An empty successful batch still notifies with the current state.
     */
    fun dispatchAll(vararg actions: RobotAction) {
        val currentState: RobotState
        synchronized(this) {
            checkReductionHealthy()
            val actionCount = actions.size
            for (i in 0 until actionCount) {
                actionListener?.invoke(actions[i])
                state = reduceWithRuntime(state, actions[i])
            }
            currentState = state
        }
        val snapshot = listeners
        for (i in snapshot.indices) {
            snapshot[i](currentState)
        }
    }

    /**
     * Registers a state observer and returns an idempotent unsubscription callback.
     *
     * Registration does not immediately emit the current state. Observers execute synchronously on
     * the dispatching thread, outside the store lock, in copy-on-write list order.
     */
    fun subscribe(listener: (RobotState) -> Unit): () -> Unit {
        // A distinct registration preserves duplicate subscriptions and idempotent removal.
        val registration: (RobotState) -> Unit = { listener(it) }
        synchronized(this) { listeners = listeners + registration }
        return {
            synchronized(this) {
                listeners = listeners.filterNot { it === registration }.toTypedArray()
            }
        }
    }

    private fun reduceWithRuntime(currentState: RobotState, action: RobotAction): RobotState {
        try {
            val prepared = poseEstimatorRuntime.prepare(currentState, action)
            var reduced = reducer(currentState, prepared.publicAction)
            val estimatorAction = prepared.estimatorAction
            if (estimatorAction != null) {
                reduced = reducer(reduced, estimatorAction)
            }
            return reduced
        } catch (failure: Throwable) {
            reductionFailure = failure
            throw failure
        }
    }

    private fun checkReductionHealthy() {
        val failure = reductionFailure
        if (failure != null) throw IllegalStateException(
            "Store reduction previously failed; replace this store before dispatching again", failure
        )
    }
}
