package com.areslib.ftc.hardware

import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.hardware.actuator.IndicatorLightIO
import com.areslib.telemetry.ITelemetry
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.Servo
import kotlin.math.abs

/**
 * Hardware IO actuator wrapper for the GoBilda RGB Indicator Light (SKU 3118-0808-0002).
 *
 * Connects to a standard PWM servo output port on REV Control or Expansion Hubs. Color selection is controlled
 * by mapping normalized servo positions $[0.0, 1.0]$ across the GoBilda color spectrum gradient.
 *
 * ### Physical Units & Bus Optimization:
 * - Position Signal: Normalized PWM servo duty cycle $[0.0, 1.0]$.
 * - Servo Position Tolerance: $0.001$ change threshold prevents redundant REV I2C writes.
 * - Hardware Mode: Output-only actuator device.
 *
 * @param hardwareMap FTC OpMode hardware map instance.
 * @param name Hardware map name for the indicator light servo.
 *
 * @see IndicatorLightIO
 * @see IndicatorLightColor
 */
class FtcIndicatorLightIO(
    hardwareMap: HardwareMap,
    val name: String
) : IndicatorLightIO, AutoCloseable {

    private val servo: Servo = hardwareMap.get(Servo::class.java, name)
    private var lastSentPosition = Double.NaN

    override var currentPosition: Double = 0.0
        private set

    /**
     * Commands the indicator light servo to a new normalized position $[0.0, 1.0]$.
     * Skips write execution if delta position is below $0.001$ tolerance.
     *
     * @param position Target normalized servo position $[0.0, 1.0]$.
     */
    override fun setPosition(position: Double) {
        val clamped = position.coerceIn(0.0, 1.0)
        currentPosition = clamped
        // Skip redundant writes to avoid I2C bus congestion
        if (lastSentPosition.isNaN() || abs(clamped - lastSentPosition) > 0.001) {
            servo.position = clamped
            lastSentPosition = clamped
        }
    }

    /**
     * Resets the indicator light to the [IndicatorLightColor.OFF] state ($0.0$ position).
     */
    override fun safe() {
        setPosition(IndicatorLightColor.OFF.position)
    }

    /**
     * No-op refresh hook (actuator is write-only).
     */
    override fun refresh() {
        // Write-only device — no sensor reads needed
    }

    /**
     * Logs current indicator light servo position to network telemetry.
     *
     * @param telemetry Telemetry sink instance.
     * @param prefix Telemetry topic prefix string.
     */
    override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
        telemetry.putNumber("$prefix/Position", currentPosition)
    }

    /**
     * Safely turns off the indicator light and releases hardware references.
     */
    override fun close() {
        safe()
    }
}

