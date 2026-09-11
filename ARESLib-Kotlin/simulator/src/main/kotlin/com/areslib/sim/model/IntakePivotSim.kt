package com.areslib.sim.model

/**
 * High-fidelity rotational dynamics simulator for a gravity-loaded FRC intake pivot arm.
 * Models arm mass, center of mass offset, motor torque, and gravity vectors.
 */
class IntakePivotSim(
    val armMassKg: Double = 3.5,            // M (kg)
    val lengthToComMeters: Double = 0.35,   // Center of Mass distance (meters)
    val momentOfInertia: Double = 0.15,     // J (kg * m^2)
    val kt: Double = 0.02,                  // Torque constant (N-m/Amp)
    val resistance: Double = 0.06,           // Winding resistance (Ohms)
    val gearRatio: Double = 80.0,           // Gearbox reduction ratio (e.g., 80:1)
    val frictionCoeff: Double = 0.05        // Joint friction damping
) {
    private var angleRad = 0.0
    private var angularVelocityRadPerSec = 0.0
    private val accelerationPerVolt: Double
    private val gravityAcceleration: Double
    private val dampingRate: Double
    private val maxAngle = 120.0 * (Math.PI / 180.0)

    init {
        require(armMassKg.isFinite() && armMassKg >= 0.0)
        require(lengthToComMeters.isFinite() && lengthToComMeters >= 0.0)
        require(momentOfInertia.isFinite() && momentOfInertia > 0.0)
        require(kt.isFinite() && kt > 0.0)
        require(resistance.isFinite() && resistance > 0.0)
        require(gearRatio.isFinite() && gearRatio > 0.0)
        require(frictionCoeff.isFinite() && frictionCoeff >= 0.0)
        accelerationPerVolt = kt / resistance * gearRatio / momentOfInertia
        gravityAcceleration = armMassKg * 9.80665 * lengthToComMeters / momentOfInertia
        dampingRate = accelerationPerVolt * (gearRatio * 0.018) + frictionCoeff / momentOfInertia
        require(accelerationPerVolt.isFinite() && accelerationPerVolt > 0.0)
        require(gravityAcceleration.isFinite() && dampingRate.isFinite())
    }

    /**
     * Exponential midpoint integration: solve stiff motor damping exactly and sample gravity at
     * a predicted midpoint. At most 5 ms per substep (four at a normal 20 ms loop), with scalar
     * scratch state and coefficients computed once per update. Hard stops dissipate impact velocity.
     * Invalid inputs or unrepresentable results throw before state changes.
     * @param motorVoltage Finite applied voltage (nominally -12.0 to 12.0 V)
     * @param dtSeconds Finite, nonnegative time, at most 50 seconds (10,000 substeps); zero is a no-op
     */
    fun update(motorVoltage: Double, dtSeconds: Double) {
        require(motorVoltage.isFinite())
        require(dtSeconds.isFinite() && dtSeconds >= 0.0 && dtSeconds <= 50.0)
        if (dtSeconds == 0.0) return
        val steps = kotlin.math.ceil(dtSeconds / 0.005).toInt().coerceAtLeast(1)
        val h = dtSeconds / steps
        val decay = Math.exp(-dampingRate * h)
        val response = velocityResponse(h)
        val positionResponse = positionResponse(h, response)
        val halfResponse = velocityResponse(h * 0.5)
        val halfPositionResponse = positionResponse(h * 0.5, halfResponse)
        val drive = motorVoltage * accelerationPerVolt
        require(drive.isFinite())
        var angle = angleRad
        var velocity = angularVelocityRadPerSec
        repeat(steps) {
            val initialAcceleration = drive - gravityAcceleration * Math.cos(angle)
            val midpoint = angle + velocity * halfResponse + initialAcceleration * halfPositionResponse
            require(midpoint.isFinite())
            val midpointAcceleration = drive - gravityAcceleration * Math.cos(midpoint.coerceIn(0.0, maxAngle))
            val nextAngle = angle + velocity * response + midpointAcceleration * positionResponse
            val nextVelocity = velocity * decay + midpointAcceleration * response
            require(nextAngle.isFinite() && (nextVelocity * (180.0 / Math.PI)).isFinite())
            angle = nextAngle.coerceIn(0.0, maxAngle)
            velocity = if (nextAngle <= 0.0 || nextAngle >= maxAngle) 0.0 else nextVelocity
        }
        angleRad = angle
        angularVelocityRadPerSec = velocity
    }

    private fun velocityResponse(h: Double): Double {
        val x = dampingRate * h
        return if (x < 1e-4) h * (1.0 - x / 2.0 + x * x / 6.0 - x * x * x / 24.0)
        else -Math.expm1(-x) / dampingRate
    }

    private fun positionResponse(h: Double, response: Double): Double {
        val x = dampingRate * h
        // Series avoids cancellation in h - velocityResponse(h), including zero damping.
        return if (x < 1e-4) h * h * (0.5 - x / 6.0 + x * x / 24.0 - x * x * x / 120.0)
        else (h - response) / dampingRate
    }

    /** Current pivot angle in degrees */
    val angleDegrees: Double
        get() = angleRad * (180.0 / Math.PI)

    /** Current rotational velocity in degrees per second */
    val velocityDegreesPerSec: Double
        get() = angularVelocityRadPerSec * (180.0 / Math.PI)

    /** Resets to a finite angle within the physical 0..120 degree range, with zero velocity. */
    fun reset(initialAngleDegrees: Double = 0.0) {
        require(initialAngleDegrees.isFinite() && initialAngleDegrees in 0.0..120.0)
        angleRad = initialAngleDegrees * (Math.PI / 180.0)
        angularVelocityRadPerSec = 0.0
    }
}
