@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.robotcore.hardware

/**
 * Interface implementation for I2c Device Synch Simple.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
interface I2cDeviceSynchSimple : HardwareDevice {
    fun read8(ireg: Int): Byte = 0
    fun write8(ireg: Int, data: Int) {}
}
