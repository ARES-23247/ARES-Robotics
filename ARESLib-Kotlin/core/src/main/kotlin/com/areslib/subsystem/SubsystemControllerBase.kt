package com.areslib.subsystem

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.util.RobotClock

/**
 * Base class for subsystem controllers providing zero-GC state change deduplication.
 */
abstract class SubsystemControllerBase(protected val store: Store) {
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
