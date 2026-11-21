package com.ares.analytics.shared

import kotlinx.serialization.Serializable

/**
 * One-time authorization-code exchange sent from the desktop app to the ARES gateway.
 *
 * The gateway adds the Google client secret from protected cloud configuration. The desktop
 * installer never contains that secret. [code] and [codeVerifier] are short-lived credentials;
 * callers and servers must never log this request body.
 */
@Serializable
data class GoogleAuthorizationCodeExchangeRequest(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
) {
    override fun toString(): String =
        "GoogleAuthorizationCodeExchangeRequest(code=<redacted>, codeVerifier=<redacted>, redirectUri=$redirectUri)"
}

/**
 * Refresh exchange sent over HTTPS when a Google access token expires.
 *
 * The refresh token remains desktop-owned. The gateway uses it only for the current exchange
 * and must not persist or log it.
 */
@Serializable
data class GoogleRefreshTokenExchangeRequest(
    val refreshToken: String,
) {
    override fun toString(): String = "GoogleRefreshTokenExchangeRequest(refreshToken=<redacted>)"
}

/** Token response returned by the ARES gateway after Google accepts an exchange. */
@Serializable
data class GoogleOAuthBrokerTokenResponse(
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val tokenType: String = "Bearer",
) {
    override fun toString(): String =
        "GoogleOAuthBrokerTokenResponse(accessToken=<redacted>, expiresIn=$expiresIn, refreshToken=${if (refreshToken != null) "<redacted>" else "null"}, idToken=${if (idToken != null) "<redacted>" else "null"}, tokenType=$tokenType)"
}

/** Safe, credential-free failure returned by the gateway. */
@Serializable
data class GoogleOAuthBrokerErrorResponse(
    val error: String,
    val errorDescription: String? = null,
)
