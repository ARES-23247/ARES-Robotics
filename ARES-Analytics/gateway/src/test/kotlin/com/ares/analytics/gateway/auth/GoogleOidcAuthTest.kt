package com.ares.analytics.gateway.auth

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the round-1 OIDC provider rewrite using the injectable
 * [GoogleOidcAuthenticationProvider.Config.tokenVerifier] so signature verification is
 * faked without a production backdoor. Covers the security-relevant outcomes:
 * valid token authenticates, invalid token is rejected (401), and a missing/ malformed
 * Authorization header is rejected (401).
 *
 * Audience enforcement lives in [verifyGoogleIdToken] (always built with a non-empty
 * audience via [DEFAULT_OIDC_AUDIENCE]) and is delegated to google-api-client, which
 * cannot be unit-tested without Google's signing keys; the injectable verifier bypasses
 * it by design, so these tests do not exercise the audience check directly.
 */
class GoogleOidcAuthTest {

    private fun io.ktor.server.testing.ApplicationTestBuilder.installSecuredRoute(
        verifier: (String) -> GooglePrincipal?
    ) {
        application {
            install(Authentication) {
                googleOidc("google") {
                    tokenVerifier = verifier
                }
            }
            routing {
                authenticate("google") {
                    get("/secure") {
                        call.respondText("ok")
                    }
                }
            }
        }
    }

    @Test
    fun `valid id token authenticates and reaches the protected route`() = testApplication {
        installSecuredRoute { token ->
            if (token == "good") GooglePrincipal(subject = "sub-1", email = "u@example.com", name = "User") else null
        }
        val response = client.get("/secure") {
            header(HttpHeaders.Authorization, "Bearer good")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `invalid id token is rejected with 401`() = testApplication {
        installSecuredRoute { token ->
            if (token == "good") GooglePrincipal(subject = "sub-1", email = "u@example.com", name = "User") else null
        }
        val response = client.get("/secure") {
            header(HttpHeaders.Authorization, "Bearer not-the-right-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `missing Authorization header is rejected with 401`() = testApplication {
        installSecuredRoute { _ -> GooglePrincipal(subject = "sub-1", email = "u@example.com", name = "User") }
        val response = client.get("/secure")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `non-Bearer Authorization header is rejected with 401`() = testApplication {
        installSecuredRoute { _ -> GooglePrincipal(subject = "sub-1", email = "u@example.com", name = "User") }
        val response = client.get("/secure") {
            header(HttpHeaders.Authorization, "Basic dXNlcjpwdw==")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
