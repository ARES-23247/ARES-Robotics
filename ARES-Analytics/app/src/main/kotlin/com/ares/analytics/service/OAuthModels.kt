package com.ares.analytics.service

import com.ares.analytics.shared.AppJson
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Generates a cryptographically secure 256-bit PKCE verifier. */
fun generateCodeVerifier(): String {
    val codeVerifier = ByteArray(32)
    SecureRandom().nextBytes(codeVerifier)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(codeVerifier)
}

fun generateCodeChallenge(codeVerifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

sealed class AuthState {
    object Unauthenticated : AuthState()
    object Authenticating : AuthState()
    data class Authenticated(
        val idToken: String,
        val uid: String,
        val email: String,
        val displayName: String,
    ) : AuthState()
    data class Error(val message: String) : AuthState()
}

sealed class DrivePickerState {
    object Idle : DrivePickerState()
    object Picking : DrivePickerState()
    data class Selected(val folderId: String) : DrivePickerState()
    data class Error(val message: String) : DrivePickerState()
}

/** Persisted Google identity and OAuth tokens protected by [OAuthTokenStore]. */
@Serializable
data class OAuthSavedAuth(
    val googleClientId: String? = null,
    val googleAccessToken: String,
    val googleRefreshToken: String?,
    val googleTokenExpiresAt: Long?,
    val googleIdToken: String? = null,
    val uid: String,
    val email: String,
    val displayName: String,
)

@Serializable
internal data class GoogleIdPayload(
    val sub: String = "",
    val email: String? = null,
    val name: String? = null,
)

@Serializable
private data class GoogleOAuthErrorResponse(
    val error: String = "",
    val error_description: String? = null,
)

internal const val GOOGLE_CALLBACK_PORT = 5805
internal const val GOOGLE_DESKTOP_REDIRECT_URI = "http://127.0.0.1:$GOOGLE_CALLBACK_PORT/callback"

internal fun decodeIdToken(idToken: String): GoogleIdPayload = try {
    val payload = idToken.split(".").getOrNull(1) ?: return GoogleIdPayload()
    val json = String(Base64.getUrlDecoder().decode(payload))
    AppJson.decodeFromString<GoogleIdPayload>(json)
} catch (_: Exception) {
    GoogleIdPayload()
}

internal fun parseGoogleOAuthError(body: String): String = try {
    AppJson.decodeFromString<GoogleOAuthErrorResponse>(body).error
} catch (_: Exception) {
    when {
        body.contains("deleted_client", ignoreCase = true) -> "deleted_client"
        body.contains("invalid_grant", ignoreCase = true) -> "invalid_grant"
        body.contains("access_denied", ignoreCase = true) -> "access_denied"
        else -> "unknown"
    }
}

internal fun googleOAuthRecoveryMessage(
    responseBody: String,
    source: GoogleOAuthClientSource,
): String = when (parseGoogleOAuthError(responseBody)) {
    "deleted_client" -> if (source == GoogleOAuthClientSource.CUSTOM) {
        "This custom Google OAuth client was deleted. Disable the custom client to use ARES-managed sign-in, or create a replacement Desktop client in Google Cloud."
    } else {
        "The ARES Google sign-in client is unavailable. Update ARES Robotics Studio or contact an ARES administrator."
    }
    "invalid_grant" ->
        "Google revoked or expired this sign-in. The unusable session was cleared; choose Sign in with Google to reconnect."
    "access_denied" ->
        "Google sign-in was cancelled or access was denied. No cloud data was changed; you can keep using ARES offline or try again."
    "invalid_client" -> if (source == GoogleOAuthClientSource.CUSTOM) {
        "Google rejected this custom OAuth client. Confirm that it is an active Desktop client, then reconnect Google."
    } else {
        "Google rejected the ARES sign-in client. Update ARES Robotics Studio or contact an ARES administrator."
    }
    "unauthorized_client" ->
        "This Google OAuth client is not permitted to use the desktop authorization flow. An administrator must replace it with an active Desktop client."
    "redirect_uri_mismatch" ->
        "Google rejected the desktop callback address. Update ARES Robotics Studio, then try Google sign-in again."
    "invalid_request" -> if (
        responseBody.contains("client_secret", ignoreCase = true) ||
        responseBody.contains("client secret", ignoreCase = true)
    ) {
        if (source == GoogleOAuthClientSource.CUSTOM) {
            "Your organization's Google token service is not configured for this OAuth client. Ask its administrator to update the protected client credential, then reconnect Google."
        } else {
            "The ARES Google token service needs administrator attention. Keep using ARES offline and try sign-in again after the service is updated."
        }
    } else {
        "Google rejected the desktop sign-in request. Update ARES Robotics Studio and try again; if it continues, contact an ARES administrator."
    }
    else ->
        "Google could not complete sign-in. Check your internet connection, then try again. If this continues, disconnect Google and reconnect."
}
