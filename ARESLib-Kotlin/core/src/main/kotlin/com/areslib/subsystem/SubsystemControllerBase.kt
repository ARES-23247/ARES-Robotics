package com.areslib.subsystem

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.util.RobotClock

/**
 * Single-loop state-change dispatch for persistent mechanism targets. Do not use it for
 * leased commands that must refresh their timestamp every loop. Generic value types can box;
 * the primitive Double overload avoids allocation on unchanged numeric targets.
 */
abstract class SubsystemControllerBase(protected val store: Store) {
    /** Primitive equality treats signed zeros alike; NaN remains a changed, invalid target. */
    protected inline fun dispatchOnChange(
        current: Double,
        target: Double,
        actionFactory: (Double, Long) -> RobotAction,
        updateCurrent: (Double) -> Unit,
    ) {
        if (current != target) {
            store.dispatch(actionFactory(target, RobotClock.currentTimeMillis()))
            updateCurrent(target)
        }
    }

    /**
     * Dispatches a RobotAction to the store ONLY if the target value differs from current value,
     * avoiding redundant object allocations and reducer calculations in 50Hz-100Hz loops.
     */
    protected inline fun <T> dispatchOnChange(
        current: T?,
        target: T,
        actionFactory: (T, Long) -> RobotAction,
        updateCurrent: (T) -> Unit
    ) {
        if (current != target) {
            store.dispatch(actionFactory(target, RobotClock.currentTimeMillis()))
            updateCurrent(target)
        }
    }
}
