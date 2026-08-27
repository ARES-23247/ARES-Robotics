package com.areslib.ftc.hardware

import com.areslib.hardware.actuator.PrismDriverIO
import com.areslib.hardware.actuator.PrismPwmPreset
import com.areslib.telemetry.ITelemetry
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.I2cAddr
import com.qualcomm.robotcore.hardware.I2cDeviceSynch
/**
 * Direct I2C hardware IO driver for the Blinkin/Prism LED Strip Controller module.
 *
 * Interacts over I2C (default address `0x38`) using 32-bit register payloads to control animations,
 * solid colors, brightness levels, and EEPROM Artboard slots (0..7).
 * Implements [PrismDriverIO] and maps legacy PWM pulse widths ($500\mu s \dots 2500\mu s$) to equivalent I2C animation patterns.
 *
 * ### Physical Units & Protocol Specifications:
 * - Default I2C Address: 7-bit `0x38`.
 * - Color Channels ($R, G, B$): Unscaled 8-bit integers $[0, 255]$.
 * - Maximum Brightness: Percentage $[0\%, 100\%]$ (default 75%).
 * - PWM Compatibility Range: Microseconds ($\mu s$), range $[500, 2500]$.
 *
 * @param hardwareMap FTC OpMode hardware map instance.
 * @param name Hardware map name string for the Prism device (default `"prism"`).
 * @param i2cAddress 7-bit I2C device address (default `0x38`).
 *
 * @see PrismDriverIO
 * @see I2cDeviceSynch
 */
class FtcPrismDriverI2cIO @JvmOverloads constructor(
    hardwareMap: HardwareMap,
    val name: String = "prism",
    i2cAddress: Int = DEFAULT_I2C_ADDRESS
) : PrismDriverIO, AutoCloseable {

    companion object {
        /** Default 7-bit I2C slave address for the Prism driver module. */
        const val DEFAULT_I2C_ADDRESS = 0x38

        // I2C Register Addresses
        const val REG_DEVICE_ID = 0x00
        const val REG_FIRMWARE_VER = 0x01
        const val REG_HARDWARE_VER = 0x02
        const val REG_POWER_CYCLES = 0x03
        const val REG_RUNTIME_MINS = 0x04
        const val REG_STATUS = 0x05
        const val REG_CONTROL = 0x06
        const val REG_SAVE_LOAD_ARTBOARD = 0x07
        const val REG_LAYER_0 = 0x08

        // Animation IDs (Layer Sub-Register 0x00)
        const val ANIM_NONE = 0
        const val ANIM_SOLID_COLOR = 1
        const val ANIM_BLINKING = 2
        const val ANIM_PULSING = 3
        const val ANIM_SINE_WAVE = 4
        const val ANIM_DROID_SCAN = 5
        const val ANIM_RAINBOW = 6
        const val ANIM_SNAKES = 7
        const val ANIM_RANDOM = 8
        const val ANIM_SPARKLE = 9
        const val ANIM_SINGLE_FILL = 10
        const val ANIM_RAINBOW_SNAKES = 11
        const val ANIM_POLICE_LIGHTS = 12
    }

    private val i2cDevice: I2cDeviceSynch = hardwareMap.get(I2cDeviceSynch::class.java, name)

    /** Active equivalent PWM pulse width representation in microseconds ($\mu s$). */
    override var currentPulseWidthUs: Int = 1000
        private set

    /** Maximum global LED brightness cap percentage $[0, 100]$. */
    override var maxBrightnessPercent: Int = 75

    init {
        i2cDevice.i2cAddress = I2cAddr.create7bit(i2cAddress)
    }

    /**
     * Reads the device hardware identification byte from register `0x00` (default return value `0x03`).
     *
     * @return Hardware device ID byte.
     */
    fun readDeviceId(): Byte {
        return i2cDevice.read8(REG_DEVICE_ID)
    }

    private fun write32Bit(reg: Int, value: Int) {
        val bytes = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
        i2cDevice.write(reg, bytes)
    }

    /**
     * Sends a control bit command clearing all active animations from the LED strip.
     */
    fun clearAnimations() {
        // Control register bit 25 = Clear animations
        write32Bit(REG_CONTROL, (1 shl 25))
    }

    /**
     * Loads a pre-programmed user Artboard animation preset from EEPROM slot $[0 \dots 7]$.
     *
     * @param slot EEPROM artboard slot index $[0, 7]$.
     */
    fun loadArtboard(slot: Int) {
        val clampedSlot = slot.coerceIn(0, 7)
        val command = (1 shl (8 + clampedSlot))
        write32Bit(REG_SAVE_LOAD_ARTBOARD, command)
    }

    /**
     * Saves the current active animation layers into an EEPROM Artboard slot $[0 \dots 7]$.
     *
     * @param slot EEPROM artboard slot index $[0, 7]$.
     */
    fun saveArtboard(slot: Int) {
        val clampedSlot = slot.coerceIn(0, 7)
        val command = (1 shl clampedSlot)
        write32Bit(REG_SAVE_LOAD_ARTBOARD, command)
    }

    /**
     * Configures Layer 0 with a solid RGB color scaled by [maxBrightnessPercent].
     *
     * @param r Red intensity $[0, 255]$.
     * @param g Green intensity $[0, 255]$.
     * @param b Blue intensity $[0, 255]$.
     */
    override fun setSolidColorRgb(r: Int, g: Int, b: Int) {
        val scale = maxBrightnessPercent.coerceIn(0, 100) / 100.0
        val red = (r.coerceIn(0, 255) * scale).toInt()
        val green = (g.coerceIn(0, 255) * scale).toInt()
        val blue = (b.coerceIn(0, 255) * scale).toInt()

        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x00, ANIM_SOLID_COLOR.toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x01, maxBrightnessPercent.coerceIn(0, 100).toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x02, 0.toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x03, 255.toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x04, red.toByte(), green.toByte(), blue.toByte()))
    }

    /**
     * Configures Layer 0 with a full-spectrum rainbow scrolling animation.
     *
     * @param brightness Animation peak brightness percentage $[0, 100]$ (default 100).
     */
    fun setRainbowAnimation(brightness: Int = 100) {
        val cappedBrightness = ((brightness.coerceIn(0, 100) * (maxBrightnessPercent.coerceIn(0, 100) / 100.0))).toInt()
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x00, ANIM_RAINBOW.toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x01, cappedBrightness.toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x02, 0.toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x03, 255.toByte()))
    }

    /**
     * PWM compatibility hook translating legacy pulse width signals $[500\mu s, 2500\mu s]$ into direct I2C commands.
     *
     * @param pulseWidthUs PWM pulse width duration in microseconds ($\mu s$).
     */
    override fun setPulseWidthUs(pulseWidthUs: Int) {
        val clampedUs = pulseWidthUs.coerceIn(500, 2500)
        currentPulseWidthUs = clampedUs

        when (clampedUs) {
            in 500..579 -> {
                val slot = (clampedUs - 500) / 10
                loadArtboard(slot)
            }
            in 1000..1009 -> setRainbowAnimation()
            in 1050..1949 -> {
                val r = if (clampedUs < 1350) 255 else 0
                val g = if (clampedUs in 1200..1650) 255 else 0
                val b = if (clampedUs > 1500) 255 else 0
                setSolidColorRgb(r, g, b)
            }
            else -> setRainbowAnimation()
        }
    }

    /**
     * Clears all animations and sets the driver to a safe idle state.
     */
    override fun safe() {
        clearAnimations()
    }

    /**
     * No-op refresh hook (actuator device is write-only).
     */
    override fun refresh() {
        // Write-only device — no sensor reads needed
    }

    /**
     * Logs active equivalent pulse width duration to network telemetry.
     *
     * @param telemetry Telemetry sink instance.
     * @param prefix Telemetry topic prefix string.
     */
    override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
        telemetry.putNumber("$prefix/PulseWidthUs", currentPulseWidthUs.toDouble())
    }

    /**
     * Safely clears LED animations and releases hardware device handles.
     */
    override fun close() {
        safe()
    }
}
