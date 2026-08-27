package com.areslib.logging

import com.areslib.hardware.sensor.ImuInputs
import com.areslib.hardware.drive.OdometryInputs
import com.areslib.hardware.drive.SwerveModuleInputs
import com.areslib.hardware.vision.VisionIOInputs
import com.google.gson.annotations.SerializedName
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A consolidated, fully serializable sensory data frame capturing
 * all active robot subsystem hardware inputs at a single time step.
 */
data class RobotInputsFrame(
    @SerializedName("runId") var runId: String = "",
    @SerializedName("robotId") var robotId: String = "",
    @SerializedName("timestampMs") var timestampMs: Long = 0L,
    @SerializedName("swerveInputs") val swerveInputs: List<SwerveModuleInputs> = List(4) { SwerveModuleInputs() },
    @SerializedName("imuInputs") val imuInputs: ImuInputs = ImuInputs(),
    @SerializedName("odometryInputs") val odometryInputs: OdometryInputs = OdometryInputs(),
    @SerializedName("visionInputs") val visionInputs: VisionIOInputs = VisionIOInputs()
) {
    /**
     * Resets all internal mutable properties to default values.
     * Prevents stale telemetry leakage when recycling the frame.
     */
    fun clear() {
        runId = ""
        robotId = ""
        timestampMs = 0L
        for (module in swerveInputs) {
            module.drivePositionRads = 0.0
            module.driveVelocityRadsPerSec = 0.0
            module.steerAbsolutePositionRads = 0.0
            module.timestampMs = 0L
        }
        
        imuInputs.headingRadians = 0.0
        imuInputs.pitchRadians = 0.0
        imuInputs.rollRadians = 0.0
        imuInputs.yawVelocityRadPerSec = 0.0
        imuInputs.timestampMs = 0L
        
        odometryInputs.posX = 0.0
        odometryInputs.posY = 0.0
        odometryInputs.heading = 0.0
        odometryInputs.velX = 0.0
        odometryInputs.velY = 0.0
        odometryInputs.headingVelocity = 0.0
        odometryInputs.timestampMs = 0L

        visionInputs.isConnected = false
        visionInputs.measurements = emptyList()
    }
}

/**
 * Thread-safe Object Pool for recycling RobotInputsFrame instances.
 * Completely eliminates runtime garbage collection allocations in the control loop.
 */
object RobotInputsFramePool {
    private val pool = ConcurrentLinkedQueue<RobotInputsFrame>()
    private const val PRE_ALLOC_SIZE = 16

    init {
        // Warm up the pool with pre-allocated instances
        for (i in 0 until PRE_ALLOC_SIZE) {
            pool.offer(RobotInputsFrame())
        }
    }

    /**
     * Rent a clean, cleared frame from the pool.
     * If empty, instantiates a new frame dynamically (failsafe).
     */
    fun rent(): RobotInputsFrame {
        val frame = pool.poll() ?: RobotInputsFrame()
        frame.clear()
        return frame
    }

    /**
     * Return a frame to the pool for reuse.
     * Clears all contents to prevent stale references.
     */
    fun recycle(frame: RobotInputsFrame) {
        frame.clear()
        pool.offer(frame)
    }

    /**
     * Get the current count of available instances in the pool.
     */
    val availableCount: Int get() = pool.size
}

/**
 * Asynchronously populates the current RobotInputsFrame with drive, imu, odometry, and vision states.
 */
fun RobotInputsFrame.populate(
    runId: String,
    robotId: String,
    timestamp: Long,
    poseUpdate: com.areslib.action.RobotAction.PoseUpdate,
    driveState: com.areslib.state.DriveState,
    hasVision: Boolean,
    measurements: List<com.areslib.state.VisionMeasurement>
) {
    this.runId = runId
    this.robotId = robotId
    this.timestampMs = timestamp
    
    this.odometryInputs.posX = poseUpdate.xMeters
    this.odometryInputs.posY = poseUpdate.yMeters
    this.odometryInputs.heading = poseUpdate.headingRadians
    this.odometryInputs.velX = driveState.xVelocityMetersPerSecond
    this.odometryInputs.velY = driveState.yVelocityMetersPerSecond
    this.odometryInputs.headingVelocity = driveState.angularVelocityRadiansPerSecond
    this.odometryInputs.timestampMs = timestamp

    this.imuInputs.headingRadians = poseUpdate.headingRadians
    this.imuInputs.pitchRadians = 0.0
    this.imuInputs.rollRadians = 0.0
    this.imuInputs.yawVelocityRadPerSec = driveState.angularVelocityRadiansPerSecond
    this.imuInputs.timestampMs = timestamp

    this.visionInputs.isConnected = hasVision
    this.visionInputs.measurements = measurements
}
