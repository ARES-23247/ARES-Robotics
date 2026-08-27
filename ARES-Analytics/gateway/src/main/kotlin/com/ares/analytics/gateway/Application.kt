package com.ares.analytics.gateway

import com.ares.analytics.gateway.auth.googleOidc
import com.ares.analytics.gateway.auth.GooglePrincipal
import com.ares.analytics.gateway.routes.diagnosticsRoutes
import com.ares.analytics.gateway.routes.GoogleOAuthBroker
import com.ares.analytics.gateway.routes.googleOAuthBrokerRoutes
import com.ares.analytics.gateway.routes.sourceCodeRoutes
import com.ares.analytics.shared.ForensicsRequest
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

private val allowedCorsHosts: List<String> = System.getenv("CORS_ALLOWED_HOSTS")
    ?.split(',')
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    .orEmpty()

/**
 * Gateway entry point. This service authenticates callers for Gemini pit forensics and
 * performs narrowly scoped Google OAuth token exchanges for the desktop app. Storage (session
 * logs/summaries) and Google Drive access remain desktop-owned; this gateway does not persist
 * OAuth grants or touch Firebase, Firestore, GCS, or users' Drive files.
 */
fun main() {
    // Force gRPC and Ktor onto the JDK JSSE provider instead of netty-tcnative OpenSSL,
    // which SIGSEGVs inside Google Cloud Run.
    System.setProperty("io.netty.handler.ssl.openssl.useOpenssl", "false")
    System.setProperty("io.grpc.netty.shaded.io.netty.handler.ssl.openssl.useOpenssl", "false")
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val googleOAuthBroker = GoogleOAuthBroker(
        clientId = System.getenv("ARES_GOOGLE_OAUTH_CLIENT_ID").orEmpty().trim(),
        clientSecret = System.getenv("ARES_GOOGLE_OAUTH_CLIENT_SECRET").orEmpty().trim(),
    )

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = false
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }

        install(CORS) {
            // This gateway is consumed by the Compose desktop app, which does not
            // need browser CORS. Browser access is opt-in through a deployment
            // allowlist (for example: "dashboard.example.org").
            allowedCorsHosts.forEach { host ->
                allowHost(host, schemes = listOf("https"))
            }
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Get)
        }

        // Cloud Run terminates TLS and supplies the original client address in X-Forwarded-For.
        // Without this plugin every caller would share the proxy's rate-limit bucket.
        install(XForwardedHeaders)

        install(StatusPages) {
            exception<RequestValidationException> { call, cause ->
                call.respondText(text = "400: Bad Request: ${cause.reasons.joinToString()}", status = HttpStatusCode.BadRequest)
            }
            exception<Throwable> { call, cause ->
                call.application.environment.log.error("Internal Server Error", cause)
                call.respondText(
                    text = "500: Internal Server Error: An internal error occurred.",
                    status = HttpStatusCode.InternalServerError
                )
            }
        }

        install(Authentication) {
            googleOidc("google")
        }

        install(RateLimit) {
            register(RateLimitName("forensics")) {
                // The default Ktor key is Unit, which would make every authenticated
                // user share one global five-request bucket.
                requestKey { call -> call.principal<GooglePrincipal>()?.subject ?: "unauthenticated" }
                rateLimiter(limit = 5, refillPeriod = 60.seconds)
            }
            register(RateLimitName("oauth-exchange")) {
                // Cloud Run terminates TLS before Ktor. This coarse per-source limit is a second
                // line of defense in addition to Google grant limits and one-time auth codes.
                requestKey { call -> call.request.origin.remoteHost }
                rateLimiter(limit = 20, refillPeriod = 60.seconds)
            }
            register(RateLimitName("oauth-exchange-global")) {
                // A hard service-wide ceiling still bounds abuse if a caller spoofs forwarding
                // headers. Normal desktop sign-in volume is far below this limit.
                requestKey { "oauth-exchange-global" }
                rateLimiter(limit = 200, refillPeriod = 60.seconds)
            }
        }

        install(RequestBodyLimit) {
            bodyLimit { MAX_REQUEST_BODY_BYTES }
        }

        install(RequestValidation) {
            validate<ForensicsRequest> { req ->
                when {
                    req.alerts.size > 2000 -> ValidationResult.Invalid("Payload too large: max alerts exceeded")
                    (req.topology?.nodes?.size ?: 0) > 500 ->
                        ValidationResult.Invalid("Payload too large: max topology nodes exceeded")
                    else -> ValidationResult.Valid
                }
            }
        }

        routing {
            // Cloud Run reserves some paths ending in "z" and can intercept them before Ktor.
            get("/health") {
                call.respondText("ok")
            }
            get("/healthz") {
                call.respondText("ok")
            }
            sourceCodeRoutes()
            googleOAuthBrokerRoutes(googleOAuthBroker)
            diagnosticsRoutes()
        }
    }.start(wait = true)
}

private const val MAX_REQUEST_BODY_BYTES = 1L * 1024 * 1024
