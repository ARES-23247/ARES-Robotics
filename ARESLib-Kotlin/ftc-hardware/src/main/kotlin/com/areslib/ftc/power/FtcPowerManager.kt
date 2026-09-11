package com.areslib.ftc.power

import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.qualcomm.robotcore.hardware.AnalogInput
import com.areslib.control.safety.BrownoutGuard
import com.areslib.control.safety.CurrentBudgetManager
import com.areslib.ftc.hardware.FtcFloodgateCurrentSensor
import com.areslib.ftc.hardware.AnalogVoltageInput
import com.areslib.hardware.actuator.MotorIO
import com.areslib.subsystem.PowerManager

/**
 * FTC electrical safety coordinator with a hardware-current-sensor fallback policy.
 *
 * Voltage is sampled from the first configured FTC [VoltageSensor] at up to 50 Hz. The raw sample
 * drives [BrownoutGuard] immediately, while a 100 ms low-pass value is retained for compensation
 * and telemetry. Each [update] also advances the 20 A software fuse budget; a plausible Floodgate
 * reading supersedes that estimate. The minimum resulting scale is copied to all registered motors.
 *
 * Hardware reads occur only from [update]; [batteryVoltage], [powerScale], and [currentAmps] expose
 * cached/registered state. This class is single-loop-owned and is not thread-safe.
 */
class FtcPowerManager(
    private val hardwareMap: HardwareMap,
    private val hardwareRegistry: com.areslib.hardware.HardwareRegistry,
) : PowerManager {
    private var lastVoltageReadTime = 0L
    private var hasVoltageReadTime = false
    private var trackedBudgetManager: CurrentBudgetManager? = null
    private val trackedMotors = ArrayList<MotorIO>(8)
    private var cachedBatteryVoltage = 12.0
    private var rawBatteryVoltage = 12.0
    private var hasValidVoltageSample = false
    private var cachedCurrentAmps = 0.0
    private val voltageSensor: VoltageSensor? = hardwareMap.getAll(VoltageSensor::class.java).firstOrNull()

    /** Brownout protection guard — auto-scales motor power on voltage sag */
    val brownoutGuard = BrownoutGuard.ftcDefaults()

    /** Floodgate V2 current sensor — null if no Floodgate is connected */
    val floodgate: FtcFloodgateCurrentSensor? = try {
        val analogInput = hardwareMap.get(AnalogInput::class.java, "floodgate")
        FtcFloodgateCurrentSensor(object : AnalogVoltageInput {
            override val voltage: Double get() = analogInput.voltage
        })
    } catch (_: Throwable) {
        null
    }

    /** Software 20 A fuse budget — always advanced and used whenever Floodgate data is implausible. */
    var currentBudgetManager: CurrentBudgetManager? = null

    override var batteryVoltage = 12.0
        private set
    override var powerScale = 1.0
        private set

    /**
     * Total current draw of the robot in amperes.
     * Returns a plausible Floodgate reading when available, otherwise the motor model reconciled
     * with cached branch measurements. Unknown uncovered loads remain NaN rather than zero.
     */
    override val currentAmps: Double
        get() = cachedCurrentAmps

    /**
     * Updates the battery voltage reading (rate-limited to 50 Hz) and recalculates power scaling.
     *
     * @param dtSeconds Loop cycle delta time in seconds.
     * @param timestampMs Monotonic robot timestamp in milliseconds, normally from `RobotClock`.
     * @return The calculated power scale factor (0.0 to 1.0).
     */
    override fun update(dtSeconds: Double, timestampMs: Long): Double {
        // Brownout prevention needs a fresh sag observation. Sample at up to 50 Hz and feed the
        // raw value to the guard; retain a correctly-timed low-pass value for voltage compensation
        // and telemetry so command normalization does not amplify rapid sag/recovery oscillations.
        val clockRewound = hasVoltageReadTime && timestampMs < lastVoltageReadTime
        val elapsedMs = if (!hasVoltageReadTime || clockRewound) VOLTAGE_SAMPLE_PERIOD_MS
            else (timestampMs - lastVoltageReadTime).let { if (it < 0L) Long.MAX_VALUE else it }
        if (!hasVoltageReadTime || clockRewound || elapsedMs >= VOLTAGE_SAMPLE_PERIOD_MS) {
            lastVoltageReadTime = timestampMs
            hasVoltageReadTime = true
            if (clockRewound) hasValidVoltageSample = false
            val newVoltage = try {
                voltageSensor?.voltage ?: Double.NaN
            } catch (_: Exception) {
                Double.NaN
            }
            if (!newVoltage.isFinite() || newVoltage <= 0.0) {
                rawBatteryVoltage = 0.0
                cachedBatteryVoltage = 0.0
                hasValidVoltageSample = false
            } else {
                rawBatteryVoltage = newVoltage
                val sampleDt = (elapsedMs / 1000.0).coerceAtLeast(0.001)
                val alpha = sampleDt / (VOLTAGE_FILTER_TIME_CONSTANT_SECONDS + sampleDt)
                cachedBatteryVoltage = if (!hasValidVoltageSample || !cachedBatteryVoltage.isFinite()) {
                    newVoltage
                } else {
                    cachedBatteryVoltage * (1.0 - alpha) + newVoltage * alpha
                }
                hasValidVoltageSample = true
            }
        }
        batteryVoltage = cachedBatteryVoltage

        // 1. Brownout protection — graduated power scaling on voltage sag
        brownoutGuard.update(rawBatteryVoltage)
        var scale = brownoutGuard.powerScale

        // 2. Always advance the model fallback. Beyond the drivetrain MotorIO slots, include all
        // registered mechanism current sources so shooter/intake load participates in the 20A fuse
        // budget. A valid Floodgate remains the authoritative total-current observation.
        val motors = hardwareRegistry.getRegisteredMotors()
        val softwareScale = updateSoftwareCurrentBudget(motors, batteryVoltage)
        val modeledCurrent = currentBudgetManager?.totalEstimatedAmps ?: 0.0
        val floodgateSensor = floodgate
        if (floodgateSensor != null) {
            val fg = floodgateSensor
            fg.update()
            val observedCurrent = maxOf(fg.current, fg.instantaneousCurrent)
            val sensorDisagreesUnderLoad = observedCurrent < FLOODGATE_ZERO_CURRENT_AMPS &&
                modeledCurrent >= FLOODGATE_EXPECTED_LOAD_AMPS
            if (!fg.isReadingValid || sensorDisagreesUnderLoad) {
                cachedCurrentAmps = modeledCurrent
                scale = minOf(scale, softwareScale)
            } else if (fg.isOverloadWarning()) {
                cachedCurrentAmps = observedCurrent
                val thermalScale = if (fg.fuseThermalLoadPercent < FUSE_THERMAL_WARNING_PERCENT) {
                    1.0
                } else {
                    val normalized = (fg.fuseThermalLoadPercent - FUSE_THERMAL_WARNING_PERCENT) /
                        (100.0 - FUSE_THERMAL_WARNING_PERCENT)
                    (1.0 - normalized * 0.8).coerceIn(0.2, 1.0)
                }
                val instantaneousScale = (FTC_CONTINUOUS_CURRENT_BUDGET_AMPS / observedCurrent)
                    .coerceIn(0.2, 1.0)
                scale = minOf(scale, thermalScale, instantaneousScale)
            } else {
                cachedCurrentAmps = observedCurrent
            }
        } else {
            cachedCurrentAmps = modeledCurrent
            scale = minOf(scale, softwareScale)
        }

        powerScale = scale
        
        // Dynamically distribute final powerScale to all registered motors
        var motorIndex = 0
        while (motorIndex < motors.size) {
            motors[motorIndex].powerScale = scale
            motorIndex++
        }
        
        return scale
    }

    private fun updateSoftwareCurrentBudget(motors: List<MotorIO>, batteryVoltage: Double): Double {
        val manager = currentBudgetManager ?: CurrentBudgetManager.ftcDefaults().also {
            currentBudgetManager = it
        }
        val managerChanged = trackedBudgetManager !== manager
        if (managerChanged) {
            trackedMotors.clear()
            trackedBudgetManager = manager
        }
        // Only remove slots previously owned by this registry. Explicit models for other motors
        // and learned calibration on surviving slots belong to the caller and must be preserved.
        var registryChanged = managerChanged || trackedMotors.size != motors.size
        if (!registryChanged) {
            var index = 0
            while (index < motors.size) {
                if (motors[index] !== trackedMotors[index]) { registryChanged = true; break }
                index++
            }
        }
        if (registryChanged) {
            for (trackedIndex in trackedMotors.indices) {
                val previous = trackedMotors[trackedIndex]
                var present = false
                var index = 0
                while (index < motors.size) {
                    if (motors[index] === previous) { present = true; break }
                    index++
                }
                if (!present) manager.unregister(previous)
            }
            trackedMotors.clear()
            for (index in motors.indices) trackedMotors.add(motors[index])
        }
        var motorIndex = 0
        while (motorIndex < motors.size) {
            val motor = motors[motorIndex]
            if (!manager.isRegistered(motor)) manager.register(motor)
            motorIndex++
        }
        manager.updateFromCurrentSources(
            batteryVoltage,
            hardwareRegistry.getRegisteredCurrentSources(),
            enableCalibration = true
        )
        return manager.powerScale
    }

    private companion object {
        const val VOLTAGE_SAMPLE_PERIOD_MS = 20L
        const val VOLTAGE_FILTER_TIME_CONSTANT_SECONDS = 0.10
        const val FTC_CONTINUOUS_CURRENT_BUDGET_AMPS = 18.0
        const val FUSE_THERMAL_WARNING_PERCENT = 70.0
        const val FLOODGATE_ZERO_CURRENT_AMPS = 0.25
        const val FLOODGATE_EXPECTED_LOAD_AMPS = 5.0
    }
}

