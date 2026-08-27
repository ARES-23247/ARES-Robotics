package com.ares.analytics.shared

import com.ares.analytics.shared.models.MAX_SUPPORTED_TIMESTAMP_MS
import com.ares.analytics.shared.models.TelemetryFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelemetryFrameTimestampTest {
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
