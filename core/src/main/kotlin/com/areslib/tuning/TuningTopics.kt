package com.areslib.tuning

import com.areslib.telemetry.TelemetryTopicNormalizer

/** Strict schema-v2 NetworkTables contract mirroring [com.areslib.state.TuningState]. */
object TuningTopics {
    const val SCHEMA_VERSION = 2
    const val ROOT = "Tuning"
    const val SCHEMA_VERSION_TOPIC = "$ROOT/SchemaVersion"

    const val DRIVE_TRACK_WIDTH = "$ROOT/drive/trackWidthMeters"
    const val DRIVE_WHEEL_BASE = "$ROOT/drive/wheelBaseMeters"
    const val DRIVE_TRANSLATION_KP = "$ROOT/drive/pathTranslationGains/kP"
    const val DRIVE_TRANSLATION_KI = "$ROOT/drive/pathTranslationGains/kI"
    const val DRIVE_TRANSLATION_KD = "$ROOT/drive/pathTranslationGains/kD"
    const val DRIVE_TRANSLATION_KF = "$ROOT/drive/pathTranslationGains/kF"
    const val DRIVE_ROTATION_KP = "$ROOT/drive/pathRotationGains/kP"
    const val DRIVE_ROTATION_KI = "$ROOT/drive/pathRotationGains/kI"
    const val DRIVE_ROTATION_KD = "$ROOT/drive/pathRotationGains/kD"
    const val DRIVE_ROTATION_KF = "$ROOT/drive/pathRotationGains/kF"
    const val DRIVE_FEEDFORWARD_KS = "$ROOT/drive/driveFeedforward/kS"
    const val DRIVE_FEEDFORWARD_KV = "$ROOT/drive/driveFeedforward/kV"
    const val DRIVE_FEEDFORWARD_KA = "$ROOT/drive/driveFeedforward/kA"
    const val DRIVE_ANGULAR_FEEDFORWARD_KS = "$ROOT/drive/angularFeedforward/kS"
    const val DRIVE_ANGULAR_FEEDFORWARD_KV = "$ROOT/drive/angularFeedforward/kV"
    const val DRIVE_ANGULAR_FEEDFORWARD_KA = "$ROOT/drive/angularFeedforward/kA"

    const val FTC_TICKS_PER_METER = "$ROOT/drive/ftc/ticksPerMeter"
    const val PINPOINT_X_OFFSET = "$ROOT/localization/ftcPinpoint/xOffsetMm"
    const val PINPOINT_Y_OFFSET = "$ROOT/localization/ftcPinpoint/yOffsetMm"
    const val PINPOINT_ENCODER_RESOLUTION = "$ROOT/localization/ftcPinpoint/encoderResolution"
    const val VISION_STD_DEVS_X = "$ROOT/vision/stdDevsX"
    const val VISION_STD_DEVS_Y = "$ROOT/vision/stdDevsY"
    const val VISION_STD_DEVS_HEADING = "$ROOT/vision/stdDevsHeading"
    const val DRIVER_DEADBAND_EXPONENT = "$ROOT/driver/deadbandExponent"
    const val DRIVER_SLEW_RATE_LIMIT = "$ROOT/driver/slewRateLimit"

    const val FLYWHEEL_FEEDFORWARD_KS = "$ROOT/subsystem/flywheel/feedforward/kS"
    const val FLYWHEEL_FEEDFORWARD_KV = "$ROOT/subsystem/flywheel/feedforward/kV"
    const val FLYWHEEL_FEEDFORWARD_KA = "$ROOT/subsystem/flywheel/feedforward/kA"
    const val FLYWHEEL_VELOCITY_KP = "$ROOT/subsystem/flywheel/velocityGains/kP"
    const val FLYWHEEL_VELOCITY_KI = "$ROOT/subsystem/flywheel/velocityGains/kI"
    const val FLYWHEEL_VELOCITY_KD = "$ROOT/subsystem/flywheel/velocityGains/kD"
    const val FLYWHEEL_VELOCITY_KF = "$ROOT/subsystem/flywheel/velocityGains/kF"

    /** Normalizes transport-only leading slashes; schema-v1 aliases are intentionally unsupported. */
    fun canonicalize(topic: String): String {
        val normalized = TelemetryTopicNormalizer.normalizeTopic(topic)
        return if (normalized.startsWith("$ROOT/")) normalized else "$ROOT/$normalized"
    }

    fun statePath(topic: String): String = canonicalize(topic).removePrefix("$ROOT/")
}
