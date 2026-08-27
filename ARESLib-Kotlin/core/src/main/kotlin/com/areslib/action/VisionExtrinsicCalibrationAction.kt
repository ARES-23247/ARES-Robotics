package com.areslib.action

/**
 * Class implementation for Start Calibration Sweep.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
data class StartCalibrationSweep(
    val startHeading: Double,
    val cameraIndex: Int,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/**
 * Class implementation for Calibration Frame Logged.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
data class CalibrationFrameLogged(
    val gyroHeading: Double,
    val tagId: Int,
    val cameraIndex: Int,
    val cameraToTagTransform: DoubleArray,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction
