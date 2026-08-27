@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.robotcore.util

/**
 * Class implementation for Serial Number.
 *
 * Robotics framework control component.
 */
open class SerialNumber(val serialNumber: String = "") {
    val isEmbeddedSerialNumber: Boolean = false
    override fun toString(): String = serialNumber
    
    companion object {
        @JvmStatic
        fun fromString(serialNumber: String): SerialNumber = SerialNumber(serialNumber)
    }
}
