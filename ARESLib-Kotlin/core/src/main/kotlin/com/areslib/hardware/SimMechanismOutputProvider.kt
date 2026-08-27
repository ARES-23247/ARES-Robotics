package com.areslib.hardware

/**
 * Read-only simulator boundary for mechanism outputs that were actually applied by season IO.
 *
 * Season facades should expose cached post-safety outputs here after `writeOutputs`; dashboard
 * intent must never implement this contract because it can disagree with disabled, inhibited, or
 * failed hardware.
 */
interface SimMechanismOutputProvider {
    val intakeApplied: Boolean
    val flywheelApplied: Boolean
    val transferApplied: Boolean
}
