package com.ares.analytics.shared

import com.ares.analytics.shared.mcp.McpBridgeDescriptor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreshAuditSharedRegressionTest {

    @Test
    fun `oauth and mcp wire models redact sensitive credentials in toString`() {
        val codeReq = GoogleAuthorizationCodeExchangeRequest(
            code = "secret-auth-code-999",
            codeVerifier = "secret-pkce-verifier-888",
            redirectUri = "http://127.0.0.1:4444/callback",
        )
        assertFalse(codeReq.toString().contains("secret-auth-code-999"))
        assertFalse(codeReq.toString().contains("secret-pkce-verifier-888"))
        assertTrue(codeReq.toString().contains("<redacted>"))

        val refreshReq = GoogleRefreshTokenExchangeRequest(refreshToken = "secret-refresh-777")
        assertFalse(refreshReq.toString().contains("secret-refresh-777"))
        assertTrue(refreshReq.toString().contains("<redacted>"))

        val tokenResp = GoogleOAuthBrokerTokenResponse(
            accessToken = "secret-access-666",
            expiresIn = 3600L,
            refreshToken = "secret-refresh-555",
            idToken = "secret-id-444",
        )
        val tokenStr = tokenResp.toString()
        assertFalse(tokenStr.contains("secret-access-666"))
        assertFalse(tokenStr.contains("secret-refresh-555"))
        assertFalse(tokenStr.contains("secret-id-444"))
        assertTrue(tokenStr.contains("<redacted>"))

        val descriptor = McpBridgeDescriptor(
            pid = 4242L,
            port = 51515,
            bearerToken = "deadbeefcafebabe0123456789abcdef",
            studioVersion = "7.0.81",
            activeProjectRoot = "/tmp/robot",
            startedAtEpochMillis = 123456L,
        )
        assertFalse(descriptor.toString().contains("deadbeefcafebabe0123456789abcdef"))
        assertTrue(descriptor.toString().contains("<redacted>"))
    }
}
