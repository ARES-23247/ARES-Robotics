package com.areslib.sim.model

/**
 * High-fidelity rotational physics simulator for a dual-motor FRC flywheel shooter.
 * Emulates Torque, Back-EMF, Inertia, and Friction.
 */
class FlywheelSim(
    val momentOfInertia: Double = 0.005, // J (kg * m^2)
    val kt: Double = 0.018,              // Torque constant (N-m/Amp) - standard TalonFX/NEO estimate
    val ke: Double = 0.018,              // Back-EMF constant (V-s/rad)
    val resistance: Double = 0.05,       // Winding resistance (Ohms)
    val frictionCoeff: Double = 0.001    // Friction damping coefficient
) {
    private var angularVelocityRadPerSec = 0.0

    /**
     * Step the physics simulation.
     * @param voltage The applied voltage (-12.0 to 12.0 V)
     * @param dtSeconds Loop time step in seconds
     */
    fun update(voltage: Double, dtSeconds: Double) {
        // I = (V - w * Ke) / R
        val backEMF = angularVelocityRadPerSec * ke
        val current = (voltage - backEMF) / resistance
        
        // Te = I * Kt
        val electricalTorque = current * kt
        
        // Tf = d * w
        val frictionTorque = frictionCoeff * angularVelocityRadPerSec
        
        // Acceleration = (Te - Tf) / J
        val acceleration = (electricalTorque - frictionTorque) / momentOfInertia
        
        // Integrate velocity
        angularVelocityRadPerSec += acceleration * dtSeconds
        if (angularVelocityRadPerSec < 0.0) {
            angularVelocityRadPerSec = 0.0 // Uni-directional flywheel setup
        }
    }

    /** Returns current velocity in RPM */
    val velocityRpm: Double
        get() = (angularVelocityRadPerSec * 60.0) / (2.0 * Math.PI)

    /** Returns current stator draw in Amps */
    fun getCurrentAmps(voltage: Double): Double {
        val backEMF = angularVelocityRadPerSec * ke
        return Math.abs((voltage - backEMF) / resistance)
    }

    /** Resets simulator velocity */
    fun reset() {
        angularVelocityRadPerSec = 0.0
    }
}
