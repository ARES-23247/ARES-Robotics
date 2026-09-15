package com.ares.analytics.service

import com.ares.analytics.shared.models.DriverProfile
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mockito.Mockito
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DriverProfilePersistenceAuditTest {
    @Test fun `failed save preserves the last successfully stored in memory profile`() = runTest { fixture {
        val service = service()
        service.saveProfile(DriverProfile("driver", 1.2, 3.0))
        blockDestination()
        assertFailsWith<IOException> { service.saveProfile(DriverProfile("driver", 1.8, 5.0)) }
        assertEquals(1.2, service.getProfile("driver")?.deadbandExponent)
    } }
    @Test fun `failed deletion preserves the last successfully stored profile`() = runTest { fixture {
        val service = service()
        service.saveProfile(DriverProfile("driver", 1.2, 3.0))
        blockDestination()
        assertFailsWith<IOException> { service.deleteProfile("driver") }
        assertNotNull(service.getProfile("driver"))
    } }
    @Test fun `a directory at the profile path is an IO failure and is not quarantined as JSON`() { fixtureSync {
        assertTrue(file.mkdir()); file.resolve("keep.txt").writeText("preserve")
        assertFailsWith<IOException> { service() }
        assertEquals("preserve", file.resolve("keep.txt").readText())
    } }
    @Test fun `negative spectral metadata is rejected before writing`() = runTest { fixture {
        val service = service()
        assertFailsWith<IllegalArgumentException> { service.saveProfile(DriverProfile("driver", jitterPeakFrequencyHz = -1.0)) }
        assertFailsWith<IllegalArgumentException> { service.saveProfile(DriverProfile("driver", jitterAmplitude = -1.0)) }
    } }
    @Test fun `separate service instances do not overwrite unrelated successful profile edits`() = runTest { fixture {
        val first = service(); val second = service()
        first.saveProfile(DriverProfile("first", 1.2))
        second.saveProfile(DriverProfile("second", 1.3))
        val reopened = service()
        assertNotNull(reopened.getProfile("first")); assertNotNull(reopened.getProfile("second"))
    } }
    @Test fun `a stale instance cannot resurrect a profile another instance deleted`() = runTest { fixture {
        val first = service(); first.saveProfile(DriverProfile("deleted", 1.2))
        val second = service()
        first.deleteProfile("deleted")
        second.saveProfile(DriverProfile("retained", 1.3))
        assertNull(service().getProfile("deleted"))
    } }
    @Test fun `duplicate persisted names are rejected instead of choosing an arbitrary profile`() { fixtureSync {
        file.writeText(Json.encodeToString(listOf(DriverProfile("duplicate", 1.2), DriverProfile("duplicate", 1.8))))
        val loaded = service()
        assertNull(loaded.getProfile("duplicate"))
        assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("profiles.json.corrupt-") })
    } }

    @Test fun `a failure at atomic replace preserves bytes and every live reader snapshot`() = runTest { fixture {
        val first = service(); first.saveProfile(DriverProfile("driver", 1.2))
        val bytes = file.readBytes()
        val failing = service { destination, data -> writeFileAtomically(destination, { _, _ -> throw IOException("injected replace failure") }) { it.writeBytes(data) } }
        assertFailsWith<IOException> { failing.saveProfile(DriverProfile("driver", 1.8)) }
        assertContentEquals(bytes, file.readBytes())
        assertEquals(1.2, first.getProfile("driver")?.deadbandExponent)
        assertEquals(1.2, failing.getProfile("driver")?.deadbandExponent)
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    } }
    @Test fun `corrupt recovery preserves exact original bytes before writing defaults`() { fixtureSync {
        val original = "{broken json".toByteArray()
        file.writeBytes(original)
        val loaded = service()
        assertEquals(3, loaded.getProfiles().size)
        val backup = directory.listFiles().orEmpty().single { it.name.startsWith("profiles.json.corrupt-") }
        assertContentEquals(original, backup.readBytes())
        assertEquals(3, Json.decodeFromString<List<DriverProfile>>(file.readText()).size)
    } }
    @Test fun `failed corruption backup never overwrites the original profile file`() { fixtureSync {
        val original = "{broken json".toByteArray(); file.writeBytes(original)
        assertFailsWith<IOException> { service { destination, data ->
            if (destination.name.startsWith("profiles.json.corrupt-")) throw IOException("injected backup failure")
            WRITE_DRIVER_PROFILES(destination, data)
        } }
        assertContentEquals(original, file.readBytes())
        assertEquals(listOf("profiles.json"), directory.listFiles().orEmpty().map { it.name })
    } }
    @Test fun `failed default replacement retains both the original and completed recovery backup`() { fixtureSync {
        val original = "{broken json".toByteArray(); file.writeBytes(original)
        assertFailsWith<IOException> { service { destination, data ->
            if (destination == file) throw IOException("injected main replacement failure")
            WRITE_DRIVER_PROFILES(destination, data)
        } }
        assertContentEquals(original, file.readBytes())
        assertContentEquals(original, directory.listFiles().orEmpty().single { it.name.startsWith("profiles.json.corrupt-") }.readBytes())
    } }
    @Test fun `oversized existing profile files fail without recovery or truncation`() { fixtureSync {
        val original = ByteArray(1_048_577) { ' '.code.toByte() }; file.writeBytes(original)
        assertFailsWith<IOException> { service() }
        assertContentEquals(original, file.readBytes())
        assertEquals(1, directory.listFiles().orEmpty().size)
    } }
    @Test fun `oversized profile lists remain untouched`() { fixtureSync {
        val original = Json.encodeToString(List(257) { DriverProfile("driver$it") }); file.writeText(original)
        assertFailsWith<IOException> { service() }
        assertEquals(original, file.readText()); assertEquals(1, directory.listFiles().orEmpty().size)
    } }
    @Test fun `read snapshots update coherently after another instance commits`() = runTest { fixture {
        val first = service(); val second = service()
        second.saveProfile(DriverProfile("shared", 1.4))
        assertEquals(1.4, first.getProfile("shared")?.deadbandExponent)
        val previous = first.getProfiles()
        second.deleteProfile("shared")
        assertNull(first.getProfile("shared"))
        assertNotNull(previous.find { it.name == "shared" })
    } }
    @Test fun `canonical path aliases share the same commit boundary`() = runTest { fixture {
        val first = service()
        val second = DriverAnalysisService(database, solver, directory.resolve("nested/../profiles.json").path)
        second.saveProfile(DriverProfile("alias", 1.4))
        assertEquals(1.4, first.getProfile("alias")?.deadbandExponent)
    } }
    @Test fun `concurrent saves preserve all successful profiles`() = runTest { fixture {
        val first = service(); val second = service()
        (0 until 40).map { i -> async(Dispatchers.IO) {
            (if (i % 2 == 0) first else second).saveProfile(DriverProfile("driver$i", 1.0 + i / 100.0))
        } }.awaitAll()
        assertEquals(43, first.getProfiles().size)
        assertEquals(first.getProfiles(), second.getProfiles())
        assertEquals(43, Json.decodeFromString<List<DriverProfile>>(file.readText()).size)
    } }
    @Test fun `no-op changes avoid disk replacement`() = runTest { fixture {
        service()
        var writes = 0
        val writer = service { destination, data -> writes++; WRITE_DRIVER_PROFILES(destination, data) }
        writer.saveProfile(assertNotNull(writer.getProfile("Default Alpha")))
        writer.deleteProfile("missing")
        assertEquals(0, writes)
    } }
    @Test fun `mutations incorporate already completed external edits`() = runTest { fixture {
        val service = service()
        file.writeText(Json.encodeToString(listOf(DriverProfile("external", 1.4))))
        service.saveProfile(DriverProfile("local", 1.2))
        assertEquals(setOf("external", "local"), service.getProfiles().map { it.name }.toSet())
    } }
    @Test fun `invalid external edits fail a mutation without replacing prior memory or bytes`() = runTest { fixture {
        val service = service(); val before = service.getProfiles()
        file.writeText("{broken json")
        assertFailsWith<IllegalArgumentException> { service.saveProfile(DriverProfile("local", 1.2)) }
        assertEquals(before, service.getProfiles()); assertEquals("{broken json", file.readText())
        assertEquals(1, directory.listFiles().orEmpty().size)
    } }
    @Test fun `profile count and value limits are enforced before replacement`() = runTest { fixture {
        file.writeText(Json.encodeToString(List(256) { DriverProfile("driver$it") }))
        val service = service(); val before = file.readBytes()
        assertFailsWith<IllegalArgumentException> { service.saveProfile(DriverProfile("one-too-many")) }
        assertFailsWith<IllegalArgumentException> { service.saveProfile(DriverProfile(" ")) }
        assertFailsWith<IllegalArgumentException> { service.saveProfile(DriverProfile("x".repeat(257))) }
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            assertFailsWith<IllegalArgumentException> { service.saveProfile(DriverProfile("driver0", deadbandExponent = bad)) }
            assertFailsWith<IllegalArgumentException> { service.saveProfile(DriverProfile("driver0", slewRateLimit = bad)) }
        }
        assertContentEquals(before, file.readBytes())
        service.saveProfile(DriverProfile("driver0", 1.4))
        assertEquals(256, service.getProfiles().size)
    } }
    @Test fun `malformed UTF8 is preserved in recovery instead of changing profile identities`() { fixtureSync {
        val original = byteArrayOf(0xc3.toByte(), 0x28); file.writeBytes(original)
        assertEquals(3, service().getProfiles().size)
        assertContentEquals(original, directory.listFiles().orEmpty().single { it.name.startsWith("profiles.json.corrupt-") }.readBytes())
    } }
    @Test fun `empty lists and finite maximum slew values round trip without implicit defaults`() = runTest { fixture {
        file.writeText("[]")
        val service = service(); assertTrue(service.getProfiles().isEmpty())
        service.saveProfile(DriverProfile("driver", slewRateLimit = Double.MAX_VALUE))
        assertEquals(Double.MAX_VALUE, service.getProfile("driver")?.slewRateLimit)
        service.deleteProfile("driver"); assertEquals("[]", file.readText())
    } }
    @Test fun `readers see committed data while a write is blocked and cancellation after replacement stays coherent`() = runTest { fixture {
        val reader = service()
        val before = reader.getProfiles()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val writer = service { destination, data ->
            writeFileAtomically(destination, { _, _ ->
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS)) { "Test did not release atomic replacement" }
            }) { it.writeBytes(data) }
        }
        val saving = async(Dispatchers.IO) { writer.saveProfile(DriverProfile("committed", 1.4)) }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(15, TimeUnit.SECONDS)) }
            assertEquals(before, reader.getProfiles())
            assertNull(reader.getProfile("committed"))
            assertFalse(file.readText().contains("committed"))
            // Cancellation cannot interrupt a synchronous native replacement already in progress.
            saving.cancel()
        } finally {
            release.countDown()
            withContext(NonCancellable) { saving.join() }
        }
        assertTrue(saving.isCancelled)
        assertEquals(1.4, reader.getProfile("committed")?.deadbandExponent)
        assertNotNull(Json.decodeFromString<List<DriverProfile>>(file.readText()).find { it.name == "committed" })
    } }
    private class Fixture(val directory: File) {
        val file = directory.resolve("profiles.json")
        // Profile persistence has no database or solver dependency; mocks prevent unrelated IO.
        val database = Mockito.mock(DatabaseService::class.java)
        val solver = Mockito.mock(SysIdService::class.java)
        fun service(writer: (File, ByteArray) -> Unit = WRITE_DRIVER_PROFILES) = DriverAnalysisService(database, solver, file.absolutePath, writer)
        fun blockDestination() {
            assertTrue(file.delete()); assertTrue(file.mkdir())
            file.resolve("keep.txt").writeText("preserve")
        }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("ares-driver-profiles").toFile()
        try { Fixture(directory).block() } finally { directory.deleteRecursively() }
    }
    private fun fixtureSync(block: Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("ares-driver-profiles").toFile()
        try { Fixture(directory).block() } finally { directory.deleteRecursively() }
    }
}
