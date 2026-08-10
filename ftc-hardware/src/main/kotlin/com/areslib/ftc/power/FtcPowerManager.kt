package com.areslib.ftc.power

import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.qualcomm.robotcore.hardware.AnalogInput
import com.areslib.control.safety.BrownoutGuard
import com.areslib.control.safety.CurrentBudgetManager
import com.areslib.ftc.hardware.FtcFloodgateCurrentSensor
import com.areslib.hardware.actuator.MotorIO
import com.areslib.subsystem.PowerManager

/**
 * FTC electrical safety coordinator with a hardware-current-sensor fallback policy.
 *
 * Voltage is sampled from the first configured FTC [VoltageSensor] at most every 100 ms and cached
 * between samples. Each [update] advances [BrownoutGuard], then uses a Floodgate sensor when present
 * or lazily creates a [CurrentBudgetManager] otherwise. The minimum resulting scale is copied to all
 * motors currently registered in the global hardware registry.
 *
 * Hardware reads occur only from [update]; [batteryVoltage], [powerScale], and [currentAmps] expose
 * cached/registered state. This class is single-loop-owned and is not thread-safe.
 */
class FtcPowerManager(private val hardwareMap: HardwareMap) : PowerManager {
    private var lastVoltageReadTime = 0L
    private var cachedBatteryVoltage = 12.0

    /** Brownout protection guard — auto-scales motor power on voltage sag */
    val brownoutGuard = BrownoutGuard.ftcDefaults()

    /** Floodgate V2 current sensor — null if no Floodgate is connected */
    val floodgate: FtcFloodgateCurrentSensor? = try {
        val analogInput = hardwareMap.get(AnalogInput::class.java, "floodgate")
        FtcFloodgateCurrentSensor(analogInput)
    } catch (_: Throwable) {
        null
    }

    /** Software current budget manager — used as a fallback if no Floodgate sensor is found */
    var currentBudgetManager: CurrentBudgetManager? = null

    override var batteryVoltage = 12.0
        private set
    override var powerScale = 1.0
        private set

    /**
     * Total current draw of the robot in amperes.
     * Returns the Floodgate's cached reading when installed, otherwise the sum of registered motors'
     * cached current estimates.
     */
    override val currentAmps: Double
        get() = floodgate?.current ?: com.areslib.hardware.HardwareRegistry.getRegisteredMotors().sumOf { it.currentAmps }

    /**
     * Updates the battery voltage reading (rate-limited to 10Hz) and recalculates power scaling.
     *
     * @param dtSeconds Loop cycle delta time in seconds.
     * @param timestampMs Monotonic robot timestamp in milliseconds, normally from `RobotClock`.
     * @return The calculated power scale factor (0.0 to 1.0).
     */
    override fun update(dtSeconds: Double, timestampMs: Long): Double {
        // Fetch voltage sensor for sag compensation (rate-limited to 10Hz/100ms to eliminate blocking JNI overhead)
        if (timestampMs - lastVoltageReadTime > 100 || lastVoltageReadTime == 0L) {
            lastVoltageReadTime = timestampMs
            val voltageSensors = hardwareMap.getAll(VoltageSensor::class.java)
            val newVoltage = if (voltageSensors.isNotEmpty()) {
                voltageSensors[0].voltage
            } else {
                12.0
            }
            
            // Apply a low-pass filter (time constant ~100ms) to prevent positive feedback sag oscillations during rapid acceleration
            val alpha = dtSeconds / (0.1 + dtSeconds)
            cachedBatteryVoltage = (cachedBatteryVoltage * (1.0 - alpha)) + (newVoltage * alpha)
        }
        batteryVoltage = cachedBatteryVoltage

        // 1. Brownout protection — graduated power scaling on voltage sag
        brownoutGuard.update(batteryVoltage)
        var scale = brownoutGuard.powerScale

        // 2. Floodgate current protection — throttle on overload (or software fallback)
        val motors = com.areslib.hardware.HardwareRegistry.getRegisteredMotors()
        floodgate?.let { fg ->
            fg.update()
            if (fg.isOverloadWarning()) {
                // Graduated: scale inversely with fuse thermal load
                val fuseScale = (1.0 - fg.fuseThermalLoadPercent / 100.0).coerceIn(0.2, 1.0)
                scale = minOf(scale, fuseScale)
            }
        } ?: run {
            var cbm = currentBudgetManager
            if (cbm == null) {
                cbm = CurrentBudgetManager.ftcDefaults()
                currentBudgetManager = cbm
            }
            for (m in motors) {
                if (!cbm.isRegistered(m)) {
                    cbm.register(m)
                }
            }
            cbm.update(batteryVoltage, enableCalibration = true)
            scale = minOf(scale, cbm.powerScale)
        }

        powerScale = scale
        
        // Dynamically distribute final powerScale to all registered motors
        for (m in motors) {
            m.powerScale = scale
        }
        
        return scale
    }
}

