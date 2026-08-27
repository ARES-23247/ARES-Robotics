package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.AppJson
import kotlinx.serialization.Serializable
import java.security.MessageDigest

internal const val SIMULATOR_FIELD_APPLIED_RECEIPT_TOPIC = "ARES/Field/AppliedReceipt"
internal const val SIMULATOR_FIELD_APPLY_ERROR_TOPIC = "ARES/Field/ApplyError"

/** Exact canonical field identity that the editor expects the simulator to apply. */
data class ExpectedSimulatorField(
    val configId: String,
    val revision: Long,
    val sha256: String,
)

/** A simulator rejection together with the NT4 sample identity that produced it. */
data class SimulatorFieldApplyFailure(
    val eventId: String,
    val message: String,
)

/**
 * Atomic simulator acknowledgement for a canonical field replacement.
 *
 * [session] and [sequence] form an event identity. They let the editor distinguish a fresh apply
 * from a retained NT4 value after reconnecting, while [sha256] proves the exact canonical payload
 * was decoded and installed rather than merely queued by the desktop transport.
 */
@Serializable
data class SimulatorFieldApplyReceipt(
    val session: String,
    val sequence: Long,
    val configId: String,
    val revision: Long,
    val sha256: String,
    val obstacleCount: Int,
    val elementCount: Int,
    val aprilTagCount: Int,
) {
    val eventId: String get() = "$session:$sequence"

    fun matches(expected: ExpectedSimulatorField): Boolean =
        configId == expected.configId &&
            revision == expected.revision &&
            sha256.equals(expected.sha256, ignoreCase = true)
}

internal fun parseSimulatorFieldApplyReceipt(payload: String?): SimulatorFieldApplyReceipt? =
    payload?.takeIf(String::isNotBlank)?.let { encoded ->
        runCatching { AppJson.decodeFromString<SimulatorFieldApplyReceipt>(encoded) }.getOrNull()
    }

internal fun sha256Hex(payload: String): String = MessageDigest.getInstance("SHA-256")
    .digest(payload.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
