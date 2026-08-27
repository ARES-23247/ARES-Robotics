package com.ares.analytics.service

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncEngineUploadAtomicityTest {
    @Test
    fun `winning manifest snapshot is installed before its prior object is deleted`() = runTest {
        val events = mutableListOf<String>()

        val installedId = installImmutableCloudObject(
            uploadNewObject = {
                events += "upload:new"
                "new"
            },
            swapManifest = { newObjectId, recordPriorObjectIds ->
                recordPriorObjectIds(setOf("old-stale"))
                events += "manifest:conflict"
                recordPriorObjectIds(setOf("old-current"))
                events += "manifest:$newObjectId"
            },
            currentManifestObjectId = {
                error("successful manifest swap must not require reconciliation")
            },
            deleteObject = { objectId -> events += "delete:$objectId" }
        )

        assertEquals("new", installedId)
        assertEquals(
            listOf(
                "upload:new",
                "manifest:conflict",
                "manifest:new",
                "delete:old-current"
            ),
            events
        )
    }

    @Test
    fun `terminal manifest failure preserves old reference and cleans new orphan`() = runTest {
        val events = mutableListOf<String>()

        val failure = assertFailsWith<IllegalStateException> {
            installImmutableCloudObject(
                uploadNewObject = {
                    events += "upload:new"
                    "new"
                },
                swapManifest = { _, recordPriorObjectIds ->
                    recordPriorObjectIds(setOf("old"))
                    events += "manifest:failed"
                    throw IllegalStateException("index conflict")
                },
                currentManifestObjectId = {
                    events += "reconcile:old"
                    "old"
                },
                deleteObject = { objectId -> events += "delete:$objectId" }
            )
        }

        assertEquals("index conflict", failure.message)
        assertEquals(
            listOf(
                "upload:new",
                "manifest:failed",
                "reconcile:old",
                "delete:new"
            ),
            events
        )
    }

    @Test
    fun `ambiguous manifest response reconciles committed new reference before cleanup`() = runTest {
        val events = mutableListOf<String>()

        val installedId = installImmutableCloudObject(
            uploadNewObject = {
                events += "upload:new"
                "new"
            },
            swapManifest = { newObjectId, recordPriorObjectIds ->
                recordPriorObjectIds(setOf("old"))
                events += "manifest:$newObjectId"
                throw IllegalStateException("response lost")
            },
            currentManifestObjectId = {
                events += "reconcile:new"
                "new"
            },
            deleteObject = { objectId -> events += "delete:$objectId" }
        )

        assertEquals("new", installedId)
        assertEquals(
            listOf(
                "upload:new",
                "manifest:new",
                "reconcile:new",
                "delete:old"
            ),
            events
        )
    }

    @Test
    fun `failed reconciliation retains possibly referenced new object`() = runTest {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            installImmutableCloudObject(
                uploadNewObject = {
                    events += "upload:new"
                    "new"
                },
                swapManifest = { _, _ ->
                    events += "manifest:unknown"
                    throw IllegalStateException("response lost")
                },
                currentManifestObjectId = {
                    events += "reconcile:failed"
                    throw IllegalStateException("Drive unavailable")
                },
                deleteObject = { objectId -> events += "delete:$objectId" }
            )
        }

        assertEquals(
            listOf(
                "upload:new",
                "manifest:unknown",
                "reconcile:failed"
            ),
            events
        )
    }
}
