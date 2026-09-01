package com.ares.analytics.gateway.routes

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiagnosticsFutureTest {
    @Test
    fun `coroutine timeout cancels the underlying RPC future`() = runBlocking {
        val future = CompletableFuture<String>()

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(10) { awaitCompletableFuture(future) }
        }

        assertTrue(future.isCancelled)
    }

    @Test
    fun `completed RPC future resumes the caller`() = runBlocking {
        val future = CompletableFuture.completedFuture("ok")

        assertEquals("ok", awaitCompletableFuture(future))
    }

    @Test
    fun `failed RPC future preserves the asynchronous failure`() = runBlocking {
        val failure = IllegalStateException("diagnostics unavailable")
        val future = CompletableFuture<String>().also { it.completeExceptionally(failure) }

        val thrown = assertFailsWith<IllegalStateException> { awaitCompletableFuture(future) }
        assertEquals(failure.message, thrown.message)
    }
}
