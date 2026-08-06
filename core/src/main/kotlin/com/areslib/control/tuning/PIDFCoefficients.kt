package com.areslib.control.tuning

/**
 * Reusable Gain Coefficient Container for Closed-Loop PIDF Controllers.
 *
 * Encapsulates feedback gains ($k_P, k_I, k_D$) and static feedforward gain ($k_F$) for linear mechanisms, rotating joints, or flywheels.
 *
 * ### Control Law Formulation:
 * $$u(k) = k_P \cdot e(k) + k_I \cdot \sum e(i) \Delta t + k_D \cdot \frac{e(k) - e(k-1)}{\Delta t} + k_F \cdot r(k)$$
 *
 * ### Physical Units & Properties:
 * - Proportional Gain ($k_P$): Control output per unit error ($V / \text{unit}$)
 * - Integral Gain ($k_I$): Control output per unit error second ($V / (\text{unit} \cdot s)$)
 * - Derivative Gain ($k_D$): Control output per unit velocity error ($V \cdot s / \text{unit}$)
 * - Feedforward Gain ($k_F$): Control output per setpoint unit ($V / \text{unit}$)
 *
 * @property kP Proportional feedback gain coefficient ($k_P$).
 * @property kI Integral feedback gain coefficient ($k_I$).
 * @property kD Derivative feedback gain coefficient ($k_D$).
 * @property kF Direct static setpoint feedforward gain ($k_F$).
 * @see com.areslib.control.feedback.PIDController
 */
data class PIDFCoefficients(
    val kP: Double = 0.0,
    val kI: Double = 0.0,
    val kD: Double = 0.0,
    val kF: Double = 0.0
)
