package org.firstinspires.ftc.teamcode.opmodes.robot

import com.areslib.action.RobotAction
import com.areslib.ftc.FtcMecanumRobot
import com.areslib.state.Alliance
import com.areslib.util.RobotClock

/**
 * Dispatches the debounced alliance intent that remains season-owned rather than mechanism-owned.
 * Robot Builder owns every Lightbot mechanism target; this helper never writes hardware.
 */
class AresSuperstructureController(private val base: FtcMecanumRobot) {
    private var lastAllianceToggleTimeMs = 0L
    /** Toggles the Redux alliance; callers reset field pose separately when appropriate. */
    fun toggleAlliance() {
        val now = RobotClock.currentTimeMillis()
        if (now - lastAllianceToggleTimeMs < TOGGLE_DEBOUNCE_MS) return
        lastAllianceToggleTimeMs = now
        val currentAlliance = base.store.state.drive.alliance
        val newAlliance = when (currentAlliance) {
            Alliance.RED -> Alliance.BLUE
            Alliance.BLUE -> Alliance.RED
        }
        base.store.dispatch(RobotAction.SetAlliance(newAlliance))
    }

    private companion object {
        const val TOGGLE_DEBOUNCE_MS = 200L
    }
}
