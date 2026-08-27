// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ares.analytics.gateway.routes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceCodeRoutesTest {
    @Test
    fun `source endpoint identifies license and repository`() = testApplication {
        application {
            routing {
                sourceCodeRoutes()
            }
        }

        val response = client.get("/source")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            ContentType.Text.Plain.toString(),
            response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';'),
        )
        assertEquals(SOURCE_CODE_NOTICE, response.bodyAsText())
    }
}
