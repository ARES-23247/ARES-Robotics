package com.areslib.control.tuning

/**
 * Feedforward Gain Coefficients for Simple Velocity-Controlled Mechanisms (Flywheels, Drivetrains, Intakes).
 *
 * ### Mathematical Voltage Equation:
 * $$V = k_S \cdot \text{sign}(v) + k_V \cdot v + k_A \cdot a$$
 *
 * ### Physical Units:
 * - Static Friction ($k_S$): Volts ($V$)
 * - Velocity Gain ($k_V$): Volt-seconds per meter ($V \cdot s / m$) or Volt-seconds per radian ($V \cdot s / rad$)
 * - Acceleration Gain ($k_A$): Volt-seconds squared per meter ($V \cdot s^2 / m$) or Volt-seconds squared per radian ($V \cdot s^2 / rad$)
 *
 * @property kS Static friction voltage constant ($V$).
 * @property kV Velocity feedforward gain ($V \cdot s / \text{unit}$).
 * @property kA Acceleration feedforward gain ($V \cdot s^2 / \text{unit}$).
 */
data class SimpleFeedforwardCoeffs(
    val kS: Double = 0.0,
    val kV: Double = 0.0,
    val kA: Double = 0.0
)

/**
 * Feedforward Gain Coefficients for Linear Elevator Mechanisms Fighting Constant Gravity.
 *
 * ### Mathematical Voltage Equation:
 * $$V = k_S \cdot \text{sign}(v) + k_G + k_V \cdot v + k_A \cdot a$$
 *
 * ### Physical Units:
 * - Static Friction ($k_S$): Volts ($V$)
 * - Gravity Constant ($k_G$): Volts ($V$)
 * - Velocity Gain ($k_V$): Volt-seconds per meter ($V \cdot s / m$)
 * - Acceleration Gain ($k_A$): Volt-seconds squared per meter ($V \cdot s^2 / m$)
 *
 * @property kS Static friction voltage constant ($V$).
 * @property kG Gravity compensation voltage constant ($V$).
 * @property kV Velocity feedforward gain ($V \cdot s / m$).
 * @property kA Acceleration feedforward gain ($V \cdot s^2 / m$).
 */
data class ElevatorFeedforwardCoeffs(
    val kS: Double = 0.0,
    val kG: Double = 0.0,
    val kV: Double = 0.0,
    val kA: Double = 0.0
)

/**
 * Feedforward Gain Coefficients for Single-Joint Rotating Arm Mechanisms Fighting Position-Dependent Gravity.
 *
 * ### Mathematical Voltage Equation:
 * $$V = k_S \cdot \text{sign}(v) + k_G \cdot \cos(\theta) + k_V \cdot v + k_A \cdot a$$
 * *(where $\theta = 0\text{ rad}$ corresponds to the horizontal position).*
 *
 * ### Physical Units:
 * - Static Friction ($k_S$): Volts ($V$)
 * - Maximum Horizontal Gravity ($k_G$): Volts ($V$)
 * - Velocity Gain ($k_V$): Volt-seconds per radian ($V \cdot s / rad$)
 * - Acceleration Gain ($k_A$): Volt-seconds squared per radian ($V \cdot s^2 / rad$)
 *
 * @property kS Static friction voltage constant ($V$).
 * @property kG Maximum gravity compensation voltage constant at horizontal position ($V$).
 * @property kV Velocity feedforward gain ($V \cdot s / rad$).
 * @property kA Acceleration feedforward gain ($V \cdot s^2 / rad$).
 */
data class ArmFeedforwardCoeffs(
    val kS: Double = 0.0,
    val kG: Double = 0.0,
    val kV: Double = 0.0,
    val kA: Double = 0.0
)
