package org.aresfirst.marvin.config

import com.areslib.drivetrain.SwerveOffsetData
import org.aresfirst.marvin.generated.drivebase.GeneratedAresDrivebaseConfig
import org.aresfirst.marvin.generated.drivebase.GeneratedAresTuningConfig
import com.areslib.state.TuningState
import com.areslib.tuning.TypedTuningRuntime

/**
 * ARES-owned adapter around generated profile plumbing.
 *
 * CTRE Tuner X remains authoritative for vendor motor/module configuration. The checked-in ARES
 * profile adds path, simulation, and reviewed calibration metadata without copying or editing
 * `TunerConstants.java`.
 */
object CanonicalDrivebaseConfig {
    private val values get() = GeneratedAresTuningConfig.Parameters

    /** True only for ARES-owned fields rebuilt into immutable Redux state. */
    fun supportsRuntimeParameter(parameterUid: String): Boolean = parameterUid in reduxParameterUids

    fun initialTuningState(): TuningState = withRuntimeValues(TuningState(), null)

    fun withRuntimeValues(current: TuningState, runtime: TypedTuningRuntime?): TuningState {
        fun number(uid: String, canonical: Double): Double = runtime?.double(uid) ?: canonical
        return current.copy(
            drive = current.drive.copy(
                trackWidthMeters = GeneratedAresDrivebaseConfig.TRACK_WIDTH_METERS,
                wheelBaseMeters = GeneratedAresDrivebaseConfig.WHEEL_BASE_METERS,
                pathVelocityScale = number("frc.ares.path.velocity-scale", values.ARES_PATHVELOCITYSCALE),
                pathAccelerationLimit = number("frc.ares.path.acceleration-limit", values.ARES_PATHACCELERATIONLIMIT),
            )
        )
    }

    /** Reviewed checked-in baseline; runtime still loads the explicit deploy/local overlay. */
    fun profiledOffsets(): SwerveOffsetData = SwerveOffsetData(
        frontLeft = values.CALIBRATION_FRONTLEFTOFFSET,
        frontRight = values.CALIBRATION_FRONTRIGHTOFFSET,
        backLeft = values.CALIBRATION_BACKLEFTOFFSET,
        backRight = values.CALIBRATION_BACKRIGHTOFFSET,
    )

    val simulationLinearKp: Double get() = values.SIMULATION_LINEARKP
    val simulationAngularKp: Double get() = values.SIMULATION_ANGULARKP
    val simulationRobotLengthMeters: Double get() = values.SIMULATION_ROBOTLENGTHMETERS
    val simulationRobotWidthMeters: Double get() = values.SIMULATION_ROBOTWIDTHMETERS

    private val reduxParameterUids = setOf(
        "frc.ares.path.velocity-scale",
        "frc.ares.path.acceleration-limit",
    )
}
