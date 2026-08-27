package com.areslib.hardware.drive

import com.areslib.math.geometry.Pose2d
import com.google.gson.annotations.SerializedName

/**
 * Serializable sensory inputs for a high-speed odometry module.
 * Adheres to the Logged IO pattern.
 */
data class OdometryInputs(
    @SerializedName("posX") var posX: Double = 0.0,
    @SerializedName("posY") var posY: Double = 0.0,
    @SerializedName("heading") var heading: Double = 0.0,
    @SerializedName("velX") var velX: Double = 0.0,
    @SerializedName("velY") var velY: Double = 0.0,
    @SerializedName("headingVelocity") var headingVelocity: Double = 0.0,
    @SerializedName("timestampMs") var timestampMs: Long = 0L
)

/**
 * Pure abstraction for reading a high-speed odometry module
 * such as the goBILDA Pinpoint or OTOS.
 */
interface OdometryIO {
    /**
     * Initializes the odometry module, resetting its internal position
     * to the given starting pose.
     */
    fun initialize(startPose: Pose2d = Pose2d())
    
    /**
     * Polls the latest hardware signals into the inputs structure.
     */
    fun updateInputs(inputs: OdometryInputs)
}
