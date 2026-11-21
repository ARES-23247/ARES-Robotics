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

        private const val MODE_NONE = 0
        private const val MODE_SOLID = 1
        private const val MODE_RAINBOW = 2
        private const val MODE_ARTBOARD = 3
    }

    private val i2cDevice: I2cDeviceSynch = hardwareMap.get(I2cDeviceSynch::class.java, name)

    /** Active equivalent PWM pulse width representation in microseconds ($\mu s$). */
    override var currentPulseWidthUs: Int = 1000
        private set

    /** Maximum global LED brightness cap percentage $[0, 100]$. */
    override var maxBrightnessPercent: Int = 75

    @Volatile private var closed = false
    private val word32Buffer = ByteArray(4)
    private val pairBuffer = ByteArray(2)
    private val rgbBuffer = ByteArray(4)
    private var lastCommandMode = MODE_NONE
    private var lastCommandBrightness = -1
    private var lastCommandR = -1
    private var lastCommandG = -1
    private var lastCommandB = -1
    private var lastArtboardSlot = -1

    init {
        i2cDevice.i2cAddress = I2cAddr.create7bit(i2cAddress)
    }

    /**
     * Reads the device hardware identification byte from register `0x00` (default return value `0x03`).
     *
     * @return Hardware device ID byte.
     */
    fun readDeviceId(): Byte {
        check(!closed) { "FtcPrismDriverI2cIO is closed" }
        return i2cDevice.read8(REG_DEVICE_ID)
    }

    private fun invalidateCache() {
        lastCommandMode = MODE_NONE
        lastCommandBrightness = -1
        lastCommandR = -1
        lastCommandG = -1
        lastCommandB = -1
        lastArtboardSlot = -1
    }

    private fun write32Bit(reg: Int, value: Int) {
        word32Buffer[0] = (value and 0xFF).toByte()
        word32Buffer[1] = ((value shr 8) and 0xFF).toByte()
        word32Buffer[2] = ((value shr 16) and 0xFF).toByte()
        word32Buffer[3] = ((value shr 24) and 0xFF).toByte()
        i2cDevice.write(reg, word32Buffer)
    }

    private fun writeLayerPair(paramId: Int, value: Int) {
        pairBuffer[0] = paramId.toByte()
        pairBuffer[1] = value.toByte()
        i2cDevice.write(REG_LAYER_0, pairBuffer)
    }

    /**
     * Sends a control bit command clearing all active animations from the LED strip.
     */
    fun clearAnimations() {
        if (closed) return
        invalidateCache()
        // Control register bit 25 = Clear animations
        write32Bit(REG_CONTROL, (1 shl 25))
    }

    /**
     * Loads a pre-programmed user Artboard animation preset from EEPROM slot $[0 \dots 7]$.
     *
     * @param slot EEPROM artboard slot index $[0, 7]$.
     */
    fun loadArtboard(slot: Int) {
        if (closed) return
        val clampedSlot = slot.coerceIn(0, 7)
        if (lastCommandMode == MODE_ARTBOARD && lastArtboardSlot == clampedSlot) return
        val command = (1 shl (8 + clampedSlot))
        try {
            write32Bit(REG_SAVE_LOAD_ARTBOARD, command)
            lastCommandMode = MODE_ARTBOARD
            lastArtboardSlot = clampedSlot
        } catch (failure: Throwable) {
            invalidateCache()
            throw failure
        }
    }

    /**
     * Saves the current active animation layers into an EEPROM Artboard slot $[0 \dots 7]$.
     *
     * @param slot EEPROM artboard slot index $[0, 7]$.
     */
    fun saveArtboard(slot: Int) {
        if (closed) return
        val clampedSlot = slot.coerceIn(0, 7)
        val command = (1 shl clampedSlot)
        invalidateCache()
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
        if (closed) return
        val brightnessCap = maxBrightnessPercent.coerceIn(0, 100)
        val scale = brightnessCap / 100.0
        val red = (r.coerceIn(0, 255) * scale).toInt()
        val green = (g.coerceIn(0, 255) * scale).toInt()
        val blue = (b.coerceIn(0, 255) * scale).toInt()
        if (lastCommandMode == MODE_SOLID &&
            lastCommandBrightness == brightnessCap &&
            lastCommandR == red &&
            lastCommandG == green &&
            lastCommandB == blue
        ) {
            return
        }

        try {
            writeLayerPair(0x00, ANIM_SOLID_COLOR)
            writeLayerPair(0x01, brightnessCap)
            writeLayerPair(0x02, 0)
            writeLayerPair(0x03, 255)
            rgbBuffer[0] = 0x04
            rgbBuffer[1] = red.toByte()
            rgbBuffer[2] = green.toByte()
            rgbBuffer[3] = blue.toByte()
            i2cDevice.write(REG_LAYER_0, rgbBuffer)
            lastCommandMode = MODE_SOLID
            lastCommandBrightness = brightnessCap
            lastCommandR = red
            lastCommandG = green
            lastCommandB = blue
        } catch (failure: Throwable) {
            invalidateCache()
            throw failure
        }
    }

    /**
     * Configures Layer 0 with a full-spectrum rainbow scrolling animation.
     *
     * @param brightness Animation peak brightness percentage $[0, 100]$ (default 100).
     */
    fun setRainbowAnimation(brightness: Int = 100) {
        if (closed) return
        val cappedBrightness = ((brightness.coerceIn(0, 100) * (maxBrightnessPercent.coerceIn(0, 100) / 100.0))).toInt()
        if (lastCommandMode == MODE_RAINBOW && lastCommandBrightness == cappedBrightness) return
        try {
            writeLayerPair(0x00, ANIM_RAINBOW)
            writeLayerPair(0x01, cappedBrightness)
            writeLayerPair(0x02, 0)
            writeLayerPair(0x03, 255)
            lastCommandMode = MODE_RAINBOW
            lastCommandBrightness = cappedBrightness
        } catch (failure: Throwable) {
            invalidateCache()
            throw failure
        }
    }

    /**
     * PWM compatibility hook translating legacy pulse width signals $[500\mu s, 2500\mu s]$ into direct I2C commands.
     *
     * @param pulseWidthUs PWM pulse width duration in microseconds ($\mu s$).
     */
    override fun setPulseWidthUs(pulseWidthUs: Int) {
        if (closed) return
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
        if (closed) return
        try {
            safe()
        } finally {
            closed = true
        }
    }
}
