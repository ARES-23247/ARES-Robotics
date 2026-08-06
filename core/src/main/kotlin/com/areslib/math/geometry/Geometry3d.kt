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
    val norm: Double get() = sqrt(x * x + y * y + z * z)

    /** Vector addition $(x_1 + x_2, y_1 + y_2, z_1 + z_2)$. */
    operator fun plus(other: Translation3d) = Translation3d(x + other.x, y + other.y, z + other.z)

    /** Vector subtraction $(x_1 - x_2, y_1 - y_2, z_1 - z_2)$. */
    operator fun minus(other: Translation3d) = Translation3d(x - other.x, y - other.y, z - other.z)
}

/**
 * Unit Quaternion $\mathbf{q} = (w, x, y, z) = w + x\mathbf{i} + y\mathbf{j} + z\mathbf{k}$ for 3D orientation representation.
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
     * Normalizes this quaternion to unit length $\|q\| = 1$.
     *
     * @return Normalized unit [Quaternion] (returns default identity $(1,0,0,0)$ on NaN/zero norm).
     */
    fun normalize(): Quaternion {
        val norm = sqrt(w * w + x * x + y * y + z * z)
        if (norm.isNaN() || norm.isInfinite() || norm == 0.0) return Quaternion()
        return Quaternion(w / norm, x / norm, y / norm, z / norm)
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
 * 3D Rotational Orientation parameterized by a unit [Quaternion] or intrinsic Euler angles (roll, pitch, yaw).
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
    val x: Double get() {
        val sinr_cosp = 2.0 * (q.w * q.x + q.y * q.z)
        val cosr_cosp = 1.0 - 2.0 * (q.x * q.x + q.y * q.y)
        return atan2(sinr_cosp, cosr_cosp)
    }

    /** Extract Euler pitch angle in radians ($rad$). */
    val y: Double get() {
        val sinp = 2.0 * (q.w * q.y - q.z * q.x)
        return if (abs(sinp) >= 1.0) {
            (PI / 2).withSign(sinp)
        } else {
            asin(sinp)
        }
    }

    /** Extract Euler yaw angle in radians ($rad$), CCW-positive. */
    val z: Double get() {
        val siny_cosp = 2.0 * (q.w * q.z + q.x * q.y)
        val cosy_cosp = 1.0 - 2.0 * (q.y * q.y + q.z * q.z)
        return atan2(siny_cosp, cosy_cosp)
    }

    companion object {
        private fun fromEulerAngles(roll: Double, pitch: Double, yaw: Double): Quaternion {
            val cr = cos(roll * 0.5)
            val sr = sin(roll * 0.5)
            val cp = cos(pitch * 0.5)
            val sp = sin(pitch * 0.5)
            val cy = cos(yaw * 0.5)
            val sy = sin(yaw * 0.5)

            val w = cr * cp * cy + sr * sp * sy
            val x = sr * cp * cy - cr * sp * sy
            val y = cr * sp * cy + sr * cp * sy
            val z = cr * cp * sy - sr * sp * cy

            return Quaternion(w, x, y, z)
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
     */
    fun toPose2d(): Pose2d {
        return Pose2d(x, y, Rotation2d(rotation.z))
    }
}

/**
 * 3D Rigid Body Transformation matrix wrapper $(\mathbf{T}, \mathbf{R})$.
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
        val p = Quaternion(0.0, translation.x, translation.y, translation.z)
        val invP = invRot.q * p * invRot.q.inverse()
        return Transform3d(Translation3d(-invP.x, -invP.y, -invP.z), invRot)
    }
}

/**
 * Transforms a 3D pose by a 3D relative transform.
 *
 * @param other The rigid body [Transform3d] to apply.
 * @return Transformed [Pose3d].
 */
fun Pose3d.transformBy(other: Transform3d): Pose3d {
    val p = Quaternion(0.0, other.translation.x, other.translation.y, other.translation.z)
    val rotatedTrans = rotation.q * p * rotation.q.inverse()
    val newTrans = Translation3d(
        translation.x + rotatedTrans.x,
        translation.y + rotatedTrans.y,
        translation.z + rotatedTrans.z
    )
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
    val transDiff = Translation3d(
        translation.x - other.translation.x,
        translation.y - other.translation.y,
        translation.z - other.translation.z
    )
    val p = Quaternion(0.0, transDiff.x, transDiff.y, transDiff.z)
    val rotatedTrans = invRot.q * p * invRot.q.inverse()
    
    return Transform3d(
        Translation3d(rotatedTrans.x, rotatedTrans.y, rotatedTrans.z),
        invRot * rotation
    )
}

