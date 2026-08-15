package com.areslib.math.kinematics

import kotlin.math.abs
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
 * not vendor motor-controller characterization.
 */
class TwoDofLinkagePlant(val params: TwoDofLinkagePlantParameters) {
    var joint1PositionRad: Double = 0.0
        private set
    var joint2PositionRad: Double = 0.0
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
    }

    /** Advances accepted actuator voltages through the plant. Invalid voltages fail neutral. */
    fun step(joint1Voltage: Double, joint2Voltage: Double, dtSeconds: Double) {
        require(dtSeconds.isFinite() && dtSeconds > 0.0 && dtSeconds <= MAX_EXTERNAL_STEP_SECONDS) {
            "Linkage timestep must be finite and in (0, $MAX_EXTERNAL_STEP_SECONDS] seconds"
        }
        val voltage1 = joint1Voltage.takeIf(Double::isFinite)?.coerceIn(-12.0, 12.0) ?: 0.0
        val voltage2 = joint2Voltage.takeIf(Double::isFinite)?.coerceIn(-12.0, 12.0) ?: 0.0
        var remaining = dtSeconds
        while (remaining > 0.0) {
            val dt = remaining.coerceAtMost(MAX_INTEGRATION_STEP_SECONDS)
            integrate(voltage1, voltage2, dt)
            remaining -= dt
        }
    }

    private fun integrate(voltage1: Double, voltage2: Double, dt: Double) {
        val linkage = params.linkage
        val q1 = joint1PositionRad
        val q2 = joint2PositionRad
        val v1 = joint1VelocityRadPerSec
        val v2 = joint2VelocityRadPerSec

        val i1 = linkage.m1 * linkage.l1 * linkage.l1 / 12.0
        val i2 = linkage.m2 * linkage.l2 * linkage.l2 / 12.0
        val cross = linkage.m2 * linkage.l1 * linkage.rc2
        val cosQ2 = cos(q2)
        val m11 = i1 + i2 + linkage.m1 * linkage.rc1 * linkage.rc1 +
            linkage.m2 * (linkage.l1 * linkage.l1 + linkage.rc2 * linkage.rc2 + 2.0 * linkage.l1 * linkage.rc2 * cosQ2)
        val m12 = i2 + linkage.m2 * (linkage.rc2 * linkage.rc2 + linkage.l1 * linkage.rc2 * cosQ2)
        val m22 = i2 + linkage.m2 * linkage.rc2 * linkage.rc2
        val determinant = m11 * m22 - m12 * m12
        check(determinant.isFinite() && abs(determinant) > MIN_INERTIA_DETERMINANT) {
            "Linkage inertia matrix is singular"
        }

        val h = -cross * sin(q2)
        val coriolis1 = h * (2.0 * v1 * v2 + v2 * v2)
        val coriolis2 = -h * v1 * v1
        val gravity1 = (linkage.m1 * linkage.rc1 + linkage.m2 * linkage.l1) * linkage.g * cos(q1) +
            linkage.m2 * linkage.rc2 * linkage.g * cos(q1 + q2)
        val gravity2 = linkage.m2 * linkage.rc2 * linkage.g * cos(q1 + q2)
        val rhs1 = voltage1 * params.joint1TorquePerVoltNm - coriolis1 - gravity1 -
            params.joint1ViscousDampingNmPerRadPerSec * v1
        val rhs2 = voltage2 * params.joint2TorquePerVoltNm - coriolis2 - gravity2 -
            params.joint2ViscousDampingNmPerRadPerSec * v2
        val acceleration1 = (rhs1 * m22 - rhs2 * m12) / determinant
        val acceleration2 = (m11 * rhs2 - m12 * rhs1) / determinant

        joint1VelocityRadPerSec += acceleration1 * dt
        joint2VelocityRadPerSec += acceleration2 * dt
        joint1PositionRad += joint1VelocityRadPerSec * dt
        joint2PositionRad += joint2VelocityRadPerSec * dt
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
        const val MIN_INERTIA_DETERMINANT = 1e-12
    }
}
