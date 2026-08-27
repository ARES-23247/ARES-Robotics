package com.ares.analytics.service

import com.ares.analytics.BuildConfig
import com.ares.analytics.shared.WorkspaceConfig

private const val GOOGLE_DESKTOP_CLIENT_SUFFIX = ".apps.googleusercontent.com"

enum class GoogleOAuthClientSource {
    ARES_MANAGED,
    CUSTOM,
}

data class GoogleOAuthClientCredentials(
    val clientId: String,
    val source: GoogleOAuthClientSource,
    val tokenBrokerUrl: String,
)

sealed interface GoogleOAuthClientResolution {
    data class Available(val credentials: GoogleOAuthClientCredentials) : GoogleOAuthClientResolution
    data class Unavailable(val message: String) : GoogleOAuthClientResolution
}

internal fun isValidGoogleDesktopClientId(value: String?): Boolean {
    val normalized = value?.trim().orEmpty()
    return normalized.length in 30..256 &&
        normalized.endsWith(GOOGLE_DESKTOP_CLIENT_SUFFIX) &&
        normalized.none(Char::isWhitespace)
}

internal fun isValidGoogleOAuthBrokerUrl(value: String?): Boolean {
    val normalized = value?.trim()?.trimEnd('/').orEmpty()
    if (!normalized.startsWith("https://") || normalized.length !in 12..512) return false
    return runCatching {
        val uri = java.net.URI(normalized)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
    }.getOrDefault(false)
}

/**
 * Resolves the OAuth application identity without placing a client secret in the desktop app.
 *
 * Existing workspace client IDs are intentionally ignored unless the administrator explicitly
 * enables the custom-client switch. This safely migrates installations that still contain the
 * deleted legacy client while preserving bring-your-own Google Cloud projects. The selected
 * HTTPS broker owns any confidential Google credential required for token exchange.
 */
class GoogleOAuthClientResolver(
    private val managedClientId: String = BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
    private val managedBrokerUrl: String = BuildConfig.GOOGLE_OAUTH_BROKER_URL,
) {
    val managedClientAvailable: Boolean
        get() = isValidGoogleDesktopClientId(managedClientId) && isValidGoogleOAuthBrokerUrl(managedBrokerUrl)

    fun resolve(config: WorkspaceConfig?): GoogleOAuthClientResolution {
        if (config?.googleOAuthUseCustomClient == true) {
            val customId = config.googleClientId?.trim()
            val customBroker = config.googleOAuthBrokerUrl?.trim()?.trimEnd('/')
            return if (isValidGoogleDesktopClientId(customId) && isValidGoogleOAuthBrokerUrl(customBroker)) {
                GoogleOAuthClientResolution.Available(
                    GoogleOAuthClientCredentials(customId!!, GoogleOAuthClientSource.CUSTOM, customBroker!!),
                )
            } else {
                GoogleOAuthClientResolution.Unavailable(
                    "The custom Google OAuth setup is incomplete. Enter a Desktop client ID and its administrator-managed HTTPS token broker, or disable the custom client to use ARES-managed sign-in.",
                )
            }
        }

        val bundled = managedClientId.trim()
        val broker = managedBrokerUrl.trim().trimEnd('/')
        return if (isValidGoogleDesktopClientId(bundled) && isValidGoogleOAuthBrokerUrl(broker)) {
            GoogleOAuthClientResolution.Available(
                GoogleOAuthClientCredentials(bundled, GoogleOAuthClientSource.ARES_MANAGED, broker),
            )
        } else {
            GoogleOAuthClientResolution.Unavailable(
                "Google sign-in is unavailable in this build. Install an official ARES Robotics Studio release or ask an administrator to configure a custom Desktop OAuth client.",
            )
        }
    }
}
