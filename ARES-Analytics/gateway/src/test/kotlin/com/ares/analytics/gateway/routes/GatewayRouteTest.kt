package com.ares.analytics.gateway.routes

import com.ares.analytics.gateway.auth.googleOidc
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.server.plugins.ratelimit.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GatewayRouteTest class.
 */
class GatewayRouteTest {

    @Test
    /**
     * testHealth fun.
     */
    fun testHealth() = testApplication {
        application {
            routing {
                get("/health") {
                    call.respondText("ok")
                }
            }
        }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    /**
     * testAuthDiagnosticsWithoutToken fun.
     */
    fun testAuthDiagnosticsWithoutToken() = testApplication {
        application {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = false
                    ignoreUnknownKeys = true
                })
            }
            install(Authentication) {
                googleOidc("google")
            }
            install(RateLimit) {
                register(RateLimitName("forensics")) {
                    rateLimiter(limit = 5, refillPeriod = 60.seconds)
                }
            }
            routing {
                diagnosticsRoutes()
            }
        }
        val response = client.post("/api/diagnostics/forensics") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `oauth-exchange rate limit keys on last proxy in X-Forwarded-For`() = testApplication {
        application {
            install(XForwardedHeaders) {
                useLastProxy()
            }
            install(RateLimit) {
                register(RateLimitName("oauth-exchange")) {
                    requestKey { call -> call.request.origin.remoteHost }
                    rateLimiter(limit = 2, refillPeriod = 60.seconds)
                }
            }
            routing {
                rateLimit(RateLimitName("oauth-exchange")) {
                    post("/api/oauth/exchange") {
                        call.respondText(call.request.origin.remoteHost)
                    }
                }
            }
        }

        val first = client.post("/api/oauth/exchange") {
            header(HttpHeaders.XForwardedFor, "203.0.113.1, 198.51.100.42")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals("198.51.100.42", first.bodyAsText())

        val second = client.post("/api/oauth/exchange") {
            header(HttpHeaders.XForwardedFor, "203.0.113.2, 198.51.100.42")
        }
        assertEquals(HttpStatusCode.OK, second.status)

        val third = client.post("/api/oauth/exchange") {
            header(HttpHeaders.XForwardedFor, "203.0.113.3, 198.51.100.42")
        }
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
    }
}
