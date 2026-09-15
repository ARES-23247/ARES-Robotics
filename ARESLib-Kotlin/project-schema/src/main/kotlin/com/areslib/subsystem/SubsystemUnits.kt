package com.areslib.subsystem

/** Canonical unit policy shared by validation, code generation, and catalog-backed editors. */
object SubsystemUnits {
    fun controlUnitsCompatible(first: String?, second: String?): Boolean {
        if (first.isNullOrBlank() || second.isNullOrBlank()) return true
        return normalize(first) == normalize(second)
    }

    fun canRepresentVelocity(unit: String?): Boolean = unit.isNullOrBlank() ||
        normalize(unit) in setOf("m/s", "rad/s", "rot/s")

    fun canRepresentAcceleration(unit: String?): Boolean = unit.isNullOrBlank() ||
        normalize(unit) in setOf("m/s^2", "rad/s^2", "rot/s^2")

    fun isCanonicalAngle(unit: String?): Boolean = !unit.isNullOrBlank() && normalize(unit) == "rad"

    fun motorMeasurementScale(
        nativeUnitsPerMotorRevolution: Double,
        motorRevolutionsPerMechanismRevolution: Double,
        stateUnitsPerMechanismRevolution: Double,
    ): Double {
        require(nativeUnitsPerMotorRevolution.isFinite() && nativeUnitsPerMotorRevolution > 0.0) {
            "Native units per motor revolution must be finite and positive"
        }
        require(motorRevolutionsPerMechanismRevolution.isFinite() && motorRevolutionsPerMechanismRevolution > 0.0) {
            "Motor revolutions per mechanism revolution must be finite and positive"
        }
        require(stateUnitsPerMechanismRevolution.isFinite() && stateUnitsPerMechanismRevolution > 0.0) {
            "State units per mechanism revolution must be finite and positive"
        }
        // Scale each factor by a power of two so an intermediate product cannot overflow or
        // underflow when the final conversion is representable. This also preserves subnormals.
        val nativeExponent = Math.getExponent(nativeUnitsPerMotorRevolution)
        val gearingExponent = Math.getExponent(motorRevolutionsPerMechanismRevolution)
        val stateExponent = Math.getExponent(stateUnitsPerMechanismRevolution)
        val fraction = Math.scalb(stateUnitsPerMechanismRevolution, -stateExponent) /
            (Math.scalb(nativeUnitsPerMotorRevolution, -nativeExponent) *
                Math.scalb(motorRevolutionsPerMechanismRevolution, -gearingExponent))
        val scale = Math.scalb(fraction, stateExponent - nativeExponent - gearingExponent)
        require(scale.isFinite() && scale > 0.0) {
            "Motor measurement scale must be representable as a finite positive value"
        }
        return scale
    }

    private fun normalize(unit: String): String = when (unit.trim().lowercase()) {
        "radian", "radians" -> "rad"
        "degree", "degrees", "°" -> "deg"
        "rotation", "rotations", "turn", "turns" -> "rot"
        "meter", "meters", "metre", "metres" -> "m"
        "meter/second", "meters/second", "metre/second", "metres/second" -> "m/s"
        "radian/second", "radians/second" -> "rad/s"
        "rotation/second", "rotations/second", "turn/second", "turns/second" -> "rot/s"
        "meter/second²", "meters/second²", "meter/second^2", "meters/second^2", "m/s²" -> "m/s^2"
        "radian/second²", "radians/second²", "radian/second^2", "radians/second^2", "rad/s²" -> "rad/s^2"
        "rotation/second²", "rotations/second²", "turn/second²", "turns/second²", "rot/s²" -> "rot/s^2"
        "volt", "volts" -> "v"
        "amp", "amps", "ampere", "amperes" -> "a"
        else -> unit.trim().lowercase().replace(" ", "")
    }
}
