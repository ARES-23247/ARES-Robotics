@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.robotcore.util

/**
 * Object implementation for Robot Log.
 *
 * Robotics framework control component.
 */
object RobotLog {
    @JvmStatic fun v(message: String) { println("V/RobotLog: $message") }
    @JvmStatic fun v(format: String, vararg args: Any) { println("V/RobotLog: " + String.format(format, *args)) }
    @JvmStatic fun d(message: String) { println("D/RobotLog: $message") }
    @JvmStatic fun d(format: String, vararg args: Any) { println("D/RobotLog: " + String.format(format, *args)) }
    @JvmStatic fun i(message: String) { println("I/RobotLog: $message") }
    @JvmStatic fun i(format: String, vararg args: Any) { println("I/RobotLog: " + String.format(format, *args)) }
    @JvmStatic fun w(message: String) { println("W/RobotLog: $message") }
    @JvmStatic fun w(format: String, vararg args: Any) { println("W/RobotLog: " + String.format(format, *args)) }
    @JvmStatic fun e(message: String) { System.err.println("E/RobotLog: $message") }
    @JvmStatic fun e(format: String, vararg args: Any) { System.err.println("E/RobotLog: " + String.format(format, *args)) }
    @JvmStatic fun ee(tag: String, message: String) { System.err.println("E/RobotLog-$tag: $message") }
    @JvmStatic fun ee(tag: String, e: Throwable, message: String) { System.err.println("E/RobotLog-$tag: $message"); e.printStackTrace() }
    @JvmStatic fun ii(tag: String, message: String) { println("I/RobotLog-$tag: $message") }
    @JvmStatic fun ww(tag: String, message: String) { println("W/RobotLog-$tag: $message") }
    @JvmStatic fun dd(tag: String, message: String) { println("D/RobotLog-$tag: $message") }
    @JvmStatic fun vv(tag: String, message: String) { println("V/RobotLog-$tag: $message") }
}
