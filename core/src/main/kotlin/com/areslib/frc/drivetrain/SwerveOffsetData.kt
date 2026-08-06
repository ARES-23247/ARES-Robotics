package com.areslib.frc.drivetrain

/**
 * Immutable data representation of Swerve Module CANcoder offset angles.
 *
 * All offset values are represented in **rotations** (where 1.0 rotation = 360 degrees).
 *
 * @param frontLeft Front-Left swerve module encoder offset in rotations.
 * @param frontRight Front-Right swerve module encoder offset in rotations.
 * @param backLeft Back-Left swerve module encoder offset in rotations.
 * @param backRight Back-Right swerve module encoder offset in rotations.
 */
data class SwerveOffsetData(
    val frontLeft: Double = 0.0,
    val frontRight: Double = 0.0,
    val backLeft: Double = 0.0,
    val backRight: Double = 0.0
) {

    /**
     * Serializes this offset data object into a clean, human-readable JSON string.
     */
    fun toJsonString(): String {
        return """
            {
              "frontLeft": ${String.format("%.7f", frontLeft)},
              "frontRight": ${String.format("%.7f", frontRight)},
              "backLeft": ${String.format("%.7f", backLeft)},
              "backRight": ${String.format("%.7f", backRight)}
            }
        """.trimIndent()
    }

    companion object {

        /**
         * Parses a JSON string containing swerve offset values.
         * Safe against formatting variations and missing keys (falls back to defaults).
         *
         * @param json Valid JSON formatted string.
         * @return Parsed [SwerveOffsetData] instance.
         */
        fun fromJsonString(json: String): SwerveOffsetData {
            var fl = 0.0
            var fr = 0.0
            var bl = 0.0
            var br = 0.0

            try {
                val lines = json.split("\n", ",")
                for (line in lines) {
                    val keyVal = line.split(":")
                    if (keyVal.size == 2) {
                        val key = keyVal[0].replace("\"", "").replace("{", "").trim()
                        val value = keyVal[1].replace("\"", "").replace("}", "").trim().toDoubleOrNull() ?: continue

                        when (key) {
                            "frontLeft" -> fl = value
                            "frontRight" -> fr = value
                            "backLeft" -> bl = value
                            "backRight" -> br = value
                        }
                    }
                }
            } catch (_: Exception) {
                // Return default on parse failure
            }

            return SwerveOffsetData(fl, fr, bl, br)
        }
    }
}
