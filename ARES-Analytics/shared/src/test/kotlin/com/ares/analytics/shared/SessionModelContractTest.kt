// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.shared

import com.ares.analytics.shared.models.AnalysisDiagnostic
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.allowsAutomaticExternalUpdates
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionModelContractTest {
    @Test
    fun `diagnostic keys cannot become blank after storage normalization`() {
        for (key in listOf("", " ", "/", "//", " /// ")) {
            assertFailsWith<IllegalArgumentException>(key) {
                AnalysisDiagnostic("session", key, 1.0)
            }
            val encodedKey = AppJson.encodeToString(key)
            assertFailsWith<IllegalArgumentException>(key) {
                AppJson.decodeFromString<AnalysisDiagnostic>(
                    """{"sessionId":"session","key":$encodedKey,"value":1.0}""",
                )
            }
        }
    }

    @Test
    fun `diagnostics retain text and reject nonfinite numeric placeholders`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                AnalysisDiagnostic("session", "Diagnostics/Status", value, "ready")
            }
        }
        assertFailsWith<IllegalArgumentException> { AnalysisDiagnostic(" ", "Diagnostics/Status", 0.0) }
        val diagnostic = AnalysisDiagnostic("session", " //Diagnostics/温度 ", Double.MAX_VALUE, "ready")
        assertEquals("Diagnostics/温度", TelemetryMetricCatalog.normalizeTopic(diagnostic.key))
        assertEquals(diagnostic, AppJson.decodeFromString<AnalysisDiagnostic>(AppJson.encodeToString(diagnostic)))
    }

    @Test
    fun `session and summary suppress automatic routing for simulation tags`() {
        for ((tags, allowed) in listOf(
            emptyList<String>() to true,
            listOf("practice") to true,
            listOf("simulation") to false,
            listOf("practice", "SiMuLaTiOn") to false,
        )) {
            val session = Session("session", "team", "season", "robot", 1000L, tags = tags)
            val summary = SessionSummary("session", "team", "season", "robot", 1000L, tags = tags)
            assertEquals(allowed, AppJson.decodeFromString<Session>(AppJson.encodeToString(session)).allowsAutomaticExternalUpdates())
            assertEquals(allowed, AppJson.decodeFromString<SessionSummary>(AppJson.encodeToString(summary)).allowsAutomaticExternalUpdates())
        }
    }
}
