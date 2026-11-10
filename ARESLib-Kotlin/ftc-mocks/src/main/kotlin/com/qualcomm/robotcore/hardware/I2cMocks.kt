package com.qualcomm.robotcore.hardware

/**
 * Mock representation of an FTC [I2cAddr].
 */
open class I2cAddr {
    companion object {
        @JvmStatic
        fun create7bit(address: Int): I2cAddr = I2cAddr()
        @JvmStatic
        fun create8bit(address: Int): I2cAddr = I2cAddr()
    }
}

/**
 * Mock representation of an FTC [I2cDeviceSynch].
 */
interface I2cDeviceSynch : I2cDeviceSynchSimple {
    var i2cAddress: I2cAddr
    override fun read8(ireg: Int): Byte
    fun read(ireg: Int, length: Int): ByteArray
    fun write(ireg: Int, data: ByteArray)
    
    var readWindow: ReadWindow
    
    /**
     * Class implementation for Read Window.
     *
     * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
     */
    class ReadWindow(val ireg: Int, val creg: Int, val mode: ReadMode)
    enum class ReadMode { REPEAT, BALANCED, ONLY_ONCE }
}

/**
 * Mock representation of an FTC [I2cDeviceSynchDevice].
 */
abstract class I2cDeviceSynchDevice<T>(@JvmField protected val deviceClient: T, val isOwned: Boolean) : HardwareDevice where T : I2cDeviceSynchSimple {
    abstract fun doInitialize(): Boolean
    fun initialize(): Boolean = doInitialize()
    abstract override fun getManufacturer(): HardwareDevice.Manufacturer
    abstract override fun getDeviceName(): String
}
