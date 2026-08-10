@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.robotcore.eventloop.opmode

import com.qualcomm.robotcore.hardware.HardwareMap
import org.firstinspires.ftc.robotcore.external.Telemetry

/**
 * Class implementation for Op Mode.
 *
 * Robotics framework control component.
 */
abstract class OpMode {
    var hardwareMap: HardwareMap = HardwareMap()
    var telemetry: Telemetry = org.firstinspires.ftc.robotcore.external.MockTelemetry()

    abstract fun init()
    abstract fun loop()
}
