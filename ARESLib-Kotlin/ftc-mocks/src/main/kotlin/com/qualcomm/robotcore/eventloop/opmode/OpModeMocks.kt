@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.robotcore.eventloop.opmode

import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.Gamepad
import org.firstinspires.ftc.robotcore.external.Telemetry

/**
 * Class implementation for Op Mode.
 *
 * Robotics framework control component.
 */
abstract class OpMode {
    @JvmField var hardwareMap: HardwareMap = HardwareMap()
    @JvmField var telemetry: Telemetry = org.firstinspires.ftc.robotcore.external.MockTelemetry()
    @JvmField var gamepad1: Gamepad = Gamepad()
    @JvmField var gamepad2: Gamepad = Gamepad()
    var isStopRequested: Boolean = false

    abstract fun init()
    open fun init_loop() {}
    open fun start() {}
    abstract fun loop()
    open fun stop() {}

    fun requestOpModeStop() {
        isStopRequested = true
    }
}
