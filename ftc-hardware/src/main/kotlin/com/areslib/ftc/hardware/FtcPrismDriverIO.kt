package com.areslib.ftc.hardware

import com.areslib.hardware.actuator.PrismDriverIO
import com.areslib.hardware.actuator.PrismPwmPreset
import com.areslib.telemetry.ITelemetry
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.Servo
import kotlin.math.abs

/**
 * FTC Hardware implementation for the goBILDA Prism RGB LED Driver (SKU 3118-2855-0001).
 *
 * PWM Control Mode (500µs - 2500µs):
 * Connects to a standard Servo port on the REV Control Hub or Expansion Hub.
 *
 * IMPORTANT HARDWARE SAFETY CAUTION:
 * When connecting the Prism Driver's PWM port to a REV Control Hub or Expansion Hub,
 * DO NOT connect the center red (5V) wire on the servo cable. Both the REV Hub and
 * Prism Driver output 5V power. Connecting the red wire can permanently damage your REV Hub.
 *
 * @param hardwareMap The FTC HardwareMap.
 * @param name The hardware map name configured in the FTC Driver Station app (default: "prism").
 */
class FtcPrismDriverIO(
    hardwareMap: HardwareMap,
    val name: String = "prism"
) : PrismDriverIO, AutoCloseable {

    private val servo: Servo = hardwareMap.get(Servo::class.java, name)
    private var lastSentPulseWidth = -1

    override var currentPulseWidthUs: Int = 1000
        private set

    override var maxBrightnessPercent: Int = 75

    override fun setPulseWidthUs(pulseWidthUs: Int) {
        val clampedUs = pulseWidthUs.coerceIn(500, 2500)
        currentPulseWidthUs = clampedUs

        if (abs(clampedUs - lastSentPulseWidth) >= 2) {
            // Map 500µs–2500µs to standard 0.0–1.0 servo position
            val normalizedPos = (clampedUs - 500.0) / 2000.0
            servo.position = normalizedPos
            lastSentPulseWidth = clampedUs
        }
    }

    override fun setSolidColorRgb(r: Int, g: Int, b: Int) {
        // Convert RGB hue to PWM 1050µs - 1949µs range
        val rNorm = (r.coerceIn(0, 255)) / 255.0
        val gNorm = (g.coerceIn(0, 255)) / 255.0
        val bNorm = (b.coerceIn(0, 255)) / 255.0

        val maxC = maxOf(rNorm, gNorm, bNorm)
        val minC = minOf(rNorm, gNorm, bNorm)
        val delta = maxC - minC

        val hue = when {
            delta < 1e-4 -> 0.0
            maxC == rNorm -> ((gNorm - bNorm) / delta) % 6.0
            maxC == gNorm -> ((bNorm - rNorm) / delta) + 2.0
            else -> ((rNorm - gNorm) / delta) + 4.0
        } * 60.0

        val normalizedHue = (if (hue < 0) hue + 360.0 else hue) / 360.0
        val pulseWidth = (1050 + normalizedHue * 899.0).toInt()
        setPulseWidthUs(pulseWidth)
    }

    override fun safe() {
        setPreset(PrismPwmPreset.SOLID_RED)
    }

    override fun refresh() {
        // Write-only device — no sensor reads needed
    }

    override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
        telemetry.putNumber("$prefix/PulseWidthUs", currentPulseWidthUs.toDouble())
    }

    override fun close() {
        safe()
    }
}
