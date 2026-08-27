package com.ares.analytics.gateway.routes

import com.ares.analytics.shared.GoogleAuthorizationCodeExchangeRequest
import com.ares.analytics.shared.GoogleOAuthBrokerErrorResponse
import com.ares.analytics.shared.GoogleOAuthBrokerTokenResponse
import com.ares.analytics.shared.GoogleRefreshTokenExchangeRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException

internal const val ARES_GOOGLE_REDIRECT_URI = "http://127.0.0.1:5805/callback"
private const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
private val PKCE_VERIFIER = Regex("^[A-Za-z0-9._~-]{43,128}$")
private val GOOGLE_WIRE_JSON = Json { ignoreUnknownKeys = true }
private val SAFE_GOOGLE_ERRORS = setOf(
    "invalid_client",
    "deleted_client",
    "invalid_grant",
    "invalid_request",
    "unauthorized_client",
    "unsupported_grant_type",
)

@Serializable
private data class GoogleTokenWireResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("token_type") val tokenType: String = "Bearer",
)

@Serializable
private data class GoogleErrorWireResponse(
    val error: String = "unknown",
    @SerialName("error_description") val errorDescription: String? = null,
)

internal sealed interface GoogleBrokerResult {
    data class Success(val tokens: GoogleOAuthBrokerTokenResponse) : GoogleBrokerResult
    data class Failure(
        val status: HttpStatusCode,
        val error: GoogleOAuthBrokerErrorResponse,
    ) : GoogleBrokerResult
}

/**
 * Server-side Google token exchange for the ARES-managed OAuth client.
 *
 * Google currently requires its generated Desktop client secret even when the desktop uses PKCE.
 * The gateway keeps that secret in protected Cloud Run configuration. It never returns, persists,
 * or logs the secret, authorization codes, PKCE verifiers, refresh tokens, or Google token bodies.
 */
internal class GoogleOAuthBroker(
    private val clientId: String,
    private val clientSecret: String,
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    },
) {
    val configured: Boolean
        get() = clientId.length in 30..256 &&
            clientId.endsWith(".apps.googleusercontent.com") &&
            clientId.none(Char::isWhitespace) &&
            clientSecret.length in 8..512 &&
            clientSecret.none(Char::isWhitespace)

    suspend fun exchangeAuthorizationCode(
        request: GoogleAuthorizationCodeExchangeRequest,
    ): GoogleBrokerResult {
        if (!configured) return unavailable()
        if (
            request.code.isBlank() || request.code.length > 4_096 ||
            !PKCE_VERIFIER.matches(request.codeVerifier) ||
            request.redirectUri != ARES_GOOGLE_REDIRECT_URI
        ) {
            return invalidRequest()
        }
        return exchange(
            Parameters.build {
                append("code", request.code)
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("redirect_uri", request.redirectUri)
                append("grant_type", "authorization_code")
                append("code_verifier", request.codeVerifier)
            },
        )
    }

    suspend fun refresh(request: GoogleRefreshTokenExchangeRequest): GoogleBrokerResult {
        if (!configured) return unavailable()
        if (request.refreshToken.isBlank() || request.refreshToken.length > 4_096) {
            return invalidRequest()
        }
        return exchange(
            Parameters.build {
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("refresh_token", request.refreshToken)
                append("grant_type", "refresh_token")
            },
        )
    }

    private suspend fun exchange(parameters: Parameters): GoogleBrokerResult {
        val response = httpClient.post(GOOGLE_TOKEN_ENDPOINT) {
            setBody(FormDataContent(parameters))
        }
        if (response.status == HttpStatusCode.OK) {
            val wire = response.body<GoogleTokenWireResponse>()
            return GoogleBrokerResult.Success(
                GoogleOAuthBrokerTokenResponse(
                    accessToken = wire.accessToken,
                    expiresIn = wire.expiresIn,
                    refreshToken = wire.refreshToken,
                    idToken = wire.idToken,
                    tokenType = wire.tokenType,
                ),
            )
        }

        val googleError = runCatching {
            GOOGLE_WIRE_JSON.decodeFromString<GoogleErrorWireResponse>(response.bodyAsText())
        }.getOrElse { GoogleErrorWireResponse() }
        val safeCode = googleError.error.takeIf(SAFE_GOOGLE_ERRORS::contains) ?: "google_exchange_failed"
        return GoogleBrokerResult.Failure(
            status = if (response.status.value in 400..499) response.status else HttpStatusCode.BadGateway,
            error = GoogleOAuthBrokerErrorResponse(
                error = safeCode,
                errorDescription = safeGoogleErrorDescription(safeCode),
            ),
        )
    }

    private fun unavailable() = GoogleBrokerResult.Failure(
        HttpStatusCode.ServiceUnavailable,
        GoogleOAuthBrokerErrorResponse(
            error = "broker_unavailable",
            errorDescription = "ARES Google sign-in is temporarily unavailable.",
        ),
    )

    private fun invalidRequest() = invalidDesktopRequest()

    private fun safeGoogleErrorDescription(error: String): String = when (error) {
        "invalid_grant" -> "Google rejected or expired this authorization. Sign in again."
        "invalid_client", "deleted_client", "unauthorized_client" ->
            "ARES Google sign-in is temporarily unavailable. Contact an ARES administrator."
        "invalid_request", "unsupported_grant_type" ->
            "Google rejected the authorization request. Sign in again."
        else -> "Google could not complete the authorization exchange. Try again."
    }
}

/** Registers the narrowly scoped, rate-limited desktop OAuth exchange endpoints. */
internal fun Route.googleOAuthBrokerRoutes(broker: GoogleOAuthBroker) {
    rateLimit(RateLimitName("oauth-exchange-global")) {
        rateLimit(RateLimitName("oauth-exchange")) {
            post("/api/oauth/google/token") {
                val request = call.receiveBrokerRequest<GoogleAuthorizationCodeExchangeRequest>()
                    ?: return@post respond(call, invalidDesktopRequest())
                respond(call, broker.exchangeAuthorizationCode(request))
            }
            post("/api/oauth/google/refresh") {
                val request = call.receiveBrokerRequest<GoogleRefreshTokenExchangeRequest>()
                    ?: return@post respond(call, invalidDesktopRequest())
                respond(call, broker.refresh(request))
            }
        }
    }
}

private suspend fun respond(
    call: ApplicationCall,
    result: GoogleBrokerResult,
) {
    when (result) {
        is GoogleBrokerResult.Success -> call.respond(HttpStatusCode.OK, result.tokens)
        is GoogleBrokerResult.Failure -> call.respond(result.status, result.error)
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveBrokerRequest(): T? =
    try {
        receive<T>()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: ContentTransformationException) {
        null
    } catch (_: BadRequestException) {
        null
    }

private fun invalidDesktopRequest() = GoogleBrokerResult.Failure(
    HttpStatusCode.BadRequest,
    GoogleOAuthBrokerErrorResponse(
        error = "invalid_request",
        errorDescription = "The desktop authorization request was invalid.",
    ),
)
