package com.areslib.telemetry.schema

/** One robot result bound to its exact request nonce, independent of scalar topic delivery order. */
public data class TuningAcknowledgement(public val nonce: Long, public val result: String)

/**
 * Single string topic payload: `1|nonce|RESULT_CODE`. Nonces use the exact NT4 integer range.
 * Results are bounded uppercase identifiers; unknown future result codes remain explicit outcomes.
 * An empty initial topic or malformed value is not an acknowledgement. Encoding occurs only for
 * explicit tuning requests, never for an idle robot polling tick.
 */
public object TuningAcknowledgementCodec {
    private val resultPattern: Regex = Regex("[A-Z][A-Z0-9_]{0,63}")
    private const val MAX_PAYLOAD_LENGTH: Int = 83

    public fun encode(value: TuningAcknowledgement): String {
        require(value.nonce in 0..DesktopDriveProtocol.MAX_SAFE_INTEGER_LONG) { "Invalid tuning acknowledgement nonce" }
        require(resultPattern.matches(value.result)) { "Invalid tuning result code" }
        return "1|${value.nonce}|${value.result}"
    }

    /** Rejects ambiguous numeric aliases, extra fields, invalid versions and oversized input. */
    public fun decode(payload: String?): TuningAcknowledgement? {
        if (payload == null || payload.length > MAX_PAYLOAD_LENGTH || !payload.startsWith("1|")) return null
        val separator = payload.indexOf('|', 2)
        if (separator < 0) return null
        val nonceText = payload.substring(2, separator)
        val nonce = nonceText.toLongOrNull() ?: return null
        if (nonce !in 0..DesktopDriveProtocol.MAX_SAFE_INTEGER_LONG || nonce.toString() != nonceText) return null
        val result = payload.substring(separator + 1)
        if (!resultPattern.matches(result)) return null
        return TuningAcknowledgement(nonce, result)
    }
}
