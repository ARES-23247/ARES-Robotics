package com.ares.analytics.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncEngineDeleteAtomicityTest {
    @Test
    fun `manifest reference is removed before immutable object cleanup`() = runTest {
        val events = mutableListOf<String>()

        removeImmutableCloudObject(
            removeManifestReference = { events += "manifest:remove" },
            deleteObject = { events += "object:delete" }
        )

        assertEquals(listOf("manifest:remove", "object:delete"), events)
    }

    @Test
    fun `manifest failure retains immutable object`() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            removeImmutableCloudObject(
                removeManifestReference = {
                    events += "manifest:failed"
                    throw IllegalStateException("index conflict")
                },
                deleteObject = { events += "object:delete" }
            )
        }

        assertEquals(listOf("manifest:failed"), events)
    }

    @Test
    fun `orphan cleanup failure does not turn committed deletion into failure`() = runTest {
        val events = mutableListOf<String>()

        removeImmutableCloudObject(
            removeManifestReference = { events += "manifest:remove" },
            deleteObject = {
                events += "object:failed"
                throw IllegalStateException("drive unavailable")
            },
            onCleanupFailure = { events += "cleanup:reported" }
        )

        assertEquals(
            listOf("manifest:remove", "object:failed", "cleanup:reported"),
            events
        )
    }

    @Test
    fun `cancellation still propagates after manifest removal`() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<CancellationException> {
            removeImmutableCloudObject(
                removeManifestReference = { events += "manifest:remove" },
                deleteObject = { throw CancellationException("cancelled") },
                onCleanupFailure = { events += "cleanup:reported" }
            )
        }

        assertEquals(listOf("manifest:remove"), events)
    }
}
