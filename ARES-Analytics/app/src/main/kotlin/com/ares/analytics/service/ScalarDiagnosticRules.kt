package com.ares.analytics.service

import com.ares.analytics.shared.models.ThresholdRule
import kotlin.math.floor

internal enum class ScalarDiagnosticKind { CAN_UTILIZATION, I2C_TIMEOUTS, LIMELIGHT_FPS }

/** Source units are fixed: CAN ratio, exactly represented timeout count, and nonnegative Hz. */
internal object ScalarDiagnosticRules {
    const val I2C_KEY = "Hardware/I2C/Timeouts"
    const val VISION_KEY = "Vision/Limelight/FPS"
    private const val CAN_PREFIX = "Diagnostics/CANBus/"
    private const val CAN_SUFFIX = "/Utilization"
    private const val MAX_EXACT_COUNT = 9_007_199_254_740_991.0

    fun kind(key: String): ScalarDiagnosticKind? = when {
        key == I2C_KEY -> ScalarDiagnosticKind.I2C_TIMEOUTS
        key == VISION_KEY -> ScalarDiagnosticKind.LIMELIGHT_FPS
        key == "Hardware/CAN/Utilization" || key == "CAN/Utilization" -> ScalarDiagnosticKind.CAN_UTILIZATION
        key.startsWith(CAN_PREFIX) && key.endsWith(CAN_SUFFIX) &&
            key.length > CAN_PREFIX.length + CAN_SUFFIX.length -> ScalarDiagnosticKind.CAN_UTILIZATION
        else -> null
    }

    fun accepts(kind: ScalarDiagnosticKind, value: Double): Boolean = value.isFinite() && when (kind) {
        ScalarDiagnosticKind.CAN_UTILIZATION -> value in 0.0..1.0
        ScalarDiagnosticKind.I2C_TIMEOUTS -> value in 0.0..MAX_EXACT_COUNT && floor(value) == value
        ScalarDiagnosticKind.LIMELIGHT_FPS -> value >= 0.0
    }

    /** Called only when no user rule is registered for this exact normalized source. */
    fun defaultRule(kind: ScalarDiagnosticKind, key: String): ThresholdRule = when (kind) {
        ScalarDiagnosticKind.CAN_UTILIZATION -> ThresholdRule(
            key, "CRITICAL: CAN Bus Utilization High (>85%)!", maxValue = 0.85, audibleAlert = true,
        )
        ScalarDiagnosticKind.I2C_TIMEOUTS -> ThresholdRule(
            key, "WARNING: FTC I2C / Lynx Bus Timeout!", maxValue = 0.5, audibleAlert = true,
        )
        ScalarDiagnosticKind.LIMELIGHT_FPS -> ThresholdRule(
            key, "WARNING: Limelight Camera Frame Rate Low (<5 FPS)!", minValue = 5.0, audibleAlert = false,
        )
    }
}
