package com.areslib.math.kinematics

import com.areslib.math.geometry.*

import kotlin.math.cos
import kotlin.math.sin

/**
 * Dead-Wheel Odometry Displacement Vector Transformations.
 *
 * Converts robot-frame dead-wheel forward and strafe deltas into field-centric global displacement vectors
 * using 2D rotation matrix transformations.
 *
 * ### Mathematical Formulation:
 * Robot-to-Field Frame Rotation Transform $\mathbf{R}(\theta)$:
 * $$\begin{bmatrix} \Delta x_{\text{field}} \\ \Delta y_{\text{field}} \end{bmatrix} = \begin{bmatrix} \cos\theta & -\sin\theta \\ \sin\theta & \cos\theta \end{bmatrix} \begin{bmatrix} \Delta d_{\text{forward}} \\ \Delta d_{\text{strafe}} \end{bmatrix}$$
 * $$\Delta x_{\text{field}} = \Delta d_{\text{forward}} \cos\theta - \Delta d_{\text{strafe}} \sin\theta$$
 * $$\Delta y_{\text{field}} = \Delta d_{\text{forward}} \sin\theta + \Delta d_{\text{strafe}} \cos\theta$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Displacements ($\Delta d_{\text{forward}}, \Delta d_{\text{strafe}}, \Delta x, \Delta y$): Meters ($m$)
 * - Heading ($\theta$): Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 *
 * ### Zero-GC Guarantee:
 * Uses primitive scalar trigonometry with zero object allocation.
 */
object OdometryMath {
    /**
     * Calculates 2D field-centric displacement vector from robot-frame dead-wheel deltas.
     *
     * @param currentHeadingRadians Current robot heading in radians ($rad$), CCW-positive.
     * @param deltaForwardMeters Forward dead-wheel displacement delta in meters ($m$).
     * @param deltaStrafeMeters Strafe dead-wheel displacement delta in meters ($m$).
     * @return Field-relative displacement vector [Translation2d] in meters ($m$).
     */
    fun calculateDeltaPose(
        currentHeadingRadians: Double,
        deltaForwardMeters: Double,
        deltaStrafeMeters: Double
    ): Translation2d {
        // Rotate the robot-centric deltas by the current heading to get field-centric deltas
        val cosH = cos(currentHeadingRadians)
        val sinH = sin(currentHeadingRadians)
        
        val deltaX = deltaForwardMeters * cosH - deltaStrafeMeters * sinH
        val deltaY = deltaForwardMeters * sinH + deltaStrafeMeters * cosH
        
        return Translation2d(deltaX, deltaY)
    }

    /**
     * Calculates field-centric $\Delta x$ displacement in meters ($m$).
     *
     * @param currentHeadingRadians Robot heading in radians ($rad$), CCW-positive.
     * @param deltaForwardMeters Forward displacement in meters ($m$).
     * @param deltaStrafeMeters Strafe displacement in meters ($m$).
     * @return Field X displacement in meters ($m$).
     */
    fun calculateDeltaX(currentHeadingRadians: Double, deltaForwardMeters: Double, deltaStrafeMeters: Double): Double {
        return deltaForwardMeters * cos(currentHeadingRadians) - deltaStrafeMeters * sin(currentHeadingRadians)
    }

    /**
     * Calculates field-centric $\Delta y$ displacement in meters ($m$).
     *
     * @param currentHeadingRadians Robot heading in radians ($rad$), CCW-positive.
     * @param deltaForwardMeters Forward displacement in meters ($m$).
     * @param deltaStrafeMeters Strafe displacement in meters ($m$).
     * @return Field Y displacement in meters ($m$).
     */
    fun calculateDeltaY(currentHeadingRadians: Double, deltaForwardMeters: Double, deltaStrafeMeters: Double): Double {
        return deltaForwardMeters * sin(currentHeadingRadians) + deltaStrafeMeters * cos(currentHeadingRadians)
    }
}

