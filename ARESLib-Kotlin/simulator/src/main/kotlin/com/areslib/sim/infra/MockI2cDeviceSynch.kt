package com.areslib.sim.infra

import com.qualcomm.robotcore.hardware.I2cAddr
import com.qualcomm.robotcore.hardware.I2cDeviceSynch

/**
 * Class implementation for Mock I2c Device Synch.
 *
 * Robotics framework control component.
 */
class MockI2cDeviceSynch : I2cDeviceSynch {
    override var i2cAddress: I2cAddr = I2cAddr.create7bit(0x00)
    
    val registers = ByteArray(256)
    
    var onWrite: ((register: Int, data: ByteArray) -> Unit)? = null
    var onRead: ((register: Int, length: Int) -> Unit)? = null

    override fun read8(register: Int): Byte {
        onRead?.invoke(register, 1)
        return registers.getOrElse(register) { 0 }
    }

    override fun read(register: Int, length: Int): ByteArray {
        onRead?.invoke(register, length)
        val data = ByteArray(length)
        for (i in 0 until length) {
            val regIndex = register + i
            data[i] = registers.getOrElse(regIndex) { 0 }
        }
        return data
    }

    override fun write(register: Int, data: ByteArray) {
        for (i in data.indices) {
            val regIndex = register + i
            if (regIndex in 0..255) {
                registers[regIndex] = data[i]
            }
        }
        onWrite?.invoke(register, data)
    }

    override var readWindow: I2cDeviceSynch.ReadWindow = 
        I2cDeviceSynch.ReadWindow(0, 256, I2cDeviceSynch.ReadMode.REPEAT)
}
