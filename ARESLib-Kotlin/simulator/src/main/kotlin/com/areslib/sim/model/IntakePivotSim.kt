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
    private val g = 9.80665                 // Acceleration due to gravity (m/s^2)

    /**
     * Step the physics simulation.
     * @param motorVoltage Applied voltage (-12.0 to 12.0 V)
     * @param dtSeconds Loop time step in seconds
     */
    fun update(motorVoltage: Double, dtSeconds: Double) {
        // Motor output torque after gear reduction
        val backEMF = (angularVelocityRadPerSec * gearRatio) * 0.018 // Back-EMF constant approximation
        val motorCurrent = (motorVoltage - backEMF) / resistance
        val motorTorque = motorCurrent * kt
        val outputTorque = motorTorque * gearRatio
        
        // Gravity loaded torque: T_g = M * g * d * cos(theta)
        // theta is 0 when horizontal (max gravity torque)
        val gravityTorque = armMassKg * g * lengthToComMeters * Math.cos(angleRad)
        
        // Friction torque
        val frictionTorque = frictionCoeff * angularVelocityRadPerSec
        
        // Total torque: T_net = T_motor - T_gravity - T_friction
        val netTorque = outputTorque - gravityTorque - frictionTorque
        
        // Angular acceleration: alpha = T_net / J
        val angularAcceleration = netTorque / momentOfInertia
        
        // Integrate velocity & position
        angularVelocityRadPerSec += angularAcceleration * dtSeconds
        angleRad += angularVelocityRadPerSec * dtSeconds
        
        // Clamp to physical hard stops (0 to 120 degrees)
        val minAngle = 0.0
        val maxAngle = 120.0 * (Math.PI / 180.0)
        
        when {
            angleRad < minAngle -> {
                angleRad = minAngle
                angularVelocityRadPerSec = 0.0
            }
            angleRad > maxAngle -> {
                angleRad = maxAngle
                angularVelocityRadPerSec = 0.0
            }
        }
    }

    /** Current pivot angle in degrees */
    val angleDegrees: Double
        get() = angleRad * (180.0 / Math.PI)

    /** Current rotational velocity in degrees per second */
    val velocityDegreesPerSec: Double
        get() = angularVelocityRadPerSec * (180.0 / Math.PI)

    /** Resets the pivot to 0 degrees */
    fun reset(initialAngleDegrees: Double = 0.0) {
        angleRad = initialAngleDegrees * (Math.PI / 180.0)
        angularVelocityRadPerSec = 0.0
    }
}
