package com.areslib.math.geometry

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import com.areslib.math.wrapAngle

/**
 * Represents a 2D translational vector or point $(x, y)$ in Cartesian space.
 *
 * Core component for the Redux and Control architecture, primarily used in kinematics 
 * and state space representations for tracking spatial coordinates.
 *
 * Designed with value-semantics in mind. In high-frequency 50Hz/100Hz loops, allocations of this 
 * class should be minimized or managed by a memory pool / value class unboxing to guarantee zero-GC pauses.
 *
 * ### Physical Units & Coordinates:
 * - Position $(x, y)$: Meters ($m$)
 * - Positive X is typically forward, Positive Y is typically left (following standard CCW-positive robotics convention).
 *
 * @param x The X-coordinate displacement in meters ($m$).
 * @param y The Y-coordinate displacement in meters ($m$).
 */
data class Translation2d(val x: Double = 0.0, val y: Double = 0.0) {
    /**
     * Calculates the Euclidean norm (magnitude) of the translation vector.
     * $$ \|v\| = \sqrt{x^2 + y^2} $$
     * 
     * Guaranteed zero-GC allocation.
     * 
     * @return Vector magnitude in meters ($m$).
     */
    val norm: Double get() = hypot(x, y)
}

/**
 * Represents a 2D rotational orientation.
 *
 * Implemented as a Kotlin `value class` to ensure zero-GC allocations on 50Hz/100Hz hot paths 
 * when directly manipulating angles, acting as a primitive at runtime.
 *
 * ### Physical Units & Coordinates:
 * - Angle: Radians ($rad$), mathematically wrapped to $[-\pi, \pi)$.
 * - **CCW-positive** convention: $0^\circ$ is +X, $90^\circ$ is +Y.
 *
 * @param rawRadians The raw rotation value in radians ($rad$).
 */
@JvmInline value class Rotation2d(val rawRadians: Double = 0.0) {
    /** 
     * The wrapped rotation value in radians ($rad$) bounded to $[-\pi, \pi)$. 
     * $$ \theta_{\text{wrapped}} = (\theta + \pi \pmod{2\pi}) - \pi $$
     */
    val radians: Double get() = wrapAngle(rawRadians)
    
    /** The cosine of the rotation angle. Zero-GC. */
    val cos: Double get() = cos(radians)
    
    /** The sine of the rotation angle. Zero-GC. */
    val sin: Double get() = sin(radians)
    
    companion object {
        /**
         * Factory method to construct a Rotation2d from degrees.
         * 
         * @param degrees The rotation in degrees ($^\circ$).
         * @return A Rotation2d instance.
         */
        fun fromDegrees(degrees: Double): Rotation2d = Rotation2d(Math.toRadians(degrees))
    }
}

/**
 * Represents a 2D rigid body transform or spatial pose $(x, y, \theta)$.
 *
 * Serves as the fundamental state representation for global robot odometry and path following.
 *
 * Designed with value-semantics in mind. For high-frequency 50Hz/100Hz loops, allocations should be 
 * tracked and minimized to maintain zero-GC overhead.
 *
 * ### Physical Units & Coordinates:
 * - Position $(x, y)$: Meters ($m$)
 * - Heading $(\theta)$: Radians ($rad$)
 * - **CCW-positive** heading convention: $0^\circ = +X$, $90^\circ = +Y$.
 *
 * @param x The X-coordinate displacement in meters ($m$).
 * @param y The Y-coordinate displacement in meters ($m$).
 * @param heading The rotational orientation of the pose.
 */
data class Pose2d(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val heading: Rotation2d = Rotation2d()
) {
    /** Extracts the translational component $(x, y)$ of the pose. */
    val translation: Translation2d get() = Translation2d(x, y)
}

/**
 * Formats a Pose2d into a standard human-readable format: "(X.XX, Y.YY) DEGREES°".
 * 
 * Note: String allocation inherently triggers GC, so avoid calling in 50Hz/100Hz hot loops unless required for telemetry.
 * 
 * @receiver The [Pose2d] instance to format.
 * @return A formatted string representation.
 */
fun Pose2d.toFormattedString(): String =
    String.format("(%.2f, %.2f) %.1f°", x, y, Math.toDegrees(heading.radians))
