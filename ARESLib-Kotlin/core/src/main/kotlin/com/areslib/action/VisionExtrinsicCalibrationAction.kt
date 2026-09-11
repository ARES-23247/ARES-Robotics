package com.areslib.action

/**
 * Requests a camera calibration sweep beginning at [startHeading] radians, CCW-positive.
 * [cameraIndex] identifies the producer's camera. This event performs no hardware IO;
 * the consuming calibration owner must validate configuration and authorize motion.
 */
data class StartCalibrationSweep(
    val startHeading: Double,
    val cameraIndex: Int,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/**
 * Calibration observation with [gyroHeading] in CCW-positive radians and producer-defined
 * camera-to-tag transform values. The array is caller-owned and mutable; retained/asynchronous
 * consumers must snapshot it during dispatch. ActionLogger does so before queuing the record.
 * Transform layout and camera/tag validity belong to the producer/consumer calibration contract.
 */
data class CalibrationFrameLogged(
    val gyroHeading: Double,
    val tagId: Int,
    val cameraIndex: Int,
    val cameraToTagTransform: DoubleArray,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction
