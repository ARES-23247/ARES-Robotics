package com.areslib.math.geometry

import kotlin.math.*

/**
 * 3D Translational Vector $(x, y, z)$ in Cartesian space.
 *
 * Represents spatial position or displacement in 3D field coordinates.
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position $(x, y, z)$: Meters ($m$)
 * - $+X$ forward, $+Y$ left, $+Z$ upward (standard 3D robotics convention)
 *
 * @property x Displacement along X-axis in meters ($m$).
 * @property y Displacement along Y-axis in meters ($m$).
 * @property z Displacement along Z-axis in meters ($m$).
 */
data class Translation3d(var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0) {
    /** Euclidean length (norm) of the 3D translation vector in meters ($m$). $\|v\| = \sqrt{x^2 + y^2 + z^2}$. */
    val norm: Double get() {
        val squared = x * x + y * y + z * z
        if (squared >= java.lang.Double.MIN_NORMAL && squared.isFinite()) return sqrt(squared)
        // Preserve NaN even alongside infinity; hypot alone prioritizes infinity.
        return if (x.isNaN() || y.isNaN() || z.isNaN()) Double.NaN else hypot(hypot(x, y), z)
    }

    /** Vector addition $(x_1 + x_2, y_1 + y_2, z_1 + z_2)$. */
    operator fun plus(other: Translation3d) = Translation3d(x + other.x, y + other.y, z + other.z)

    /** Vector subtraction $(x_1 - x_2, y_1 - y_2, z_1 - z_2)$. */
    operator fun minus(other: Translation3d) = Translation3d(x - other.x, y - other.y, z - other.z)
}

/**
 * Mutable quaternion $\mathbf{q} = (w, x, y, z) = w + x\mathbf{i} + y\mathbf{j} + z\mathbf{k}$.
 * Rotation operations require unit length; construction and mutation do not enforce it.
 *
 * Avoids gimbal lock singularities when processing Limelight/AprilTag 3D camera transforms.
 *
 * ### Mathematical Formulation:
 * Quaternion Product $\mathbf{q}_a \otimes \mathbf{q}_b$:
 * $$\begin{bmatrix} w \\ x \\ y \\ z \end{bmatrix} = \begin{bmatrix}
 * w_a w_b - x_a x_b - y_a y_b - z_a z_b \\
 * w_a x_b + x_a w_b + y_a z_b - z_a y_b \\
 * w_a y_b - x_a z_b + y_a w_b + z_a x_b \\
 * w_a z_b + x_a y_b - y_a x_b + z_a w_b
 * \end{bmatrix}$$
 *
 * Unit Conjugate Inverse for Unit Quaternions:
 * $$\mathbf{q}^{-1} = (w, -x, -y, -z)$$
 *
 * @property w Scalar real component.
 * @property x Vector $\mathbf{i}$ component.
 * @property y Vector $\mathbf{j}$ component.
 * @property z Vector $\mathbf{k}$ component.
 */
data class Quaternion(var w: Double = 1.0, var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0) {
    /**
     * Returns a fresh unit quaternion without mutating this value. Scaling before squaring
     * preserves every finite nonzero quaternion, including subnormal components.
     *
     * @return Normalized unit [Quaternion]; zero or nonfinite input retains the legacy identity
     * fallback. That fallback is not evidence of a valid measured orientation.
     */
    fun normalize(): Quaternion {
        val squared = w * w + x * x + y * y + z * z
        if (squared >= java.lang.Double.MIN_NORMAL && squared.isFinite()) {
            val norm = sqrt(squared)
            return Quaternion(w / norm, x / norm, y / norm, z / norm)
        }
        val scale = max(max(abs(w), abs(x)), max(abs(y), abs(z)))
        if (!scale.isFinite() || scale == 0.0) return Quaternion()
        val sw = w / scale
        val sx = x / scale
        val sy = y / scale
        val sz = z / scale
        val norm = sqrt(sw * sw + sx * sx + sy * sy + sz * sz)
        return Quaternion(sw / norm, sx / norm, sy / norm, sz / norm)
    }

    /** Hamilton product multiplication. */
    operator fun times(other: Quaternion): Quaternion {
        return Quaternion(
            w * other.w - x * other.x - y * other.y - z * other.z,
            w * other.x + x * other.w + y * other.z - z * other.y,
            w * other.y - x * other.z + y * other.w + z * other.x,
            w * other.z + x * other.y - y * other.x + z * other.w
        )
    }

    /**
     * Calculates the inverse conjugate for a unit rotation quaternion.
     *
     * @return Conjugate inverse unit [Quaternion].
     */
    fun inverse(): Quaternion {
        // Assuming unit quaternion for spatial rotation
        return Quaternion(w, -x, -y, -z)
    }
}

/**
 * Mutable 3D orientation with a unit [Quaternion]. Euler construction uses
 * Rz(yaw) Ry(pitch) Rx(roll): fixed-axis X/Y/Z, equivalently intrinsic Z/Y/X.
 * Direct quaternion assignment must preserve unit length. Euler getters choose roll zero
 * at pitch singularities; roll and yaw are not individually identifiable there.
 * Components are borrowed mutable storage; use [Pose3d.deepCopy] when retaining a pose.
 *
 * ### Mathematical Formulation:
 * Euler (roll $\phi$, pitch $\theta$, yaw $\psi$) to Quaternion conversion:
 * $$w = \cos\frac{\phi}{2}\cos\frac{\theta}{2}\cos\frac{\psi}{2} + \sin\frac{\phi}{2}\sin\frac{\theta}{2}\sin\frac{\psi}{2}$$
 * $$x = \sin\frac{\phi}{2}\cos\frac{\theta}{2}\cos\frac{\psi}{2} - \cos\frac{\phi}{2}\sin\frac{\theta}{2}\sin\frac{\psi}{2}$$
 * $$y = \cos\frac{\phi}{2}\sin\frac{\theta}{2}\cos\frac{\psi}{2} + \sin\frac{\phi}{2}\cos\frac{\theta}{2}\sin\frac{\psi}{2}$$
 * $$z = \cos\frac{\phi}{2}\cos\frac{\theta}{2}\sin\frac{\psi}{2} - \sin\frac{\phi}{2}\sin\frac{\theta}{2}\cos\frac{\psi}{2}$$
 *
 * ### Physical Units & Conventions:
 * - Angles (roll, pitch, yaw): Radians ($rad$)
 * - Yaw ($\psi$): **CCW-positive** rotation about Z-axis ($0 = +X$, $\frac{\pi}{2} = +Y$).
 *
 * @property q Underlying unit [Quaternion].
 */
data class Rotation3d(var q: Quaternion = Quaternion()) {
    /** Constructs a 3D rotation from Euler angles (roll, pitch, yaw) in radians ($rad$). */
    constructor(roll: Double, pitch: Double, yaw: Double) : this(
        fromEulerAngles(roll, pitch, yaw)
    )
    
    /**
     * Sets internal quaternion components from Euler angles in radians ($rad$).
     *
     * @param roll Rotation about X-axis in radians ($rad$).
     * @param pitch Rotation about Y-axis in radians ($rad$).
     * @param yaw Rotation about Z-axis in radians ($rad$), CCW-positive.
     */
    fun setEulerAngles(roll: Double, pitch: Double, yaw: Double) {
        setEulerQuaternion(q, roll, pitch, yaw)
    }

    /** Composes two 3D rotations via quaternion product. */
    operator fun times(other: Rotation3d): Rotation3d {
        return Rotation3d((q * other.q).normalize())
    }
    
    /**
     * Calculates the inverse 3D rotation.
     *
     * @return Inverse [Rotation3d].
     */
    fun inverse(): Rotation3d {
        return Rotation3d(q.inverse())
    }

    /** Extract Euler roll angle in radians ($rad$). */
    val x: Double get() = quaternionRoll(q.w, q.x, q.y, q.z)

    /** Extract Euler pitch angle in radians ($rad$). */
    val y: Double get() = quaternionPitch(q.w, q.x, q.y, q.z)

    /** Extract Euler yaw angle in radians ($rad$), CCW-positive. */
    val z: Double get() = quaternionYaw(q.w, q.x, q.y, q.z)

    companion object {
        private fun fromEulerAngles(roll: Double, pitch: Double, yaw: Double): Quaternion {
            return Quaternion().also { setEulerQuaternion(it, roll, pitch, yaw) }
        }

        private fun setEulerQuaternion(q: Quaternion, roll: Double, pitch: Double, yaw: Double) {
            val cr = cos(roll * 0.5)
            val sr = sin(roll * 0.5)
            val cp = cos(pitch * 0.5)
            val sp = sin(pitch * 0.5)
            val cy = cos(yaw * 0.5)
            val sy = sin(yaw * 0.5)

            q.w = cr * cp * cy + sr * sp * sy
            q.x = sr * cp * cy - cr * sp * sy
            q.y = cr * sp * cy + sr * cp * sy
            q.z = cr * cp * sy - sr * sp * cy
        }
    }
}

/**
 * 3D Spatial Pose $(\mathbf{t}, \mathbf{R})$ combining 3D translation and 3D rotation.
 *
 * Fundamental state representation for 3D AprilTag landmark tracking and camera calibration.
 *
 * ### Physical Units:
 * - Translation $(x, y, z)$: Meters ($m$)
 * - Rotation $(\text{roll}, \text{pitch}, \text{yaw})$: Radians ($rad$)
 *
 * @property translation 3D position vector [Translation3d].
 * @property rotation 3D orientation [Rotation3d].
 */
data class Pose3d(
    var translation: Translation3d = Translation3d(),
    var rotation: Rotation3d = Rotation3d()
) {
    /** X position in meters ($m$). */
    val x: Double get() = translation.x
    /** Y position in meters ($m$). */
    val y: Double get() = translation.y
    /** Z position in meters ($m$). */
    val z: Double get() = translation.z
    
    /**
     * Projects this 3D pose onto the 2D ground plane $(x, y, \text{yaw})$.
     *
     * @return Equivalent planar [Pose2d] in meters ($m$) and radians ($rad$).
     * This projection does not validate feedback: callers must check the 3D observation
     * before use. [Rotation2d] retains its legacy nonfinite-heading fallback.
     */
    fun toPose2d(): Pose2d {
        return Pose2d(x, y, Rotation2d(rotation.z))
    }
}

/** Returns an ownership-safe copy including nested mutable translation, rotation, and quaternion. */
fun Pose3d.deepCopy(): Pose3d = Pose3d(
    translation = Translation3d(translation.x, translation.y, translation.z),
    rotation = Rotation3d(Quaternion(rotation.q.w, rotation.q.x, rotation.q.y, rotation.q.z))
)

/**
 * 3D Rigid Body Transformation matrix wrapper $(\mathbf{T}, \mathbf{R})$.
 * Constructors borrow mutable components. Transform operations return independently owned
 * results and allocate; they are not in-place, zero-allocation sensor workspaces. Rotations
 * must be unit quaternions, and scalar overflow propagates as in ordinary vector arithmetic.
 *
 * @property translation Relative 3D translation vector.
 * @property rotation Relative 3D rotation.
 */
data class Transform3d(
    val translation: Translation3d = Translation3d(),
    val rotation: Rotation3d = Rotation3d()
) {
    /**
     * Inverts this 3D rigid body transformation.
     *
     * @return Inverse [Transform3d].
     */
    fun inverse(): Transform3d {
        val invRot = rotation.inverse()
        val invTrans = rotateTranslation(invRot.q, -translation.x, -translation.y, -translation.z)
        return Transform3d(invTrans, invRot)
    }
}

/**
 * Transforms a 3D pose by a 3D relative transform.
 *
 * @param other The rigid body [Transform3d] to apply.
 * @return Transformed [Pose3d].
 */
fun Pose3d.transformBy(other: Transform3d): Pose3d {
    val newTrans = rotateTranslation(rotation.q, other.translation.x, other.translation.y, other.translation.z)
    newTrans.x += translation.x
    newTrans.y += translation.y
    newTrans.z += translation.z
    return Pose3d(newTrans, rotation * other.rotation)
}

/**
 * Calculates the relative 3D transform from target pose [other] to this pose.
 *
 * @param other Reference origin [Pose3d].
 * @return Relative [Transform3d].
 */
fun Pose3d.relativeTo(other: Pose3d): Transform3d {
    val invRot = other.rotation.inverse()
    val rotatedTrans = rotateTranslation(invRot.q,
        translation.x - other.translation.x,
        translation.y - other.translation.y,
        translation.z - other.translation.z
    )
    return Transform3d(
        rotatedTrans,
        invRot * rotation
    )
}

// Scalar Hamilton sandwich q * (0, x, y, z) * conjugate(q). Only the owned vector
// result allocates: no pure-vector quaternion, products, or conjugate temporaries.
// Like the public Hamilton product, arithmetic overflow/nonfinite inputs propagate.
private fun rotateTranslation(q: Quaternion, x: Double, y: Double, z: Double): Translation3d {
    val scale = max(abs(x), max(abs(y), abs(z)))
    if (scale.isFinite() && (scale > Double.MAX_VALUE / 4.0 || scale > 0.0 && scale < java.lang.Double.MIN_NORMAL * 4.0)) {
        // A unit quaternion can rotate a finite vector even when sandwich intermediates
        // overflow or underflow. Normalize only these extremes; recursion is exactly one
        // level because the largest normalized component is one. Reuse the owned result.
        val rotated = rotateTranslation(q, x / scale, y / scale, z / scale)
        rotated.x *= scale
        rotated.y *= scale
        rotated.z *= scale
        return rotated
    }
    val aw = -q.x * x - q.y * y - q.z * z
    val ax = q.w * x + q.y * z - q.z * y
    val ay = q.w * y - q.x * z + q.z * x
    val az = q.w * z + q.x * y - q.y * x
    return Translation3d(
        -aw * q.x + ax * q.w - ay * q.z + az * q.y,
        -aw * q.y + ax * q.z + ay * q.w - az * q.x,
        -aw * q.z - ax * q.y + ay * q.x + az * q.w,
    )
}
