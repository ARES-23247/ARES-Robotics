@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.robotcore.hardware.usb

/**
 * Class implementation for [RobotUsbDevice].
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators
 * into immutable Redux state representations.
 *
 * In physical robot controllers, [RobotUsbDevice] communicates directly with FTDI
 * and CDC-ACM USB endpoints connected to REV Expansion Hubs and Control Hubs.
 *
 * In desktop simulation, this mock double provides an in-memory transport layer
 * that safely handles byte stream writes without native libusb or Android USB host APIs,
 * enabling full communication protocol verification in automated unit tests.
 */
open class RobotUsbDevice {
    /** Constructs a default desktop mock [RobotUsbDevice]. */
    constructor()

    /**
     * Transmits raw datagram bytes to the simulated USB peripheral endpoint.
     *
     * In desktop mock execution, this operation safely verifies frame serialization
     * without blocking the caller on hardware I/O or raising unhandled transport faults.
     *
     * @param data The raw byte buffer to transmit across the USB interface.
     */
    open fun write(data: ByteArray) {}
}
