package com.qualcomm.robotcore.hardware

/**
 * Mock representation of an FTC [DistanceSensor].
 */
interface DistanceSensor : HardwareDevice {
    fun getDistance(unit: org.firstinspires.ftc.robotcore.external.navigation.Distance): Double
}

/**
 * Mock representation of an FTC [ColorSensor].
 */
interface ColorSensor : HardwareDevice {
    fun red(): Int
    fun green(): Int
    fun blue(): Int
    fun alpha(): Int
}

/**
 * Mock representation of an FTC [NormalizedRGBA].
 */
class NormalizedRGBA {
    @JvmField var red: Float = 0f
    @JvmField var green: Float = 0f
    @JvmField var blue: Float = 0f
    @JvmField var alpha: Float = 0f
}

/**
 * Mock representation of an FTC [NormalizedColorSensor].
 */
interface NormalizedColorSensor : HardwareDevice {
    val normalizedColors: NormalizedRGBA
}

/**
 * Mock representation of an FTC [AnalogInput].
 */
open class AnalogInput : HardwareDevice {
    open val voltage: Double = 0.0
    override fun getManufacturer(): HardwareDevice.Manufacturer = HardwareDevice.Manufacturer.Unknown
    override fun getDeviceName(): String = "AnalogInput"
    override fun getConnectionInfo(): String = ""
    override fun getVersion(): Int = 1
    override fun resetDeviceConfigurationForOpMode() {}
    override fun close() {}
}

/**
 * Mock representation of an FTC [DigitalChannel].
 */
interface DigitalChannel : HardwareDevice {
    enum class Mode { INPUT, OUTPUT }
    var mode: Mode
    val state: Boolean
}

/**
 * Mock representation of an FTC [IMU].
 */
interface IMU : HardwareDevice {
    class Parameters(val hubOrientationOnRobot: com.qualcomm.hardware.rev.RevHubOrientationOnRobot)

    fun initialize(parameters: Parameters): Boolean
    fun resetYaw()
    fun getRobotYawPitchRollAngles(): org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles
    fun getRobotAngularVelocity(unit: org.firstinspires.ftc.robotcore.external.navigation.AngleUnit): org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity
}
