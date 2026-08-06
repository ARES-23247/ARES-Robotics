package com.areslib.ftc.hardware

import com.qualcomm.robotcore.hardware.AnalogInput
import com.areslib.util.RobotClock

/**
 * Minimal abstraction interface for reading analog voltage signals ($V$).
 */
interface AnalogVoltageInput {
    /** Measured analog signal level in Volts ($V$). */
    val voltage: Double
}

/**
 * Driver and thermal monitoring model for the goBILDA Floodgate V2 Power Switch.
 *
 * Maps the $0.0\text{V} \dots 3.3\text{V}$ analog telemetry output of the Floodgate V2 to real-time current draw in Amperes ($A$).
 * Incorporates an exponential moving average low-pass filter to smooth motor starting spikes, integrates battery charge consumption ($Ah$, $Wh$),
 * and models thermal accumulation ($I^2 \cdot t$) to prevent tripping the 80A smart breaker or blowing the 20A main battery fuse.
 *
 * ### Mathematical Formulations:
 * 1. Current conversion from analog telemetry voltage $V$:
 *    $$I_{raw} = \frac{V}{3.3} \cdot I_{max}$$
 * 2. Low-pass exponential filtering ($\alpha = \text{filterAlpha}$):
 *    $$I_{filtered} = \alpha \cdot I_{raw} + (1 - \alpha) \cdot I_{filtered, k-1}$$
 * 3. Thermal accumulation model ($I^2 \cdot t$ heating with linear cooling):
 *    $$\text{Load}_k = \max\left(0, \text{Load}_{k-1} + I_{raw}^2 \Delta t - 0.1 \cdot \text{Load}_{k-1} \Delta t\right)$$
 *
 * ### Physical Units:
 * - Telemetry Input: Volts ($V$), range $[0.0, 3.3] \text{ V}$.
 * - Current Draw: Amperes ($A$).
 * - Charge Consumption: Ampere-Seconds ($A \cdot s$) and Ampere-Hours ($Ah$).
 * - Energy Consumption: Watt-Hours ($Wh$, assuming nominal 12.0V bus).
 * - Fuse Thermal Strain: Percentage $[0.0\%, 100.0\%]$.
 *
 * @param analogInput Analog voltage supplier interface ([AnalogVoltageInput] or FTC SDK [AnalogInput]).
 * @param maxCurrentAmps Maximum current rating corresponding to 3.3V analog output ($A$, default 80A for Floodgate V2).
 * @param filterAlpha Low-pass smoothing alpha coefficient $[0.0, 1.0]$.
 * @param fuseRatingAmps Rating of the main battery fuse ($A$, default 20A standard FTC fuse).
 */
class FtcFloodgateCurrentSensor @kotlin.jvm.JvmOverloads constructor(
    private val analogInput: AnalogVoltageInput,
    private val maxCurrentAmps: Double = 80.0, // Scale: 3.3V corresponds to max current (default 80A for V2)
    private val filterAlpha: Double = 0.15,    // Low-pass filter smoothing coefficient (0.0 to 1.0)
    private val fuseRatingAmps: Double = 20.0  // Standard FTC main battery fuse rating
) {

    // Secondary constructors for backward compatibility with Qualcomm's concrete AnalogInput class
    constructor(analogInput: AnalogInput) : this(
        object : AnalogVoltageInput {
            override val voltage: Double get() = analogInput.voltage
        }
    )

    constructor(analogInput: AnalogInput, maxCurrentAmps: Double) : this(
        object : AnalogVoltageInput {
            override val voltage: Double get() = analogInput.voltage
        },
        maxCurrentAmps
    )

    constructor(analogInput: AnalogInput, maxCurrentAmps: Double, filterAlpha: Double) : this(
        object : AnalogVoltageInput {
            override val voltage: Double get() = analogInput.voltage
        },
        maxCurrentAmps,
        filterAlpha
    )

    constructor(analogInput: AnalogInput, maxCurrentAmps: Double, filterAlpha: Double, fuseRatingAmps: Double) : this(
        object : AnalogVoltageInput {
            override val voltage: Double get() = analogInput.voltage
        },
        maxCurrentAmps,
        filterAlpha,
        fuseRatingAmps
    )

    private var lastUpdateTime = RobotClock.currentTimeMillis()
    private var filteredCurrentAmps = 0.0
    private var totalAmpSeconds = 0.0
    private var isInitialized = false

    // Thermal accumulation model to simulate a slow-blow thermal fuse behavior (I^2 * t)
    private var accumulatedThermalLoad = 0.0
    private val fuseThermalCapacity = fuseRatingAmps * fuseRatingAmps * 5.0 // Reference limit for max fuse rating running for 5 seconds

    /**
     * Periodically updates the current measurements, applies the smoothing filter, 
     * and integrates total energy consumption. Should be called inside your main OpMode loop.
     */
    fun update() {
        val currentTime = RobotClock.currentTimeMillis()
        val dtSeconds = if (isInitialized) {
            (currentTime - lastUpdateTime) / 1000.0
        } else {
            isInitialized = true
            0.0
        }
        lastUpdateTime = currentTime

        val rawCurrent = instantaneousCurrent
        
        // 1. Apply Exponential Moving Average filter to smooth out spiky motor startup draws
        filteredCurrentAmps = (filterAlpha * rawCurrent) + ((1.0 - filterAlpha) * filteredCurrentAmps)

        if (dtSeconds > 0.0) {
            // 2. Integrate current over time to compute charge usage (Ampere-Seconds)
            totalAmpSeconds += rawCurrent * dtSeconds

            // 3. Update I^2*t thermal accumulation modeling for the fuse
            // Heat generated is proportional to current squared (I^2 * R). 
            // Heat dissipated is proportional to ambient dissipation rate.
            val heating = rawCurrent * rawCurrent * dtSeconds
            val cooling = accumulatedThermalLoad * 0.1 * dtSeconds // Ambient heat dissipation
            accumulatedThermalLoad = (accumulatedThermalLoad + heating - cooling).coerceAtLeast(0.0)
        }
    }

    /**
     * Reads the instantaneous, unfiltered current draw in Amperes.
     */
    val instantaneousCurrent: Double
        get() {
            val voltage = analogInput.voltage
            // Floodgate analog telemetry scales linearly from 0V to 3.3V
            return (voltage / 3.3).coerceIn(0.0, 1.0) * maxCurrentAmps
        }

    /**
     * Gets the smoothed, low-pass filtered current draw in Amperes.
     * Prevents false-alarms from instantaneous high-frequency motor noise.
     */
    val current: Double
        get() = filteredCurrentAmps

    /**
     * Returns the accumulated electrical charge consumed by the robot in Ampere-Hours (Ah).
     */
    val totalAmpHours: Double
        get() = totalAmpSeconds / 3600.0

    /**
     * Estimates the total electrical energy consumed by the robot in Watt-Hours (Wh) 
     * assuming a nominal 12V battery system.
     */
    val estimatedEnergyWattHours: Double
        get() = totalAmpHours * 12.0

    /**
     * Calculates the estimated thermal strain on the main battery fuse as a percentage (0.0 to 100.0).
     * Proactively warn the driver when this nears 80% to avoid sudden loss of power!
     */
    val fuseThermalLoadPercent: Double
        get() = (accumulatedThermalLoad / fuseThermalCapacity * 100.0).coerceIn(0.0, 100.0)

    /**
     * Returns true if the robot's current draw or thermal load indicates a high risk 
     * of tripping the Floodgate V2 smart current limit or blowing the main battery fuse.
     */
    fun isOverloadWarning(warningThresholdAmps: Double = 18.0): Boolean {
        return current >= warningThresholdAmps || fuseThermalLoadPercent > 80.0
    }

    /**
     * Resets the energy integration tracker (e.g. at the beginning of a match).
     */
    fun resetTracker() {
        totalAmpSeconds = 0.0
        accumulatedThermalLoad = 0.0
        lastUpdateTime = RobotClock.currentTimeMillis()
    }
}
