package com.ares.analytics.shared

/** Dimensions supported by dashboard display conversion. */
enum class UnitCategory {
    LENGTH, LINEAR_VELOCITY, ANGLE, ANGULAR_VELOCITY, TIME, VOLTAGE, CURRENT, TEMPERATURE, NONE
}

/** Unit metadata. [factorToBase] converts non-temperature values to the category's SI base unit. */
enum class RobotUnit(val symbol: String, val category: UnitCategory, val factorToBase: Double) {
    // Length (Base: Meter)
    METER("m", UnitCategory.LENGTH, 1.0),
    INCH("in", UnitCategory.LENGTH, 0.0254),
    FOOT("ft", UnitCategory.LENGTH, 0.3048),
    CENTIMETER("cm", UnitCategory.LENGTH, 0.01),

    // Linear velocity (Base: Meter/sec)
    METER_PER_SEC("m/s", UnitCategory.LINEAR_VELOCITY, 1.0),
    FOOT_PER_SEC("ft/s", UnitCategory.LINEAR_VELOCITY, 0.3048),

    // Angle (Base: Radian)
    RADIAN("rad", UnitCategory.ANGLE, 1.0),
    DEGREE("deg", UnitCategory.ANGLE, Math.PI / 180.0),
    ROTATION("rot", UnitCategory.ANGLE, 2 * Math.PI),

    // Angular Velocity (Base: Radian/sec)
    RAD_PER_SEC("rad/s", UnitCategory.ANGULAR_VELOCITY, 1.0),
    DEG_PER_SEC("deg/s", UnitCategory.ANGULAR_VELOCITY, Math.PI / 180.0),
    RPM("rpm", UnitCategory.ANGULAR_VELOCITY, (2 * Math.PI) / 60.0),

    // Time (Base: Second)
    SECOND("s", UnitCategory.TIME, 1.0),
    MILLISECOND("ms", UnitCategory.TIME, 0.001),
    MINUTE("min", UnitCategory.TIME, 60.0),

    // Voltage (Base: Volt)
    VOLT("V", UnitCategory.VOLTAGE, 1.0),
    MILLIVOLT("mV", UnitCategory.VOLTAGE, 0.001),

    // Current (Base: Ampere)
    AMPERE("A", UnitCategory.CURRENT, 1.0),
    MILLIAMPERE("mA", UnitCategory.CURRENT, 0.001),

    // Temperature (Base: Celsius)
    CELSIUS("°C", UnitCategory.TEMPERATURE, 1.0),
    FAHRENHEIT("°F", UnitCategory.TEMPERATURE, 1.0),
    KELVIN("K", UnitCategory.TEMPERATURE, 1.0);

    companion object {
        /** Looks up either a display symbol or enum name, ignoring case and surrounding whitespace. */
        fun fromSymbol(symbol: String): RobotUnit? {
            val clean = symbol.trim()
            return entries.find { it.symbol.equals(clean, ignoreCase = true) || it.name.equals(clean, ignoreCase = true) }
        }
    }
}

/** Conversion and conservative topic-name inference used by telemetry charts. */
object UnitConversion {
    private val camelWordBoundary = Regex("([a-z0-9])([A-Z])")
    private val ampereWord = Regex("(?i)(^|[^a-z0-9])amp(?:ere)?s?($|[^a-z0-9])")
    private val secondWord = Regex("(?i)(^|[^a-z0-9])(?:sec|secs|seconds?)($|[^a-z0-9])")
    private val millisecondWord = Regex("(?i)(^|[^a-z0-9])(?:ms|millis|milliseconds?|latency|looptime)($|[^a-z0-9])")
    private val rotationWord = Regex("(?i)(^|[^a-z0-9])(?:rot|rots|rotations?)($|[^a-z0-9])")
    private val radianWord = Regex("(?i)(^|[^a-z0-9])(?:rad|rads|radians?)($|[^a-z0-9])")
    private val degreeWord = Regex("(?i)(^|[^a-z0-9])(?:deg|degs|degrees?)($|[^a-z0-9])")
    private val cartesianAxes = setOf(
        "x", "y", "z", "pose_x", "pose_y", "pose_z", "position_x", "position_y", "position_z"
    )

    /** Converts [value] between units of the same [UnitCategory]. */
    fun convert(value: Double, from: RobotUnit, to: RobotUnit): Double {
        if (from.category != to.category) throw IllegalArgumentException("Cannot convert from ${from.category} to ${to.category}")
        if (from == to) return value
        if (value.isNaN()) return Double.NaN
        if (value.isInfinite()) return value

        if (from.category == UnitCategory.TEMPERATURE) {
            val celsius = when (from) {
                RobotUnit.CELSIUS -> value
                RobotUnit.FAHRENHEIT -> (value - 32.0) * (5.0 / 9.0)
                RobotUnit.KELVIN -> value - 273.15
                else -> value
            }
            return when (to) {
                RobotUnit.CELSIUS -> celsius
                RobotUnit.FAHRENHEIT -> celsius * (9.0 / 5.0) + 32.0
                RobotUnit.KELVIN -> celsius + 273.15
                else -> celsius
            }
        }
        return value * (from.factorToBase / to.factorToBase)
    }

    /** Infers a display unit from a telemetry key, or returns `null` when the key is ambiguous. */
    fun detectUnitFromKey(key: String): RobotUnit? {
        val lowerKey = key.lowercase()
        val leaf = lowerKey.substringAfterLast('/')
        val spaced = camelWordBoundary.replace(key, "$1 $2")
        val isAngular = rotationWord.containsMatchIn(spaced) ||
            radianWord.containsMatchIn(spaced) ||
            degreeWord.containsMatchIn(spaced) ||
            lowerKey.contains("angle") ||
            lowerKey.contains("omega")
        val isCartesianAxis = leaf in cartesianAxes
        val hasAmpereWord = lowerKey.contains("amp") &&
            ampereWord.containsMatchIn(spaced)
        val hasMsWord = millisecondWord.containsMatchIn(spaced)
        val hasSecWord = secondWord.containsMatchIn(spaced)

        return when {
            lowerKey.contains("millivolt") -> RobotUnit.MILLIVOLT
            lowerKey.contains("milliamp") -> RobotUnit.MILLIAMPERE
            lowerKey.contains("voltage") || lowerKey.contains("volt") -> RobotUnit.VOLT
            lowerKey.contains("current") || hasAmpereWord -> RobotUnit.AMPERE
            lowerKey.contains("fahrenheit") -> RobotUnit.FAHRENHEIT
            lowerKey.contains("kelvin") -> RobotUnit.KELVIN
            lowerKey.contains("temp") || lowerKey.contains("celsius") -> RobotUnit.CELSIUS
            lowerKey.contains("rpm") -> RobotUnit.RPM
            (lowerKey.contains("velocity") || lowerKey.contains("vel")) && isAngular -> RobotUnit.RAD_PER_SEC
            lowerKey.contains("velocity") || lowerKey.contains("vel") -> RobotUnit.METER_PER_SEC
            degreeWord.containsMatchIn(spaced) -> RobotUnit.DEGREE
            radianWord.containsMatchIn(spaced) -> RobotUnit.RADIAN
            rotationWord.containsMatchIn(spaced) -> RobotUnit.ROTATION
            lowerKey.contains("angle") || lowerKey.contains("heading") || lowerKey.contains("yaw") || lowerKey.contains("pitch") || lowerKey.contains("roll") -> RobotUnit.RADIAN
            hasMsWord -> RobotUnit.MILLISECOND
            lowerKey.contains("time") || hasSecWord -> RobotUnit.SECOND
            lowerKey.contains("distance") || lowerKey.contains("position") || lowerKey.contains("pose") || isCartesianAxis -> RobotUnit.METER
            else -> null
        }
    }
}
