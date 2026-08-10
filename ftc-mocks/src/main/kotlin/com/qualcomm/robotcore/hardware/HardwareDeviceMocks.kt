package com.qualcomm.robotcore.hardware

/**
 * Minimal source-compatible subset of the FTC SDK hardware-device contract.
 * Default methods expose deterministic metadata and own no native resources.
 */
interface HardwareDevice {
    /** Returns [Manufacturer.Unknown] unless a concrete mock overrides it. */
    fun getManufacturer(): Manufacturer = Manufacturer.Unknown
    /** Returns a deterministic desktop device label. */
    fun getDeviceName(): String = "MockDevice"
    /** Returns a deterministic desktop connection label. */
    fun getConnectionInfo(): String = "MockConnection"
    /** Returns mock API version `1`. */
    fun getVersion(): Int = 1
    /** Default no-op because the base mock has no configuration state. */
    fun resetDeviceConfigurationForOpMode() {}
    /** Default no-op because the base mock owns no external resource. */
    fun close() {}

    /** FTC SDK-compatible manufacturer values used by first-party hardware wrappers. */
    enum class Manufacturer {
        Unknown, Other, Lego, HiTechnic, ModernRobotics, Adafruit, Matrix,
        Lynx, AMS, STMicroelectronics, StepperMotor, I2cDeviceSynchImplSimple,
        I2cDeviceSynchImpl, Broadcom, MaxBotix, GoBilda, Rev
    }
}

/**
 * In-memory named device mapping. Iteration over an empty voltage-sensor mapping yields a synthetic
 * 12 V source so desktop robot code has a deterministic nominal supply.
 */
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
 * Base FTC hardware-map double.
 *
 * Named mappings can be populated directly. Generic [get] and [getAll] deliberately throw until a
 * test/simulator subclass supplies lookup behavior, preventing silent use of missing hardware.
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
    /** Compatibility stub that reports success without mutating a backing serial-number registry. */
    open fun remove(serialNumber: String, deviceName: String): Boolean {
        return true
    }
    /** Compatibility stub that reports success without mutating a backing serial-number registry. */
    open fun remove(serialNumber: com.qualcomm.robotcore.util.SerialNumber, deviceName: String): Boolean {
        return true
    }
    /** Compatibility stub that reports success without mutating a backing serial-number registry. */
    open fun remove(serialNumber: String, device: HardwareDevice): Boolean {
        return true
    }
    /** Compatibility stub that reports success without mutating a backing serial-number registry. */
    open fun remove(serialNumber: com.qualcomm.robotcore.util.SerialNumber, device: HardwareDevice): Boolean {
        return true
    }
    /** Compatibility no-op; populate the typed [DeviceMapping] fields or override this method. */
    open fun put(serialNumber: String, deviceName: String, device: HardwareDevice) {}
    /** Compatibility no-op; populate the typed [DeviceMapping] fields or override this method. */
    open fun put(serialNumber: com.qualcomm.robotcore.util.SerialNumber, deviceName: String, device: HardwareDevice) {}
    /** Compatibility no-op; populate the typed [DeviceMapping] fields or override this method. */
    open fun put(deviceName: String, device: HardwareDevice) {}
}

/**
 * Mock representation of an FTC [VoltageSensor].
 */
interface VoltageSensor : HardwareDevice {
    val voltage: Double
}
