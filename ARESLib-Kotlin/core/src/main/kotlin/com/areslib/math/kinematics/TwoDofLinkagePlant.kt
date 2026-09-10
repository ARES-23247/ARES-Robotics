package com.areslib.math.kinematics

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Physical parameters for the deterministic two-joint linkage simulator.
 *
 * The voltage-to-torque constants include the motor, gearbox, and mechanism efficiency. Joint
 * limits use the same CCW-positive radians as [TwoDofLinkageKinematics].
 */
data class TwoDofLinkagePlantParameters(
    val linkage: TwoDofLinkageParameters,
    val joint1TorquePerVoltNm: Double,
    val joint2TorquePerVoltNm: Double,
    val joint1ViscousDampingNmPerRadPerSec: Double = 0.08,
    val joint2ViscousDampingNmPerRadPerSec: Double = 0.05,
    val joint1MinimumRad: Double = -Math.PI,
    val joint1MaximumRad: Double = Math.PI,
    val joint2MinimumRad: Double = -Math.PI,
    val joint2MaximumRad: Double = Math.PI,
) {
    init {
        require(linkage.m1 > 0.0 && linkage.m2 > 0.0) { "Dynamic linkage simulation requires positive link masses" }
        require(joint1TorquePerVoltNm.isFinite() && joint1TorquePerVoltNm > 0.0) {
            "Joint 1 torque-per-volt must be finite and positive"
        }
        require(joint2TorquePerVoltNm.isFinite() && joint2TorquePerVoltNm > 0.0) {
            "Joint 2 torque-per-volt must be finite and positive"
        }
        require(joint1ViscousDampingNmPerRadPerSec.isFinite() && joint1ViscousDampingNmPerRadPerSec >= 0.0)
        require(joint2ViscousDampingNmPerRadPerSec.isFinite() && joint2ViscousDampingNmPerRadPerSec >= 0.0)
        require(joint1MinimumRad.isFinite() && joint1MaximumRad.isFinite() && joint1MinimumRad < joint1MaximumRad)
        require(joint2MinimumRad.isFinite() && joint2MaximumRad.isFinite() && joint2MinimumRad < joint2MaximumRad)
    }
}

/**
 * Allocation-free rigid-body plant for a serial planar two-link arm.
 *
 * [step] uses the standard two-link inertia, Coriolis, gravity, and viscous-damping model with
 * bounded semi-implicit integration. It is intended for deterministic desktop/mock verification,
 * not vendor motor-controller characterization. Centroidal inertias assume rods of length L
 * with inertia mL²/12, even when a custom center-of-mass offset is supplied. Coefficients must
 * fit the Double domain; nonfinite/underflowed effective inertia is rejected at construction.
 */
class TwoDofLinkagePlant(val params: TwoDofLinkagePlantParameters) {
    // Geometry is immutable. Cache physical terms once rather than rebuilding the inertia
    // and gravity coefficients at every 2 ms integration substep.
    private val linkage = params.linkage
    private val proximalInertia = LinkageCoefficient.product(linkage.m1, linkage.l1, linkage.l1, 1.0 / 12.0).value +
        LinkageCoefficient.product(linkage.m1, linkage.rc1, linkage.rc1).value
    private val distalCentroidInertia = LinkageCoefficient.product(linkage.m2, linkage.l2, linkage.l2, 1.0 / 12.0).value
    private val distalOffsetInertia = LinkageCoefficient.product(linkage.m2, linkage.rc2, linkage.rc2).value
    private val distalInertia = distalCentroidInertia + distalOffsetInertia
    private val translatedInertia = LinkageCoefficient.product(linkage.m2, linkage.l1, linkage.l1).value
    private val cross = LinkageCoefficient.product(linkage.m2, linkage.l1, linkage.rc2).value
    private val crossRatio = cross / distalInertia
    private val schurBase = proximalInertia + translatedInertia * (distalCentroidInertia / distalInertia)
    private val schurSine = translatedInertia * (distalOffsetInertia / distalInertia)
    private val gravityBase = LinkageCoefficient.product(linkage.m1, linkage.rc1, linkage.g).value +
        LinkageCoefficient.product(linkage.m2, linkage.l1, linkage.g).value
    private val gravityDistal = LinkageCoefficient.product(linkage.m2, linkage.rc2, linkage.g).value

    init {
        require(distalInertia.isFinite() && distalInertia > 0.0 &&
            schurBase.isFinite() && schurBase > 0.0 && schurSine.isFinite() &&
            cross.isFinite() && crossRatio.isFinite() && gravityBase.isFinite() && gravityDistal.isFinite()) {
            "Linkage inertia and gravity coefficients must be representable as finite Doubles"
        }
    }

    var joint1PositionRad: Double = 0.0.coerceIn(params.joint1MinimumRad, params.joint1MaximumRad)
        private set
    var joint2PositionRad: Double = 0.0.coerceIn(params.joint2MinimumRad, params.joint2MaximumRad)
        private set
    var joint1VelocityRadPerSec: Double = 0.0
        private set
    var joint2VelocityRadPerSec: Double = 0.0
        private set

    /** Resets the plant to a finite, limit-clamped state without allocating. */
    fun reset(
        joint1PositionRad: Double = 0.0,
        joint2PositionRad: Double = 0.0,
        joint1VelocityRadPerSec: Double = 0.0,
        joint2VelocityRadPerSec: Double = 0.0,
    ) {
        require(
            joint1PositionRad.isFinite() && joint2PositionRad.isFinite() &&
                joint1VelocityRadPerSec.isFinite() && joint2VelocityRadPerSec.isFinite(),
        ) { "Linkage reset state must be finite" }
        this.joint1PositionRad = joint1PositionRad.coerceIn(params.joint1MinimumRad, params.joint1MaximumRad)
        this.joint2PositionRad = joint2PositionRad.coerceIn(params.joint2MinimumRad, params.joint2MaximumRad)
        this.joint1VelocityRadPerSec = joint1VelocityRadPerSec
        this.joint2VelocityRadPerSec = joint2VelocityRadPerSec
        enforceJointLimits()
    }

    /**
     * Advances accepted actuator voltages in at most 50 equal substeps. Invalid voltages fail
     * neutral. Unrepresentable dynamics throw before committing the external step; callers
     * must stop simulation and correct/reset its state rather than treating it as new feedback.
     */
    fun step(joint1Voltage: Double, joint2Voltage: Double, dtSeconds: Double) {
        require(dtSeconds.isFinite() && dtSeconds > 0.0 && dtSeconds <= MAX_EXTERNAL_STEP_SECONDS) {
            "Linkage timestep must be finite and in (0, $MAX_EXTERNAL_STEP_SECONDS] seconds"
        }
        val voltage1 = if (joint1Voltage.isFinite()) joint1Voltage.coerceIn(-12.0, 12.0) else 0.0
        val voltage2 = if (joint2Voltage.isFinite()) joint2Voltage.coerceIn(-12.0, 12.0) else 0.0
        val initialQ1 = joint1PositionRad
        val initialQ2 = joint2PositionRad
        val initialV1 = joint1VelocityRadPerSec
        val initialV2 = joint2VelocityRadPerSec
        val steps = ceil(dtSeconds / MAX_INTEGRATION_STEP_SECONDS).toInt().coerceAtLeast(1)
        val dt = dtSeconds / steps
        try {
            repeat(steps) { integrate(voltage1, voltage2, dt) }
        } catch (failure: IllegalStateException) {
            joint1PositionRad = initialQ1
            joint2PositionRad = initialQ2
            joint1VelocityRadPerSec = initialV1
            joint2VelocityRadPerSec = initialV2
            throw failure
        }
    }

    private fun integrate(voltage1: Double, voltage2: Double, dt: Double) {
        val q1 = joint1PositionRad
        val q2 = joint2PositionRad
        val v1 = joint1VelocityRadPerSec
        val v2 = joint2VelocityRadPerSec

        val sinQ2 = sin(q2)
        val coupling = 1.0 + crossRatio * cos(q2)
        // Eliminate joint 2 using its positive diagonal pivot. The Schur complement is
        // a sum of nonnegative physical terms, avoiding det(M)'s subtractive cancellation
        // and its squared inertia units (which previously triggered an absolute cutoff).
        val schur = schurBase + schurSine * sinQ2 * sinQ2
        val h = -cross * sinQ2
        val coriolis1 = if (h == 0.0) 0.0 else h * (2.0 * v1 * v2 + v2 * v2)
        val coriolis2 = if (h == 0.0) 0.0 else -h * v1 * v1
        val gravity2 = gravityDistal * linkageCosSum(q1, q2)
        val gravity1 = gravityBase * cos(q1) + gravity2
        val rhs1 = voltage1 * params.joint1TorquePerVoltNm - coriolis1 - gravity1 -
            params.joint1ViscousDampingNmPerRadPerSec * v1
        val rhs2 = voltage2 * params.joint2TorquePerVoltNm - coriolis2 - gravity2 -
            params.joint2ViscousDampingNmPerRadPerSec * v2
        val acceleration1 = (rhs1 - coupling * rhs2) / schur
        val acceleration2 = rhs2 / distalInertia - coupling * acceleration1
        val nextV1 = v1 + acceleration1 * dt
        val nextV2 = v2 + acceleration2 * dt
        val nextQ1 = q1 + nextV1 * dt
        val nextQ2 = q2 + nextV2 * dt
        check(schur.isFinite() && schur > 0.0 && acceleration1.isFinite() && acceleration2.isFinite() &&
            nextV1.isFinite() && nextV2.isFinite() && nextQ1.isFinite() && nextQ2.isFinite()) {
            "Linkage dynamics exceeded finite numerical range"
        }
        joint1VelocityRadPerSec = nextV1
        joint2VelocityRadPerSec = nextV2
        joint1PositionRad = nextQ1
        joint2PositionRad = nextQ2
        enforceJointLimits()
    }

    private fun enforceJointLimits() {
        if (joint1PositionRad <= params.joint1MinimumRad) {
            joint1PositionRad = params.joint1MinimumRad
            if (joint1VelocityRadPerSec < 0.0) joint1VelocityRadPerSec = 0.0
        } else if (joint1PositionRad >= params.joint1MaximumRad) {
            joint1PositionRad = params.joint1MaximumRad
            if (joint1VelocityRadPerSec > 0.0) joint1VelocityRadPerSec = 0.0
        }
        if (joint2PositionRad <= params.joint2MinimumRad) {
            joint2PositionRad = params.joint2MinimumRad
            if (joint2VelocityRadPerSec < 0.0) joint2VelocityRadPerSec = 0.0
        } else if (joint2PositionRad >= params.joint2MaximumRad) {
            joint2PositionRad = params.joint2MaximumRad
            if (joint2VelocityRadPerSec > 0.0) joint2VelocityRadPerSec = 0.0
        }
    }

    private companion object {
        const val MAX_EXTERNAL_STEP_SECONDS = 0.1
        const val MAX_INTEGRATION_STEP_SECONDS = 0.002
        // Kotlin emitted this old private-companion const as a public JVM field. Retain
        // its binary ABI for existing consumers; the solver no longer uses this cutoff.
        @Suppress("unused")
        const val MIN_INERTIA_DETERMINANT = 1e-12
    }
}
