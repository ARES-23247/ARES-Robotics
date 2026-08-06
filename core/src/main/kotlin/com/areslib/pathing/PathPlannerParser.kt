package com.areslib.pathing

import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d

/**
 * PathPlanner JSON Parsing and Trajectory Generation Facade.
 *
 * Exposes clean top-level functions for parsing PathPlanner `.path` JSON files or building dynamic Hermite spline paths.
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position $(x, y)$: Field-centric meters ($m$)
 * - Headings: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 * - Velocity ($v$): Meters per second ($m/s$)
 * - Acceleration ($a$): Meters per second squared ($m/s^2$)
 *
 * @see DynamicPathLoader
 * @see SplineMotionProfiler
 */
object PathPlannerParser {

    /**
     * Parses a raw PathPlanner `.path` JSON string into a fully motion-profiled [Path].
     *
     * @param jsonString Raw UTF-8 JSON text content.
     * @param maxVelocityMps Default linear velocity cap $v_{\text{max}}$ in meters per second ($m/s$).
     * @param maxAccelerationMps2 Default acceleration cap $a_{\text{max}}$ in meters per second squared ($m/s^2$).
     * @return Motion-profiled [Path].
     */
    fun parsePath(jsonString: String, maxVelocityMps: Double = 2.0, maxAccelerationMps2: Double = 1.5): Path {
        val parsedData = PathPlannerJsonParser.parse(jsonString, maxVelocityMps, maxAccelerationMps2)
        return SplineMotionProfiler.buildProfiledPath(parsedData)
    }

    /**
     * Dynamically generates a smooth, profiled [Path] from a list of 2D translation waypoints.
     *
     * @param points Waypoint translation coordinates in meters ($m$).
     * @param startHeading Desired starting robot heading [Rotation2d] (CCW-positive).
     * @param endHeading Desired ending robot heading [Rotation2d] (CCW-positive).
     * @param maxVelocityMps Maximum speed cap in meters per second ($m/s$).
     * @param maxAccelerationMps2 Maximum acceleration cap in meters per second squared ($m/s^2$).
     * @return Hermite spline interpolated [Path].
     */
    fun generatePath(
        points: List<Translation2d>,
        startHeading: Rotation2d,
        endHeading: Rotation2d,
        maxVelocityMps: Double = 2.0,
        maxAccelerationMps2: Double = 1.5
    ): Path {

        return SplineMotionProfiler.generateHermitePath(
            points = points,
            startHeading = startHeading,
            endHeading = endHeading,
            maxVelocityMps = maxVelocityMps,
            maxAccelerationMps2 = maxAccelerationMps2
        )
    }
}
