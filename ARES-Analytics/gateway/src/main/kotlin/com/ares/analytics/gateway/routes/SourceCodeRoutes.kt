// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ares.analytics.gateway.routes

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Publishes the license and corresponding-source location for gateway users. */
fun Route.sourceCodeRoutes() {
    get("/source") {
        call.respondText(
            text = SOURCE_CODE_NOTICE,
            contentType = ContentType.Text.Plain,
        )
    }
}

internal const val SOURCE_CODE_NOTICE =
    "ARES Robotics Studio is licensed under GNU AGPL-3.0-or-later. " +
        "Source code: https://github.com/ARES-23247/ARES-Analytics"
