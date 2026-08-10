@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.robotcore.eventloop.opmode

import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.Gamepad
import org.firstinspires.ftc.robotcore.external.Telemetry

annotation class TeleOp(val name: String = "", val group: String = "")
annotation class Autonomous(val name: String = "", val group: String = "")

/**
 * Class implementation for Linear Op Mode.
 *
 * Robotics framework control component.
 */
abstract class LinearOpMode {
    abstract fun runOpMode()
    @JvmField var hardwareMap: HardwareMap = object : HardwareMap() {
        override fun <T> get(classOrType: Class<out T>, deviceName: String): T {
            throw NotImplementedError()
        }
        override fun <T> getAll(classOrType: Class<out T>): List<T> {
            return emptyList()
        }
    }
    @JvmField val gamepad1 = Gamepad()
    @JvmField val gamepad2 = Gamepad()
    @JvmField val telemetry: Telemetry = org.firstinspires.ftc.robotcore.external.MockTelemetry()
    
    @Volatile var isStarted = false
    @Volatile var isStopRequested = false
    
    fun opModeIsActive(): Boolean = isStarted && !isStopRequested && !Thread.currentThread().isInterrupted
    fun opModeInInit(): Boolean = !isStarted && !isStopRequested && !Thread.currentThread().isInterrupted
    
    fun waitForStart() {
        while (!isStarted && !isStopRequested && !Thread.currentThread().isInterrupted) {
            Thread.sleep(10)
        }
    }
    
    fun sleep(milliseconds: Long) {
        if (com.areslib.util.RobotClock.isMocked) {
            val targetTime = com.areslib.util.RobotClock.currentTimeMillis() + milliseconds
            while (com.areslib.util.RobotClock.currentTimeMillis() < targetTime && !isStopRequested && !Thread.currentThread().isInterrupted) {
                Thread.sleep(1)
            }
        } else {
            Thread.sleep(milliseconds)
        }
    }
}
