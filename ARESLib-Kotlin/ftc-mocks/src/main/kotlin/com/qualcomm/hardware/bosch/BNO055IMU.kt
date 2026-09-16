@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.hardware.bosch

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference
import org.firstinspires.ftc.robotcore.external.navigation.Orientation

/**
 * Interface implementation for [BNO055IMU].
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators
 * into immutable Redux state representations.
 *
 * Provides a desktop mock double for the legacy Bosch BNO055 9-axis absolute orientation sensor.
 * In desktop simulation, concrete test doubles implement orientation telemetry retrieval
 * and simulated Euler angle kinematics according to the configured axis references.
 */
interface BNO055IMU {
    /**
     * Angular measurement units supported by the BNO055 sensor configuration.
     */
    enum class AngleUnit {
        DEGREES,
        RADIANS
    }

    /**
     * Configuration parameters used to initialize the BNO055 IMU sensor.
     *
     * Specifies operating mode, calibration parameters, and angular units.
     */
    class Parameters {
        /**
         * The angular unit to return orientation measurements in (defaults to [AngleUnit.RADIANS]).
         */
        var angleUnit: AngleUnit = AngleUnit.RADIANS

        /** Constructs default [Parameters]. */
        constructor()
    }

    /**
     * Initializes the IMU sensor with the supplied configuration [parameters].
     *
     * @param parameters Configuration parameters for sensor initialization.
     * @return `true` if initialization succeeded, `false` otherwise.
     */
    fun initialize(parameters: Parameters): Boolean

    /**
     * Queries the instantaneous angular orientation using the given axes reference and order conventions.
     *
     * @param reference Coordinate frame convention (e.g. INTRINSIC, EXTRINSIC).
     * @param order Rotation sequence order (e.g. ZYX).
     * @param angleUnit Desired output angle unit (DEGREES or RADIANS).
     * @return Simulated [Orientation] snapshot.
     */
    fun getAngularOrientation(
        reference: AxesReference,
        order: AxesOrder,
        angleUnit: org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
    ): Orientation
}
