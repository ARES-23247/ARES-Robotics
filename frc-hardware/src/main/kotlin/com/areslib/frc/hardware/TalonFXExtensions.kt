package com.areslib.frc.hardware

import com.ctre.phoenix6.BaseStatusSignal
import com.ctre.phoenix6.StatusCode
import com.ctre.phoenix6.configs.TalonFXConfiguration
import com.ctre.phoenix6.hardware.TalonFX
import edu.wpi.first.wpilibj.DriverStation

/**
 * Batch-applies a [TalonFXConfiguration] block across an iterable collection of [TalonFX] motor controllers.
 *
 * Each apply is retried (up to 5 attempts) on non-OK [StatusCode]s, because a transient CAN
 * error otherwise leaves a motor on factory defaults (no current limits, wrong PID). If the
 * config still fails to apply after all attempts, the failure is reported to the FRC
 * [DriverStation] so it surfaces on the driver console / Driver Station log.
 *
 * @receiver Collection of physical CTRE [TalonFX] motor controller instances.
 * @param block Configuration lambda executed on a temporary [TalonFXConfiguration] instance.
 *
 * @see TalonFX
 * @see TalonFXConfiguration
 */
/**
 * Applies one configuration to every motor and returns `true` only when every device reports an
 * OK status within [maxAttempts]. Mechanism initialization can use this checked variant to inhibit
 * outputs when current limits or closed-loop gains were not accepted by hardware.
 */
fun Iterable<TalonFX>.applyConfigChecked(
    maxAttempts: Int = 5,
    block: TalonFXConfiguration.() -> Unit
): Boolean {
    require(maxAttempts > 0) { "TalonFX config attempts must be positive" }
    val config = TalonFXConfiguration()
    config.block()
    var allApplied = true
    for (motor in this) {
        var lastStatus: StatusCode = StatusCode.OK
        var applied = false
        for (attempt in 0 until maxAttempts) {
            lastStatus = motor.configurator.apply(config)
            if (lastStatus.isOK) {
                applied = true
                break
            }
        }
        if (!applied) {
            allApplied = false
            DriverStation.reportError(
                "ARES: failed to apply TalonFX config to motor ${motor.deviceID} after $maxAttempts attempts (last status: $lastStatus)",
                false
            )
        }
    }
    return allApplied
}

/**
 * Batch-sets update frequencies ($Hz$) for CTRE Phoenix 6 [BaseStatusSignal] instances to optimize CANbus bandwidth utilization.
 *
 * @param hz Desired signal publishing frequency in Hertz ($Hz$).
 * @param signals Vararg list of CTRE status signals.
 *
 * @see BaseStatusSignal
 */
fun setUpdateFrequencies(hz: Double, vararg signals: BaseStatusSignal) {
    for (signal in signals) {
        signal.setUpdateFrequency(hz)
    }
}
