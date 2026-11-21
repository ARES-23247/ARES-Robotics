package com.ares.analytics.gateway

import com.ares.analytics.gateway.auth.resolveGoogleOidcClientId
import com.ares.analytics.gateway.routes.parseForensicsResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FreshAuditGatewayRegressionTest {

    @Test
    fun `resolveGoogleOidcClientId falls back to ARES_GOOGLE_OAUTH_CLIENT_ID`() {
        val primary = resolveGoogleOidcClientId { key ->
            when (key) {
                "GOOGLE_OIDC_CLIENT_ID" -> "primary-client-id"
                "ARES_GOOGLE_OAUTH_CLIENT_ID" -> "fallback-client-id"
                else -> null
            }
        }
        assertEquals("primary-client-id", primary)

        val fallback = resolveGoogleOidcClientId { key ->
            when (key) {
                "GOOGLE_OIDC_CLIENT_ID" -> "   "
                "ARES_GOOGLE_OAUTH_CLIENT_ID" -> "fallback-client-id"
                else -> null
            }
        }
        assertEquals("fallback-client-id", fallback)

        assertNull(resolveGoogleOidcClientId { null })
    }

    @Test
    fun `parseForensicsResponse ignores unknown extra fields from model output`() {
        val raw = """
            {
              "probableRootCause": "Brownout on CAN bus",
              "confidenceScore": 0.91,
              "cascadingNodesAffected": ["drive_fl"],
              "hardwareFaultLocus": {
                "failedNodeId": "pdh_0",
                "extraNestedField": true
              },
              "recommendedActions": ["Inspect Anderson connector"],
              "unexpectedModelMetadata": "should-not-fail"
            }
        """.trimIndent()
        val parsed = parseForensicsResponse(raw)
        assertEquals("Brownout on CAN bus", parsed.probableRootCause)
        assertEquals(0.91, parsed.confidenceScore)
        assertEquals("pdh_0", parsed.hardwareFaultLocus?.failedNodeId)
    }
}
