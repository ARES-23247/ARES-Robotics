package com.ares.analytics.service

import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.ForensicsRequest
import com.ares.analytics.shared.models.SessionSummary
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito.*
import kotlin.test.*

class PitCoachEvidenceTest {
    @Test
    fun `follow-up supplies measured volts and alert time while identifying absent metrics`() = runBlocking {
        val ai = mock(GenerativeAiService::class.java)
        var text = ""
        `when`(ai.requestText(anyString())).thenAnswer { invocation ->
            text = invocation.getArgument<String>(0)
            "response"
        }
        val service = AiDiagnosticsService(ai, mock(DatabaseService::class.java))
        val summary = SessionSummary("run", "team", "season", "bot", 1, minBatteryVoltage = 10.4, observedMetrics = setOf("battery"))
        service.requestChatCoach(ForensicsRequest("team", "run", listOf(AlertRecord("a", "run", "low", 105, peakValue = 10.4)), summary), "Minimum voltage?")
        assertContains(text, """"minimumBatteryVoltage":{"unit":"V","value":10.4}""")
        assertContains(text, """"meanVisionLatency":{"unit":"ms","value":null}""")
        assertContains(text, "triggerMs=105")
        assertContains(text, "peak=10.4")
    }
}
