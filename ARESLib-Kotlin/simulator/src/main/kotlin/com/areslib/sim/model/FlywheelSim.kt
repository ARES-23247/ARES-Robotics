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
    private val accelerationPerVolt: Double
    private val dampingRate: Double

    init {
        require(momentOfInertia.isFinite() && momentOfInertia > 0.0)
        require(kt.isFinite() && kt > 0.0)
        require(ke.isFinite() && ke >= 0.0)
        require(resistance.isFinite() && resistance > 0.0)
        require(frictionCoeff.isFinite() && frictionCoeff >= 0.0)
        accelerationPerVolt = kt / resistance / momentOfInertia
        dampingRate = accelerationPerVolt * ke + frictionCoeff / momentOfInertia
        require(accelerationPerVolt.isFinite() && accelerationPerVolt > 0.0)
        require(dampingRate.isFinite())
    }

    /**
     * Exact constant-voltage solution of the linear motor/damping equation, with a zero-speed stop.
     * Invalid inputs or unrepresentable results throw before state changes. No substep loop is needed.
     * @param voltage Finite applied voltage (nominally -12.0 to 12.0 V)
     * @param dtSeconds Finite, nonnegative loop time in seconds; zero is a no-op
     */
    fun update(voltage: Double, dtSeconds: Double) {
        require(voltage.isFinite())
        require(dtSeconds.isFinite() && dtSeconds >= 0.0)
        if (dtSeconds == 0.0) return
        val exponent = dampingRate * dtSeconds
        val response = if (exponent == 0.0) dtSeconds else -Math.expm1(-exponent) / dampingRate
        val next = angularVelocityRadPerSec * Math.exp(-exponent) +
            (voltage * accelerationPerVolt) * response
        require(next.isFinite() && (next * (60.0 / (2.0 * Math.PI))).isFinite())
        angularVelocityRadPerSec = next.coerceAtLeast(0.0)
    }

    /** Returns current velocity in RPM */
    val velocityRpm: Double
        get() = angularVelocityRadPerSec * (60.0 / (2.0 * Math.PI))

    /** Returns current stator draw in Amps */
    fun getCurrentAmps(voltage: Double): Double {
        require(voltage.isFinite())
        val backEMF = angularVelocityRadPerSec * ke
        val current = Math.abs((voltage - backEMF) / resistance)
        require(current.isFinite())
        return current
    }

    /** Resets simulator velocity */
    fun reset() {
        angularVelocityRadPerSec = 0.0
    }
}
