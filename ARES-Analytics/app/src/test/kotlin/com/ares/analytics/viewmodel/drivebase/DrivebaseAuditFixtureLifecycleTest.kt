package com.ares.analytics.viewmodel.drivebase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrivebaseAuditFixtureLifecycleTest {
    @Test
    fun `joining fixture work waits for its pending child`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        var completed = false
        scope.launch {
            release.await()
            completed = true
        }
        val joined = async(start = CoroutineStart.UNDISPATCHED) { scope.joinScopeChildren() }
        try {
            assertFalse(joined.isCompleted, "Joining must observe the fixture job, not the timeout job")
            release.complete(Unit)
            withTimeout(1_000) { joined.await() }
            assertTrue(completed)
        } finally {
            release.complete(Unit)
            scope.cancel()
            withTimeout(1_000) { scope.coroutineContext[Job]!!.join() }
        }
    }

    @Test
    fun `cancelling fixture work finishes child cleanup and returns`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var cleanedUp = false
        scope.launch {
            try {
                awaitCancellation()
            } finally {
                cleanedUp = true
            }
        }
        try {
            scope.cancelAndJoinScope(timeoutMs = 1_000)
            assertTrue(scope.coroutineContext[Job]!!.isCompleted)
            assertTrue(cleanedUp)
        } finally {
            scope.cancel()
            withTimeout(1_000) { scope.coroutineContext[Job]!!.join() }
        }
    }
}
