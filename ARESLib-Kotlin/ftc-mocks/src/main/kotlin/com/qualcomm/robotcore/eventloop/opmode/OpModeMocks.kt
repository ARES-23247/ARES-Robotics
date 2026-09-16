@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.robotcore.eventloop.opmode

import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.Gamepad
import org.firstinspires.ftc.robotcore.external.Telemetry

/**
 * Base abstract class for iterative FTC [OpMode] implementations.
 *
 * Robotics framework control component governing the iterative lifecycle of robot code:
 * - [init]: Executed once when the driver station user presses the INIT button.
 * - [init_loop]: Executed repeatedly between INIT and START button presses.
 * - [start]: Executed once when the driver station user presses the START button.
 * - [loop]: Executed repeatedly during the active running phase of the OpMode.
 * - [stop]: Executed once when the OpMode is stopped.
 *
 * In desktop simulation, test fixtures instantiate OpModes, inject mock hardware maps,
 * gamepads, and telemetry, and step through lifecycle iterations deterministically.
 */
abstract class OpMode {
    /** Hardware device registry containing all configured motors, servos, and sensors. */
    @JvmField var hardwareMap: HardwareMap = HardwareMap()

    /** Telemetry output sink for logging diagnostic metrics to the driver station. */
    @JvmField var telemetry: Telemetry = org.firstinspires.ftc.robotcore.external.MockTelemetry()

    /** Primary driver gamepad controller inputs. */
    @JvmField var gamepad1: Gamepad = Gamepad()

    /** Secondary operator gamepad controller inputs. */
    @JvmField var gamepad2: Gamepad = Gamepad()

    /** Indicates whether the OpMode has received an explicit stop request. */
    var isStopRequested: Boolean = false

    /** Constructs an [OpMode] instance with initialized default hardware and gamepads. */
    constructor()

    /** User-defined initialization logic called once when INIT is pressed. */
    abstract fun init()

    /** Optional initialization loop called repeatedly until START is pressed. */
    open fun init_loop() {}

    /** Optional logic called once when START is pressed. */
    open fun start() {}

    /** Main control loop logic called repeatedly while active. */
    abstract fun loop()

    /** Optional cleanup logic called once when STOP is requested. */
    open fun stop() {}

    /**
     * Requests the OpMode to stop executing, setting [isStopRequested] to `true`.
     */
    fun requestOpModeStop() {
        isStopRequested = true
    }
}
