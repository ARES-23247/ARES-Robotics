package com.areslib.telemetry.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TuningAcknowledgementTest {
    @Test fun `exact nonce limits and result identifiers round trip without numeric aliasing`() {
        for (nonce in listOf(0L, 1L, 9_007_199_254_740_990L, DesktopDriveProtocol.MAX_SAFE_INTEGER_LONG)) {
            for (result in listOf("APPLIED", "SESSION_NOT_ARMED", "APPLY_CALLBACK_FAILED", "FUTURE_RESULT2")) {
                val value = TuningAcknowledgement(nonce, result)
                assertEquals(value, TuningAcknowledgementCodec.decode(TuningAcknowledgementCodec.encode(value)))
            }
        }
        assertEquals("1|1|APPLIED", TuningAcknowledgementCodec.encode(TuningAcknowledgement(1, "APPLIED")))
    }

    @Test fun `malformed or uninitialized acknowledgements never become a result`() {
        for (payload in listOf(null, "", "IDLE", "1", "1|1", "2|1|APPLIED", "1|-1|APPLIED", "1|+1|APPLIED",
            "1|01|APPLIED", "1|1.0|APPLIED", "1|1e1|APPLIED", "1|NaN|APPLIED", "1|9007199254740992|APPLIED",
            "1|9223372036854775808|APPLIED", "1|1|", "1|1|applied", "1|1|APPLIED\n", "1|1|APPLIED|REJECTED",
            "1|1|" + "A".repeat(65), "x".repeat(100_000))) {
            assertNull(TuningAcknowledgementCodec.decode(payload), payload?.take(90))
        }
    }

    @Test fun `encoder rejects invalid nonces and unsafe result fields`() {
        for (nonce in listOf(-1L, DesktopDriveProtocol.MAX_SAFE_INTEGER_LONG + 1, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { TuningAcknowledgementCodec.encode(TuningAcknowledgement(nonce, "APPLIED")) }
        }
        for (result in listOf("", "applied", "1APPLIED", "APPLIED|OTHER", "APPLIED\n", "A".repeat(65))) {
            assertFailsWith<IllegalArgumentException> { TuningAcknowledgementCodec.encode(TuningAcknowledgement(1, result)) }
        }
    }

    @Test fun `maximum valid payload remains accepted`() {
        val value = TuningAcknowledgement(DesktopDriveProtocol.MAX_SAFE_INTEGER_LONG, "A".repeat(64))
        val payload = TuningAcknowledgementCodec.encode(value)
        assertEquals(83, payload.length)
        assertEquals(value, TuningAcknowledgementCodec.decode(payload))
    }
}
