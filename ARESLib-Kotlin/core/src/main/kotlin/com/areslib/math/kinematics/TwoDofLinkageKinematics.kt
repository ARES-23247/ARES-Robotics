package com.areslib.math.kinematics

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometric and inertial properties of a 2-degree-of-freedom planar serial linkage or articulated arm.
 *
 * All lengths are in meters, masses in kilograms, and gravity in m/s².
 */
data class TwoDofLinkageParameters(
    /** Length of the proximal link (base to elbow) in meters. */
    val l1: Double,
    /** Length of the distal link (elbow to end-effector) in meters. */
    val l2: Double,
    /** Mass of the proximal link in kilograms. */
    val m1: Double,
    /** Mass of the distal link in kilograms. */
    val m2: Double,
    /** Center of mass distance along link 1 from joint 1 in meters. Defaults to mid-link. */
    val rc1: Double = l1 / 2.0,
    /** Center of mass distance along link 2 from joint 2 in meters. Defaults to mid-link. */
    val rc2: Double = l2 / 2.0,
    /** Acceleration due to gravity in m/s². Defaults to standard Earth gravity (9.80665). */
    val g: Double = 9.80665,
) {
    init {
        require(l1.isFinite() && l1 > 0.0) { "Link 1 length must be finite and strictly positive" }
        require(l2.isFinite() && l2 > 0.0) { "Link 2 length must be finite and strictly positive" }
        require(m1.isFinite() && m1 >= 0.0) { "Link 1 mass must be finite and non-negative" }
        require(m2.isFinite() && m2 >= 0.0) { "Link 2 mass must be finite and non-negative" }
        require(rc1.isFinite() && rc1 in 0.0..l1) { "Link 1 center of mass must lie on link 1" }
        require(rc2.isFinite() && rc2 in 0.0..l2) { "Link 2 center of mass must lie on link 2" }
        require(g.isFinite() && g > 0.0) { "Gravity must be finite and positive" }
    }

    /** Maximum reach radius of the linkage from base origin in meters. */
    val maxReach: Double get() = l1 + l2

    /** Minimum reach radius (inner workspace boundary) in meters. */
    val minReach: Double get() = kotlin.math.abs(l1 - l2)
}

/** Joint angle coordinates in radians. */
data class LinkageJointAngles(
    /** Base joint angle in radians relative to horizontal ground (+X axis). CCW positive. */
    val theta1Rad: Double,
    /** Elbow joint angle in radians relative to Link 1 axis. CCW positive. */
    val theta2Rad: Double,
)

/** End-effector Cartesian coordinates in meters. */
data class LinkageEndEffectorPose(
    val x: Double,
    val y: Double,
)

/** Geometric branch selection for inverse kinematics solutions. */
enum class ElbowConfiguration {
    ELBOW_UP,
    ELBOW_DOWN,
}

/**
 * Analytical kinematics, Jacobian singularity checking, and Lagrangian gravity feedforward solver
 * for 2-DOF planar linkages and articulated robotic arms.
 *
 * Raw forward/Jacobian/gravity results use IEEE Double values: unknown angles produce NaN,
 * and genuinely unrepresentable final components may be infinite. Callers must validate
 * outputs before treating them as actuator commands. Buffered overloads write only their
 * documented elements and reject short buffers before mutation. Instances are immutable.
 */
class TwoDofLinkageKinematics(val params: TwoDofLinkageParameters) {
    private val lengthScale = maxOf(params.l1, params.l2)
    private val scaledL1 = params.l1 / lengthScale
    private val scaledL2 = params.l2 / lengthScale
    private val scaledShortLink = minOf(scaledL1, scaledL2)
    private val scaledMinimumReach = kotlin.math.abs(scaledL1 - scaledL2)
    private val scaledMaximumReach = scaledL1 + scaledL2
    private val workspaceRoundoff = 8.0 * Math.ulp(scaledMaximumReach)
    private val determinantCoefficient = LinkageCoefficient.product(params.l1, params.l2)
    private val proximalGravity = LinkageCoefficient.product(params.m1, params.rc1, params.g)
    private val translatedGravity = LinkageCoefficient.product(params.m2, params.l1, params.g)
    private val distalGravity = LinkageCoefficient.product(params.m2, params.rc2, params.g)


    /**
     * Computes the Cartesian (X, Y) position of the end-effector from joint angles.
     *
     * X = L1 * cos(theta1) + L2 * cos(theta1 + theta2)
     * Y = L1 * sin(theta1) + L2 * sin(theta1 + theta2)
     */
    fun forwardKinematics(theta1: Double, theta2: Double): LinkageEndEffectorPose =
        geometry(theta1, theta2) { c1, s1, c12, s12 ->
            LinkageEndEffectorPose(params.l1 * c1 + params.l2 * c12, params.l1 * s1 + params.l2 * s12)
        }

    /** Allocation-free forward kinematics for periodic simulation/control paths. */
    fun forwardKinematics(theta1: Double, theta2: Double, output: DoubleArray) {
        require(output.size >= 2) { "Forward-kinematics output requires two elements" }
        geometry(theta1, theta2) { c1, s1, c12, s12 ->
            output[0] = params.l1 * c1 + params.l2 * c12
            output[1] = params.l1 * s1 + params.l2 * s12
        }
    }

    private inline fun <T> geometry(theta1: Double, theta2: Double,
                                   result: (Double, Double, Double, Double) -> T): T {
        val c1 = cos(theta1)
        val s1 = sin(theta1)
        val sum = theta1 + theta2
        if (sum.isFinite()) return result(c1, s1, cos(sum), sin(sum))
        val c2 = cos(theta2)
        val s2 = sin(theta2)
        return result(c1, s1, c1 * c2 - s1 * s2, s1 * c2 + c1 * s2)
    }

    /**
     * Checks whether a finite target lies within the workspace, allowing eight ulps of
     * normalized outer-radius roundoff. Uses the same boundary contract as inverse kinematics.
     */
    fun isReachable(x: Double, y: Double): Boolean = elbowCosine(x, y).isFinite()

    // One common workspace contract for reachability and IK. Scaling prevents squared
    // lengths from overflowing/underflowing; the tolerance is roundoff in normalized units.
    private fun elbowCosine(x: Double, y: Double): Double {
        if (!x.isFinite() || !y.isFinite()) return Double.NaN
        val radius = hypot(x / lengthScale, y / lengthScale)
        if (!radius.isFinite() || radius < scaledMinimumReach - workspaceRoundoff ||
            radius > scaledMaximumReach + workspaceRoundoff) return Double.NaN
        // For a link smaller than the normalized Double domain, the workspace is
        // indistinguishable from a circle at this precision. Choose a finite elbow branch.
        if (scaledShortLink == 0.0) return 0.0
        // (r² - 1 - b²) / (2b), factored to retain small r-1 offsets and avoid b² underflow.
        return (0.5 * (((radius - 1.0) / scaledShortLink) * (radius + 1.0) - scaledShortLink))
            .coerceIn(-1.0, 1.0)
    }

    /**
     * Computes analytical inverse kinematics for a target (X, Y) coordinate.
     *
     * @param x Target X position in meters.
     * @param y Target Y position in meters.
     * @param config Geometric solution branch (Elbow Up vs Elbow Down).
     * @return [LinkageJointAngles] if point is reachable, or null for a nonfinite/outside target.
     * Boundary roundoff is clamped to the workspace. When one link is below the normalized
     * Double domain, its angular contribution is unresolved and a right-angle elbow is selected.
     */
    fun inverseKinematics(x: Double, y: Double, config: ElbowConfiguration = ElbowConfiguration.ELBOW_UP): LinkageJointAngles? {
        val clampedCos = elbowCosine(x, y)
        if (!clampedCos.isFinite()) return null
        val sinTheta2Mag = sqrt((1.0 - clampedCos) * (1.0 + clampedCos))
        val sinTheta2 = when (config) {
            ElbowConfiguration.ELBOW_UP -> -sinTheta2Mag
            ElbowConfiguration.ELBOW_DOWN -> sinTheta2Mag
        }
        val theta2 = atan2(sinTheta2, clampedCos)
        val k1 = scaledL1 + scaledL2 * clampedCos
        val k2 = scaledL2 * sinTheta2
        val theta1 = atan2(y, x) - atan2(k2, k1)

        return LinkageJointAngles(theta1, theta2)
    }

    /**
     * Computes the 2x2 geometric Jacobian matrix J(theta) mapping joint velocities to end-effector velocities:
     *
     * [vx; vy] = J * [theta1_dot; theta2_dot]
     */
    fun jacobian(theta1: Double, theta2: Double): Array<DoubleArray> =
        geometry(theta1, theta2) { c1, s1, c12, s12 ->
            arrayOf(doubleArrayOf(-params.l1 * s1 - params.l2 * s12, -params.l2 * s12),
                doubleArrayOf(params.l1 * c1 + params.l2 * c12, params.l2 * c12))
        }

    /** Allocation-free row-major 2x2 Jacobian: `[j11, j12, j21, j22]`. */
    fun jacobian(theta1: Double, theta2: Double, output: DoubleArray) {
        require(output.size >= 4) { "Jacobian output requires four elements" }
        geometry(theta1, theta2) { c1, s1, c12, s12 ->
            output[0] = -params.l1 * s1 - params.l2 * s12
            output[1] = -params.l2 * s12
            output[2] = params.l1 * c1 + params.l2 * c12
            output[3] = params.l2 * c12
        }
    }

    /**
     * Computes the determinant of the Jacobian matrix det(J) = L1 * L2 * sin(theta2).
     *
     * Singularity occurs when theta2 is 0 or ±π (arm fully outstretched or fully folded back).
     */
    fun jacobianDeterminant(theta2: Double): Double {
        return determinantCoefficient.times(sin(theta2))
    }

    /**
     * Detects proximity using `abs(sin(theta2)) < threshold`, independent of length units.
     * Threshold must be finite and non-negative; zero disables the strict proximity band.
     * Unknown joint angles conservatively report near-singular.
     */
    fun isNearSingularity(theta1: Double, theta2: Double, threshold: Double = 0.05): Boolean {
        require(threshold.isFinite() && threshold >= 0.0) { "Singularity threshold must be finite and non-negative" }
        if (!theta1.isFinite() || !theta2.isFinite()) return true
        return kotlin.math.abs(sin(theta2)) < threshold
    }

    /**
     * Computes analytical Lagrangian multivariable continuous gravity compensation torque vector G(theta).
     *
     * G1 = (m1 * rc1 + m2 * L1) * g * cos(theta1) + m2 * rc2 * g * cos(theta1 + theta2)
     * G2 = m2 * rc2 * g * cos(theta1 + theta2)
     *
     * @return DoubleArray of size 2 containing [torque1_Nm, torque2_Nm].
     */
    fun gravityTorque(theta1: Double, theta2: Double): DoubleArray = gravity(theta1, theta2) { g1, g2 ->
        doubleArrayOf(g1, g2)
    }

    /** Allocation-free gravity torque vector `[joint1Nm, joint2Nm]`. */
    fun gravityTorque(theta1: Double, theta2: Double, output: DoubleArray) {
        require(output.size >= 2) { "Gravity-torque output requires two elements" }
        gravity(theta1, theta2) { g1, g2 -> output[0] = g1; output[1] = g2 }
    }

    private inline fun <T> gravity(theta1: Double, theta2: Double, result: (Double, Double) -> T): T {
        val c1 = cos(theta1)
        val c12 = linkageCosSum(theta1, theta2)
        return result(LinkageCoefficient.sum(proximalGravity, c1, translatedGravity, c1, distalGravity, c12),
            distalGravity.times(c12))
    }
}
