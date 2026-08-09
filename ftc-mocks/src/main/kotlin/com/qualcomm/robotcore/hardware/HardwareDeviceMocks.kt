package com.qualcomm.robotcore.hardware

/**
 * Mock representation of an FTC [HardwareDevice].
 */
interface HardwareDevice {
    /**
     * getManufacturer declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun getManufacturer(): Manufacturer = Manufacturer.Unknown
    /**
     * getDeviceName declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun getDeviceName(): String = "MockDevice"
    /**
     * getConnectionInfo declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun getConnectionInfo(): String = "MockConnection"
    /**
     * getVersion declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun getVersion(): Int = 1
    /**
     * resetDeviceConfigurationForOpMode declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun resetDeviceConfigurationForOpMode() {}
    /**
     * close declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun close() {}

    /**
     * Manufacturer declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    enum class Manufacturer {
        Unknown, Other, Lego, HiTechnic, ModernRobotics, Adafruit, Matrix,
        Lynx, AMS, STMicroelectronics, StepperMotor, I2cDeviceSynchImplSimple,
        I2cDeviceSynchImpl, Broadcom, MaxBotix, GoBilda, Rev
    }
}

open class DeviceMapping<T : HardwareDevice>(val deviceType: Class<T>) : Iterable<T> {
    private val map = mutableMapOf<String, T>()
    open fun get(deviceName: String): T? = map[deviceName]
    open fun put(deviceName: String, device: T) { map[deviceName] = device }
    open fun entrySet(): Set<Map.Entry<String, T>> = map.entries
    @Suppress("UNCHECKED_CAST")
    override fun iterator(): Iterator<T> = if (map.isEmpty() && VoltageSensor::class.java.isAssignableFrom(deviceType)) {
        listOf(object : VoltageSensor { override val voltage: Double get() = 12.0 }).map { it as T }.iterator()
    } else {
        map.values.iterator()
    }
    open val size: Int get() = map.size
}

/**
 * Mock representation of an FTC [HardwareMap].
 */
open class HardwareMap {
    @JvmField val voltageSensor: DeviceMapping<VoltageSensor> = DeviceMapping(VoltageSensor::class.java)
    @JvmField val servo: DeviceMapping<Servo> = DeviceMapping(Servo::class.java)
    @JvmField val dcMotor: DeviceMapping<DcMotor> = DeviceMapping(DcMotor::class.java)
    @JvmField val dcMotorEx: DeviceMapping<DcMotorEx> = DeviceMapping(DcMotorEx::class.java)

    open fun <T> get(classOrType: Class<out T>, deviceName: String): T {
        throw NotImplementedError("Mock HardwareMap.get() not overridden")
    }
    open fun <T> getAll(classOrType: Class<out T>): List<T> {
        throw NotImplementedError("Mock HardwareMap.getAll() not overridden")
    }
    open fun <T> getNamesOf(device: T): Set<String> {
        return emptySet()
    }
    /**
     * remove declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    open fun remove(serialNumber: String, deviceName: String): Boolean {
        return true
    }
    /**
     * remove declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    open fun remove(serialNumber: com.qualcomm.robotcore.util.SerialNumber, deviceName: String): Boolean {
        return true
    }
    /**
     * remove declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    open fun remove(serialNumber: String, device: HardwareDevice): Boolean {
        return true
    }
    /**
     * remove declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    open fun remove(serialNumber: com.qualcomm.robotcore.util.SerialNumber, device: HardwareDevice): Boolean {
        return true
    }
    /**
     * put declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    open fun put(serialNumber: String, deviceName: String, device: HardwareDevice) {}
    /**
     * put declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    open fun put(serialNumber: com.qualcomm.robotcore.util.SerialNumber, deviceName: String, device: HardwareDevice) {}
    /**
     * put declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    open fun put(deviceName: String, device: HardwareDevice) {}
}

/**
 * Mock representation of an FTC [VoltageSensor].
 */
interface VoltageSensor : HardwareDevice {
    val voltage: Double
}
