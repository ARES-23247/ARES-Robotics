package com.areslib.ftc.hardware

import com.areslib.util.RobotClock
import kotlin.math.exp

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
 * and maintains a conservative overload-energy estimate for the external 20A ATM battery fuse.
 * The Floodgate's independent 80A electronic cutoff and 60A internal fuse remain hardware-owned.
 *
 * ### Mathematical Formulations:
 * 1. Current conversion from analog telemetry voltage $V$:
 *    $$I_{raw} = \frac{V}{3.3} \cdot I_{max}$$
 * 2. Low-pass exponential filtering ($\alpha = \text{filterAlpha}$):
 *    $$I_{filtered} = \alpha \cdot I_{raw} + (1 - \alpha) \cdot I_{filtered, k-1}$$
 *    The first valid sample seeds the filter; invalid samples preserve its history.
 * 3. Fuse overload-energy model. Current at or below the fuse rating adds no damage; excess
 *    $I^2t$ accumulates above the rating: $E_k = E_{k-1} + (I^2-I_r^2)\Delta t$.
 *    Below the rating it cools exponentially: $E_k = E_{k-1}\exp(-\Delta t/\tau_c)$.
 *    Energy is normalized by a configurable calibration point, not a measured fuse trip curve.
 *
 * ### Physical Units:
 * - Telemetry Input: Volts ($V$), range $[0.0, 3.3] \text{ V}$.
 * - Current Draw: Amperes ($A$).
 * - Charge Consumption: Ampere-Seconds ($A \cdot s$) and Ampere-Hours ($Ah$).
 * - Energy Consumption: Watt-Hours ($Wh$, assuming nominal 12.0V bus).
 * - Fuse Thermal Strain: Percentage $[0.0\%, 100.0\%]$.
 *
 * @param analogInput Platform-neutral analog voltage supplier. FTC SDK adaptation belongs in the
 * platform host that owns the hardware map.
 * @param maxCurrentAmps Maximum current rating corresponding to 3.3V analog output ($A$, default 80A for Floodgate V2).
 * @param filterAlpha Low-pass smoothing alpha coefficient $[0.0, 1.0]$.
 * @param fuseRatingAmps Rating of the main battery fuse ($A$, default 20A FTC ATM fuse).
 * @param fuseCalibrationMultiple Current multiple expected to consume the model's full thermal
 * budget after [fuseCalibrationTripSeconds]. This must be replaced with measured or manufacturer
 * time-current data when a specific fuse family is characterized.
 * @param fuseCalibrationTripSeconds Conservative trip time at [fuseCalibrationMultiple].
 * @param fuseCoolingTimeConstantSeconds Thermal recovery time constant below the fuse rating.
 */
class FtcFloodgateCurrentSensor(
    private val analogInput: AnalogVoltageInput,
    private val maxCurrentAmps: Double = 80.0, // Scale: 3.3V corresponds to max current (default 80A for V2)
    private val filterAlpha: Double = 0.15,    // Low-pass filter smoothing coefficient (0.0 to 1.0)
    private val fuseRatingAmps: Double = 20.0, // Standard FTC main battery fuse rating
    private val fuseCalibrationMultiple: Double = 2.0,
    private val fuseCalibrationTripSeconds: Double = 2.0,
    private val fuseCoolingTimeConstantSeconds: Double = 15.0
) {

    init {
        require(maxCurrentAmps.isFinite() && maxCurrentAmps > 0.0) { "Maximum current must be finite and positive" }
        require(filterAlpha in 0.0..1.0) { "Filter alpha must be within [0, 1]" }
        require(fuseRatingAmps.isFinite() && fuseRatingAmps > 0.0) { "Fuse rating must be finite and positive" }
        require(fuseCalibrationMultiple.isFinite() && fuseCalibrationMultiple > 1.0) { "Calibration multiple must exceed one" }
        require(fuseCalibrationTripSeconds.isFinite() && fuseCalibrationTripSeconds > 0.0) { "Calibration time must be finite and positive" }
        require(fuseCoolingTimeConstantSeconds.isFinite() && fuseCoolingTimeConstantSeconds > 0.0) { "Cooling time constant must be finite and positive" }
    }

    private var lastUpdateTime = RobotClock.currentTimeMillis()
    private var filteredCurrentAmps = 0.0
    private var totalAmpSeconds = 0.0
    private var hasValidIntegrationSample = false
    private var hasFilterSample = false
    private var cachedInstantaneousCurrent = 0.0

    /** Whether the most recent analog sample was finite and inside the ADC's physical range. */
    var isReadingValid: Boolean = false
        private set

    // Conservative excess-I²t surrogate. This is deliberately parameterized rather than claiming
    // that every legal vendor's 20A ATM fuse has the same time-current curve.
    private var accumulatedThermalLoad = 0.0
    private val ratingSquared = fuseRatingAmps * fuseRatingAmps
    private val calibratedCurrent = fuseRatingAmps * fuseCalibrationMultiple
    private val fuseThermalCapacity =
        (calibratedCurrent * calibratedCurrent - ratingSquared) * fuseCalibrationTripSeconds

    init {
        require((maxCurrentAmps * maxCurrentAmps).isFinite() &&
            fuseThermalCapacity.isFinite() && fuseThermalCapacity > 0.0) {
            "Current square and thermal capacity must be representable; capacity must be positive"
        }
    }

    /**
     * Periodically updates the current measurements, applies the smoothing filter, 
     * and integrates total energy consumption. Should be called inside your main OpMode loop.
     * Integration requires consecutive valid observations; unknown intervals are omitted and
     * retain thermal history. Rewinds re-anchor without integrating negative elapsed time.
     * Charge totals therefore describe observed intervals, not guaranteed complete battery usage.
     */
    fun update() {
        val currentTime = RobotClock.currentTimeMillis()
        val elapsed = currentTime - lastUpdateTime
        val dtSeconds = if (!hasValidIntegrationSample || currentTime < lastUpdateTime) 0.0
            else if (elapsed >= 0L) elapsed / 1000.0
            else (currentTime.toDouble() - lastUpdateTime.toDouble()) / 1000.0
        lastUpdateTime = currentTime

        val sampledVoltage = try {
            analogInput.voltage
        } catch (_: Exception) {
            Double.NaN
        }
        isReadingValid = sampledVoltage.isFinite() && sampledVoltage in 0.0..3.3
        if (!isReadingValid) {
            hasValidIntegrationSample = false
            return
        }
        hasValidIntegrationSample = true

        val rawCurrent = sampledVoltage / 3.3 * maxCurrentAmps
        cachedInstantaneousCurrent = rawCurrent
        
        // 1. Apply Exponential Moving Average filter to smooth out spiky motor startup draws
        filteredCurrentAmps = if (!hasFilterSample) rawCurrent
            else (filterAlpha * rawCurrent) + ((1.0 - filterAlpha) * filteredCurrentAmps)
        hasFilterSample = true

        if (dtSeconds > 0.0) {
            // 2. Integrate current over time to compute charge usage (Ampere-Seconds)
            totalAmpSeconds = (totalAmpSeconds + rawCurrent * dtSeconds).coerceAtMost(Double.MAX_VALUE)

            // 3. Accumulate only energy above the continuous fuse rating. A rated 20A load must
            // not inevitably "blow" a 20A fuse in the software model.
            if (rawCurrent > fuseRatingAmps) {
                val heating = (rawCurrent * rawCurrent - ratingSquared) * dtSeconds
                accumulatedThermalLoad = (accumulatedThermalLoad + heating).coerceAtMost(Double.MAX_VALUE)
            } else if (rawCurrent < fuseRatingAmps && accumulatedThermalLoad > 0.0) {
                accumulatedThermalLoad *= exp(-dtSeconds / fuseCoolingTimeConstantSeconds)
            }
        }
    }

    /**
     * Reads the instantaneous, unfiltered current draw in Amperes.
     */
    val instantaneousCurrent: Double
        get() = if (isReadingValid) cachedInstantaneousCurrent else 0.0

    /**
     * Gets the smoothed, low-pass filtered current draw in Amperes.
     * Prevents false-alarms from instantaneous high-frequency motor noise.
     */
    val current: Double
        get() = if (isReadingValid) filteredCurrentAmps else 0.0

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
        require(warningThresholdAmps.isFinite() && warningThresholdAmps >= 0.0) { "Warning current must be finite and non-negative" }
        return maxOf(current, instantaneousCurrent) >= warningThresholdAmps ||
            fuseThermalLoadPercent >= 70.0
    }

    /**
     * Resets the energy integration tracker (e.g. at the beginning of a match).
     * Preserves the latest current/filter/validity state while starting a new charge/thermal interval.
     */
    fun resetTracker() {
        totalAmpSeconds = 0.0
        accumulatedThermalLoad = 0.0
        lastUpdateTime = RobotClock.currentTimeMillis()
    }
}
