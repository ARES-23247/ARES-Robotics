package com.ares.analytics.service

import io.ktor.http.Parameters
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class PendingOAuthRequest(
    val state: String,
    val generation: Long,
    val successTitle: String,
    val onCodeReceived: suspend (String, Parameters) -> Unit,
    val onError: (String) -> Unit,
)

internal class OAuthLoopbackServer(
    private val lock: Any,
    private val serviceScope: CoroutineScope,
    private val consumePendingRequest: (String?) -> PendingOAuthRequest?,
    private val launchPendingCodeExchange: (PendingOAuthRequest, String, Parameters) -> Unit,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var serverGeneration: Long? = null

    fun boot(port: Int, generation: Long, isGenerationCurrent: (Long) -> Boolean) {
        stop(generation)
        val candidate = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                get("/callback") {
                    val returnedState = call.request.queryParameters["state"]
                    val pending = consumePendingRequest(returnedState)
                    if (pending == null) {
                        call.respondText("Authentication failed: invalid state parameter (possible CSRF attack).")
                        return@get
                    }
                    val code = call.request.queryParameters["code"]
                    val error = call.request.queryParameters["error"]

                    if (code != null) {
                        call.respondText(
                            callbackHtml(pending.successTitle),
                            io.ktor.http.ContentType.Text.Html
                        )
                        launchPendingCodeExchange(pending, code, call.request.queryParameters)
                    } else {
                        call.respondText("Authentication was not completed. Return to ARES Robotics Studio for recovery steps.")
                        pending.onError(error ?: "unknown")
                        serviceScope.launch { stop(pending.generation) }
                    }
                }
            }
        }
        val installed = synchronized(lock) {
            if (!isGenerationCurrent(generation)) {
                false
            } else {
                candidate.start(wait = false)
                server = candidate
                serverGeneration = generation
                true
            }
        }
        if (!installed) stopEmbeddedServer(candidate)
    }

    fun stop(expectedGeneration: Long? = null) {
        detach(expectedGeneration)?.invoke()
    }

    /** Detach under the shared auth lifecycle lock; shutdown outside it cannot close a successor. */
    fun detach(expectedGeneration: Long? = null): (() -> Unit)? {
        val serverToStop = synchronized(lock) {
            if (expectedGeneration != null && serverGeneration != expectedGeneration) {
                null
            } else {
                server.also {
                    server = null
                    serverGeneration = null
                }
            }
        }
        return serverToStop?.let { captured -> { stopEmbeddedServer(captured) } }
    }

    private fun stopEmbeddedServer(
        target: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>?
    ) {
        target?.let { runCatching { it.stop(1000, 2000) } }
    }

    private fun callbackHtml(successTitle: String): String = """
        <html>
        <head>
            <title>ARES Mission Control Sign-In</title>
            <style>
                body {
                    background-color: #0D0F14;
                    color: #E8ECF4;
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    height: 100vh;
                    margin: 0;
                }
                .card {
                    background-color: #161A22;
                    border: 1px solid #2A2F3C;
                    padding: 40px;
                    border-radius: 16px;
                    text-align: center;
                    box-shadow: 0 4px 20px rgba(0,0,0,0.5);
                }
                h1 { color: #00E5FF; margin-bottom: 8px; }
                p { color: #9CA3B4; }
            </style>
        </head>
        <body>
            <div class="card">
                <h1>$successTitle</h1>
                <p>Google returned the authorization response. ARES is completing the secure exchange now. Return to the application to see the final result.</p>
            </div>
        </body>
        </html>
    """.trimIndent()
}
