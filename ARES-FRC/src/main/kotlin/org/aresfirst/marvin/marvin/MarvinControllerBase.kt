package org.aresfirst.marvin.marvin

import com.areslib.Store
import com.areslib.subsystem.SubsystemControllerBase

/** Shared Redux dispatch-on-change support for Marvin mechanism facades. */
abstract class MarvinControllerBase(store: Store) : SubsystemControllerBase(store) {
    /** Primitive comparison avoids boxing both values during unchanged 20 ms setpoint checks. */
    protected inline fun dispatchOnChange(
        current: Double,
        target: Double,
        actionFactory: (Double, Long) -> com.areslib.action.RobotAction,
        updateCurrent: (Double) -> Unit,
    ) {
        if (current != target) {
            store.dispatch(actionFactory(target, com.areslib.util.RobotClock.currentTimeMillis()))
            updateCurrent(target)
        }
    }
}
