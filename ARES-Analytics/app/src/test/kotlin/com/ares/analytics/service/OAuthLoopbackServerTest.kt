package com.ares.analytics.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OAuthLoopbackServerTest {
    @Test
    fun `delayed old attempt cleanup leaves the replacement callback server available`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val lifecycleLock = Any()
        var generation = 1L
        val server = OAuthLoopbackServer(lifecycleLock, scope, { null }, { _, _, _ -> })
        var oldCleanup: (() -> Unit)? = null
        try {
            val firstPort = ServerSocket(0).use { it.localPort }
            server.boot(firstPort, generation) { it == generation }
            synchronized(lifecycleLock) {
                generation = 2
                oldCleanup = server.detach()
            }
            // The previous socket is still bound, so the second ephemeral port is distinct.
            val replacementPort = ServerSocket(0).use { it.localPort }
            server.boot(replacementPort, generation) { it == generation }
            oldCleanup?.invoke()
            oldCleanup = null
            server.stop(expectedGeneration = 1)
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$replacementPort/callback"))
                .timeout(Duration.ofSeconds(5)).GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("invalid state"))
        } finally {
            oldCleanup?.invoke()
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `boot failure on port collision stops candidate and leaves server detached`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val lifecycleLock = Any()
        val generation = 1L
        val server = OAuthLoopbackServer(lifecycleLock, scope, { null }, { _, _, _ -> })
        val occupyingSocket = ServerSocket(0)
        val occupiedPort = occupyingSocket.localPort
        try {
            val exception = assertThrows(Exception::class.java) {
                server.boot(occupiedPort, generation) { it == generation }
            }
            assertTrue(
                exception is java.net.BindException ||
                    exception.cause is java.net.BindException ||
                    exception.message?.contains("Address already in use") == true ||
                    exception.message?.contains("Failed to bind") == true
            )
            assertNull(server.detach())
        } finally {
            occupyingSocket.close()
            server.stop()
            scope.cancel()
        }
    }
}
