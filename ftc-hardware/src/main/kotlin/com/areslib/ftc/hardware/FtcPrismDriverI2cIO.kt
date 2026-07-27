package com.areslib.ftc.hardware

import com.areslib.hardware.actuator.PrismDriverIO
import com.areslib.hardware.actuator.PrismPwmPreset
import com.areslib.telemetry.ITelemetry
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.I2cAddr
import com.qualcomm.robotcore.hardware.I2cDeviceSynch
import com.qualcomm.robotcore.hardware.configuration.annotations.I2cDeviceType
import com.qualcomm.robotcore.hardware.configuration.annotations.DeviceProperties

/**
 * Native FTC I²C Hardware Driver for the goBILDA Prism RGB LED Driver (SKU 3118-2855-0001).
 *
 * Hardware Connections:
 * Connects to any REV Control Hub or Expansion Hub I²C Port (I2C 0, 1, 2, or 3)
 * via a 4-pos JST-PH cable. Default I²C 7-bit address is 0x38.
 *
 * Provides complete control over Layer 0-9 animations, solid colors, brightness,
 * clearing animations, and loading/saving Artboards to device EPROM memory.
 *
 * @param hardwareMap The FTC HardwareMap.
 * @param name Hardware configuration name in Driver Station App (default: "prism").
 * @param i2cAddress 7-bit I²C address (default: 0x38).
 */
@I2cDeviceType
@DeviceProperties(
    name = "goBILDA Prism RGB LED Driver",
    description = "I2C Driver for goBILDA Prism RGB LED Driver",
    xmlTag = "GoBildaPrism"
)
class FtcPrismDriverI2cIO @JvmOverloads constructor(
    hardwareMap: HardwareMap,
    val name: String = "prism",
    i2cAddress: Int = DEFAULT_I2C_ADDRESS
) : PrismDriverIO, AutoCloseable {

    companion object {
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
    override var currentPulseWidthUs: Int = 1000
        private set

    init {
        i2cDevice.i2cAddress = I2cAddr.create7bit(i2cAddress)
    }

    /**
     * Reads the device ID (Default is 0x03).
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
     * Clears all active animations from the strip.
     */
    fun clearAnimations() {
        // Control register bit 25 = Clear animations
        write32Bit(REG_CONTROL, (1 shl 25))
    }

    /**
     * Loads a user-saved Artboard (Slots 0 to 7) from EPROM.
     */
    fun loadArtboard(slot: Int) {
        val clampedSlot = slot.coerceIn(0, 7)
        val command = (1 shl (8 + clampedSlot))
        write32Bit(REG_SAVE_LOAD_ARTBOARD, command)
    }

    /**
     * Saves current displayed animations to an EPROM Artboard slot (Slots 0 to 7).
     */
    fun saveArtboard(slot: Int) {
        val clampedSlot = slot.coerceIn(0, 7)
        val command = (1 shl clampedSlot)
        write32Bit(REG_SAVE_LOAD_ARTBOARD, command)
    }

    /**
     * Configures Layer 0 with a Solid Color animation (RGB 0-255).
     */
    override fun setSolidColorRgb(r: Int, g: Int, b: Int) {
        val red = r.coerceIn(0, 255)
        val green = g.coerceIn(0, 255)
        val blue = b.coerceIn(0, 255)

        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x00, ANIM_SOLID_COLOR.toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x01, 100.toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x02, 0.toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x03, 255.toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x04, red.toByte(), green.toByte(), blue.toByte()))
    }

    /**
     * Configures Layer 0 with a classic full spectrum Rainbow animation over I²C.
     */
    fun setRainbowAnimation(speed: Float = 0.5f, brightness: Int = 100) {
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x00, ANIM_RAINBOW.toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x01, brightness.coerceIn(0, 100).toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x02, 0.toByte()))
        i2cDevice.write(REG_LAYER_0, byteArrayOf(0x03, 255.toByte()))
    }

    /**
     * PWM interface compatibility: maps pulse width to equivalent I²C animation / artboard commands.
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

    override fun safe() {
        clearAnimations()
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
