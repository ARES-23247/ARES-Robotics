package com.areslib.state

/** Canonical decode-and-validation boundary shared by FTC, FRC, Studio, and simulators. */
object ValidatedRobotFieldLoader {
    /**
     * Decodes one current field document and rejects the first contract violation.
     * Platform adapters may derive SDK-specific tag layouts only after this succeeds.
     */
    fun load(
        bytes: ByteArray,
        requiredFieldType: FieldType,
        requireAprilTags: Boolean,
    ): RobotFieldConfig {
        val config = RobotFieldDocument.decode(bytes.decodeToString())
        val issues = RobotFieldValidator.validate(config, requiredFieldType, requireAprilTags)
        require(issues.isEmpty()) { issues.first().message }
        return config
    }
}
