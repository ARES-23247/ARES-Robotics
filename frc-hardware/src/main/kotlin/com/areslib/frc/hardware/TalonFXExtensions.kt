package com.areslib.frc.hardware

import com.ctre.phoenix6.BaseStatusSignal
import com.ctre.phoenix6.configs.TalonFXConfiguration
import com.ctre.phoenix6.hardware.TalonFX

/**
 * Batch-applies a [TalonFXConfiguration] block across an iterable collection of [TalonFX] motor controllers.
 *
 * @receiver Collection of physical CTRE [TalonFX] motor controller instances.
 * @param block Configuration lambda executed on a temporary [TalonFXConfiguration] instance.
 *
 * @see TalonFX
 * @see TalonFXConfiguration
 */
fun Iterable<TalonFX>.applyConfig(block: TalonFXConfiguration.() -> Unit) {
    val config = TalonFXConfiguration()
    config.block()
    for (motor in this) {
        motor.configurator.apply(config)
    }
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

