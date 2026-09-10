package com.areslib.kinematics

import com.areslib.math.geometry.Rotation2d

/**
 * Mutable data container representing the operational target state of an individual Swerve module.
 *
 * Combines the wheel drive speed ($v$) and module steering angle orientation ($\theta$) into a single state vector:
 * $$\mathbf{s}_i = \begin{bmatrix} v_{drive,i} \\ \theta_{steer,i} \end{bmatrix}$$
 *
 * ### Physical Units & Coordinate System:
 * - Drive Speed (`speedMetersPerSecond`): Linear wheel surface speed in meters per second ($m/s$).
 * - Steering Angle (`angle`): [Rotation2d] orientation in radians ($rad$), **CCW-positive** (0° = +X forward).
 *
 * ### Storage ownership:
 * Mutable fields allow callers to reuse module-state instances and arrays. Speed normalization
 * preserves the angle value. [Rotation2d] is an inline value class stored as a primitive here;
 * replacing this field does not itself require a boxed angle. Owning arrays/state copies allocate.
 *
 * @property speedMetersPerSecond Target wheel drive velocity in meters per second ($m/s$).
 * @property angle Target module steering orientation as a [Rotation2d] instance ($rad$, CCW positive).
 * @see SwerveKinematics
 */
data class SwerveModuleState(
    var speedMetersPerSecond: Double = 0.0,
    var angle: Rotation2d = Rotation2d()
)
