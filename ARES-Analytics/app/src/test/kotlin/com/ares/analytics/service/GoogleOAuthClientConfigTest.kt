package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoogleOAuthClientConfigTest {
    private val managed = "123456789012-managed.apps.googleusercontent.com"
    private val managedBroker = "https://oauth.aresfirst.org"
    private val custom = "987654321098-custom.apps.googleusercontent.com"
    private val customBroker = "https://oauth.school.example"

    @Test
    fun `normal workspaces use managed application identity and ignore stale legacy values`() {
        val resolution = GoogleOAuthClientResolver(managed, managedBroker).resolve(
            workspace(googleClientId = "deleted.apps.googleusercontent.com"),
        )

        val available = assertIs<GoogleOAuthClientResolution.Available>(resolution)
        assertEquals(managed, available.credentials.clientId)
        assertEquals(managedBroker, available.credentials.tokenBrokerUrl)
        assertEquals(GoogleOAuthClientSource.ARES_MANAGED, available.credentials.source)
    }

    @Test
    fun `administrator opt in uses a valid custom desktop client without a secret`() {
        val resolution = GoogleOAuthClientResolver(managed, managedBroker).resolve(
            workspace(
                googleClientId = custom,
                googleOAuthUseCustomClient = true,
                googleOAuthBrokerUrl = customBroker,
            ),
        )

        val available = assertIs<GoogleOAuthClientResolution.Available>(resolution)
        assertEquals(custom, available.credentials.clientId)
        assertEquals(GoogleOAuthClientSource.CUSTOM, available.credentials.source)
        assertEquals(customBroker, available.credentials.tokenBrokerUrl)
    }

    @Test
    fun `invalid custom client fails closed with managed recovery guidance`() {
        val resolution = GoogleOAuthClientResolver(managed, managedBroker).resolve(
            workspace(googleClientId = "not-a-google-client", googleOAuthUseCustomClient = true),
        )

        val unavailable = assertIs<GoogleOAuthClientResolution.Unavailable>(resolution)
        assertTrue(unavailable.message.contains("disable the custom client", ignoreCase = true))
    }

    @Test
    fun `custom client without a valid broker fails closed`() {
        val missing = GoogleOAuthClientResolver(managed, managedBroker).resolve(
            workspace(googleClientId = custom, googleOAuthUseCustomClient = true),
        )
        val insecure = GoogleOAuthClientResolver(managed, managedBroker).resolve(
            workspace(
                googleClientId = custom,
                googleOAuthUseCustomClient = true,
                googleOAuthBrokerUrl = "http://oauth.school.example",
            ),
        )

        assertIs<GoogleOAuthClientResolution.Unavailable>(missing)
        assertIs<GoogleOAuthClientResolution.Unavailable>(insecure)
    }

    @Test
    fun `deleted and revoked errors are actionable and do not expose client ids`() {
        val deleted = googleOAuthRecoveryMessage(
            """{"error":"deleted_client","error_description":"gone"}""",
            GoogleOAuthClientSource.CUSTOM,
        )
        val revoked = googleOAuthRecoveryMessage(
            """{"error":"invalid_grant"}""",
            GoogleOAuthClientSource.ARES_MANAGED,
        )

        assertTrue(deleted.contains("Disable the custom client"))
        assertTrue(revoked.contains("session was cleared"))
        assertTrue(!deleted.contains(custom))
    }

    @Test
    fun `confidential client failure explains token service recovery without exposing secrets`() {
        val managedFailure = googleOAuthRecoveryMessage(
            """{"error":"invalid_request","error_description":"client_secret is missing."}""",
            GoogleOAuthClientSource.ARES_MANAGED,
        )
        val customFailure = googleOAuthRecoveryMessage(
            """{"error":"invalid_request","error_description":"client_secret is missing."}""",
            GoogleOAuthClientSource.CUSTOM,
        )

        assertTrue(managedFailure.contains("token service"))
        assertTrue(customFailure.contains("token service"))
        assertTrue(customFailure.contains("administrator"))
        assertTrue(!managedFailure.contains("client_secret"))
        assertTrue(!customFailure.contains("client_secret"))
        assertTrue(!managedFailure.contains(managed))
        assertTrue(!customFailure.contains(custom))
    }

    @Test
    fun `desktop callback uses the numeric loopback address`() {
        assertEquals("http://127.0.0.1:5805/callback", GOOGLE_DESKTOP_REDIRECT_URI)
    }

    private fun workspace(
        googleClientId: String?,
        googleOAuthUseCustomClient: Boolean = false,
        googleOAuthBrokerUrl: String? = null,
    ) = WorkspaceConfig(
        id = "workspace",
        teamId = "23247",
        seasonId = "2026",
        robotId = "robot",
        projectPath = ".",
        league = League.FTC,
        googleClientId = googleClientId,
        googleOAuthUseCustomClient = googleOAuthUseCustomClient,
        googleOAuthBrokerUrl = googleOAuthBrokerUrl,
    )
}
