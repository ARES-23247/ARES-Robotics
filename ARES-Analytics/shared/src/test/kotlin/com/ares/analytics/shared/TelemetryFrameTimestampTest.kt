package com.ares.analytics.shared

import com.ares.analytics.shared.models.*
import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelemetryFrameTimestampTest {
    @Test
    fun `legacy JSON derives exact microseconds and default sample order`() {
        val frame = AppJson.decodeFromString<TelemetryFrame>(
            """{"timestampMs":1234,"sessionId":"session","key":"Status","value":0.0,"stringValue":"ready"}""")
        assertEquals(1_234_000L, frame.timestampUs)
        assertEquals(0L, frame.sampleOrder)
        assertEquals("ready", frame.stringValue)
    }

    @Test
    fun `JSON round trip preserves submillisecond order and text`() {
        val frame = TelemetryFrame(1234L, "session", "Status", 0.0, "ready",
            timestampUs = 1_234_567L, sampleOrder = 17L)
        assertEquals(frame, AppJson.decodeFromString<TelemetryFrame>(AppJson.encodeToString(frame)))
    }

    @Test
    fun `JSON decoding cannot bypass timestamp and sample order invariants`() {
        for (fields in listOf(
            "\"timestampMs\":-1",
            "\"timestampMs\":${MAX_SUPPORTED_TIMESTAMP_MS + 1L}",
            "\"timestampMs\":1234,\"timestampUs\":1235000",
            "\"timestampMs\":1234,\"timestampUs\":-1",
            "\"timestampMs\":1234,\"sampleOrder\":-1",
        )) {
            assertFailsWith<IllegalArgumentException> {
                AppJson.decodeFromString<TelemetryFrame>(
                    """{$fields,"sessionId":"session","key":"Status","value":0.0}""")
            }
        }
    }

    @Test
    fun `default microsecond timestamp is exact and bounded`() {
        val frame = TelemetryFrame(MAX_SUPPORTED_TIMESTAMP_MS, "session", "key", 1.0)
        assertEquals(MAX_SUPPORTED_TIMESTAMP_MS * 1_000L, frame.timestampUs)
        assertFailsWith<IllegalArgumentException> {
            TelemetryFrame(-1L, "session", "key", 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            TelemetryFrame(Long.MAX_VALUE, "session", "key", 1.0)
        }
    }

    @Test
    fun `explicit microseconds must describe the same millisecond`() {
        TelemetryFrame(1_000L, "session", "key", 1.0, timestampUs = 1_000_999L)
        assertFailsWith<IllegalArgumentException> {
            TelemetryFrame(1_000L, "session", "key", 1.0, timestampUs = 999_999L)
        }
        assertFailsWith<IllegalArgumentException> {
            TelemetryFrame(1_000L, "session", "key", 1.0, timestampUs = Long.MIN_VALUE)
        }
    }
}
