package com.areslib.ftc.sim

import com.areslib.hardware.SimMechanismOutputProvider

/**
 * FTC simulator mechanism snapshot containing both accepted Redux intent and applied IO output.
 *
 * Toggle injection reconciles against [intakeAccepted] and [flywheelAccepted], while field physics
 * continues to consume the post-safety output properties inherited from
 * [SimMechanismOutputProvider]. Keeping both views prevents an interlock rejection from being
 * mistaken for an accepted toggle without allowing inhibited hardware to interact with the field.
 */
interface FtcSimMechanismStateProvider : SimMechanismOutputProvider {
    val intakeAccepted: Boolean
    val flywheelAccepted: Boolean
}
