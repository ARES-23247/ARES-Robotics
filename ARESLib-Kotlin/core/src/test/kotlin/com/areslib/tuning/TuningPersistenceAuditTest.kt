package com.areslib.tuning

import com.areslib.telemetry.ITelemetry
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TuningPersistenceAuditTest {
    @TempDir lateinit var temporary: Path

    private fun runtime(): TypedTuningRuntime {
        val declaration = TuningParameterDeclaration(
            uid = "control.count", key = "control.count", componentUid = "control.main", displayName = "Count",
            description = "Count", type = TuningParameterType.INT, defaultValue = TuningValue(intValue = 2),
            applyPolicy = TuningApplyPolicy.LIVE_SAFE,
        )
        return TypedTuningRuntime(listOf(declaration), emptyMap(),
            TuningMetadataSnapshot("project.test", null, "profile.base", listOf(declaration), listOf("profile.base")))
    }

    private class Wire : ITelemetry {
        val numbers = mutableMapOf<String, Double>()
        var failAcknowledgement = false
        override fun putNumber(key: String, value: Double) { numbers[key] = value }
        override fun putString(key: String, value: String) {
            check(!failAcknowledgement || !key.endsWith("/LastResult")) { "ack failed" }
        }
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double) = numbers[key] ?: defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
        fun request(value: Int, nonce: Int) {
            numbers["Tuning/Parameters/control.count/Requested"] = value.toDouble()
            numbers["Tuning/Parameters/control.count/RequestNonce"] = nonce.toDouble()
        }
    }

    private fun manager(runtime: TypedTuningRuntime, wire: Wire) = TuningManager(
        runtime, wire, { TuningApplyContext(true, true) }, { _, _ -> true }, { true },
        temporary, temporary.resolve(".ares/local/tuning/runtime.arestuning"),
    )

    @Test fun `accepted tuning does not wait for a blocked filesystem write`() {
        val runtime = runtime(); val wire = Wire(); val manager = manager(runtime, wire)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        manager.writeLocalOverlay = { _, _, _ ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "fixture release timed out" }
        }
        val loop = Executors.newSingleThreadExecutor()
        wire.request(3, 0)
        val update = loop.submit { manager.update(500) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            update.get(1, TimeUnit.SECONDS)
            assertEquals(3, runtime.int("control.count"))
        } finally {
            release.countDown()
            update.get(5, TimeUnit.SECONDS)
            manager.close()
            loop.shutdownNow()
            assertTrue(loop.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `slow writer coalesces thousands of proposals into the latest pending snapshot`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val written = CopyOnWriteArrayList<String>()
        val overlay = runtime().localOverlay("local.0", "runtime", "Runtime")
        val writer = TuningOverlayWriter { snapshot ->
            written += snapshot.uid
            if (written.size == 1) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            writer.submit(overlay)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(10_000) { writer.submit(overlay.copy(uid = "local.${it + 1}")) }
        } finally { release.countDown(); writer.close() }
        assertEquals(listOf("local.0", "local.10000"), written)
        assertThrows(IllegalStateException::class.java) { writer.submit(overlay) }
    }

    @Test fun `failed save waits for an owner retry and recovery clears its failure`() {
        val attempts = AtomicInteger(); val second = CountDownLatch(1)
        val diskFailure = java.io.IOException("disk unavailable")
        val writer = TuningOverlayWriter {
            if (attempts.incrementAndGet() == 1) throw diskFailure
            second.countDown()
        }
        try {
            writer.submit(runtime().localOverlay("local.test", "runtime", "Runtime"))
            awaitCondition { writer.failure != null }
            assertSame(diskFailure, writer.failure)
            assertFalse(second.await(100, TimeUnit.MILLISECONDS), "Failed storage must not spin")
            writer.retry()
            assertTrue(second.await(5, TimeUnit.SECONDS))
        } finally { writer.close() }
        assertNull(writer.failure)
        assertEquals(2, attempts.get())
    }

    @Test fun `a failed older write cannot replace a newer pending proposal`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val written = CopyOnWriteArrayList<String>()
        val overlay = runtime().localOverlay("local.old", "runtime", "Runtime")
        val writer = TuningOverlayWriter {
            written += it.uid
            if (it.uid == "local.old") {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                throw java.io.IOException("old save failed")
            }
        }
        try {
            writer.submit(overlay)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            writer.submit(overlay.copy(uid = "local.new"))
        } finally { release.countDown(); writer.close() }
        assertEquals(listOf("local.old", "local.new"), written)
        assertNull(writer.failure)
    }

    @Test fun `close retries retained failure once and reports unsaved data`() {
        val attempts = AtomicInteger(); val diskFailure = java.io.IOException("full disk")
        val writer = TuningOverlayWriter { attempts.incrementAndGet(); throw diskFailure }
        writer.submit(runtime().localOverlay("local.test", "runtime", "Runtime"))
        awaitCondition { writer.failure != null }
        assertSame(diskFailure, assertThrows(java.io.IOException::class.java) { writer.close() })
        assertEquals(2, attempts.get())
    }

    @Test fun `accepted consumer value survives telemetry failure and is flushed on close`() {
        val runtime = runtime(); val wire = Wire(); val manager = manager(runtime, wire)
        wire.request(8, 0); wire.failAcknowledgement = true
        assertThrows(IllegalStateException::class.java) { manager.update(500) }
        manager.close()
        val json = Files.readString(temporary.resolve(".ares/local/tuning/runtime.arestuning"))
        val profile = com.google.gson.Gson().fromJson(json, TuningProfileDocument::class.java)
        assertEquals(8, profile.values.single().value.intValue)
        wire.request(9, 1); manager.update(1000)
        assertEquals(8, runtime.int("control.count"))
        assertThrows(IllegalStateException::class.java) { manager.publishMetadataAndValues() }
        manager.close()
    }

    @Test fun `return to canonical value replaces the saved local override with an empty overlay`() {
        val runtime = runtime(); val wire = Wire(); val manager = manager(runtime, wire)
        try {
            wire.request(8, 0); manager.update(500)
            wire.request(2, 1); manager.update(1000)
        } finally { manager.close() }
        val profile = com.google.gson.Gson().fromJson(
            Files.readString(temporary.resolve(".ares/local/tuning/runtime.arestuning")), TuningProfileDocument::class.java)
        assertTrue(profile.values.isEmpty())
        assertNull(manager.localOverlayPersistenceFailure)
    }

    @Test fun `disk failure remains observable without throwing from the control update`() {
        val runtime = runtime(); val wire = Wire(); val manager = manager(runtime, wire)
        val failure = java.io.IOException("read-only disk")
        manager.writeLocalOverlay = { _, _, _ -> throw failure }
        wire.request(8, 0)
        manager.update(500)
        awaitCondition { manager.localOverlayPersistenceFailure != null }
        assertEquals(8, runtime.int("control.count"))
        assertSame(failure, manager.localOverlayPersistenceFailure)
        assertSame(failure, assertThrows(java.io.IOException::class.java) { manager.close() })
        manager.close()
    }

    @Test fun `an aliased project root is accepted while aliases in every local ancestor are rejected`() {
        val actual = Files.createDirectories(temporary.resolve("actual"))
        val rootAlias = temporary.resolve("project")
        val overlay = runtime().localOverlay("local.test", "runtime", "Runtime")
        linkDirectory(rootAlias, actual)
        try {
            LocalTuningOverlayStore.writeAtomically(rootAlias, rootAlias.resolve(".ares/local/tuning/value.arestuning"), overlay)
            assertTrue(Files.isRegularFile(actual.resolve(".ares/local/tuning/value.arestuning")))
        } finally { Files.deleteIfExists(rootAlias) }
        for (relative in listOf(".ares", ".ares/local", ".ares/local/tuning/nested")) {
            val project = Files.createDirectories(temporary.resolve("test-${relative.count { it == '/' }}"))
            val alias = project.resolve(relative)
            Files.createDirectories(alias.parent)
            val target = Files.createDirectories(temporary.resolve("target-${relative.count { it == '/' }}"))
            linkDirectory(alias, target)
            try {
                val output = if (relative.endsWith("nested")) alias.resolve("value.arestuning")
                    else project.resolve(".ares/local/tuning/value.arestuning")
                assertThrows(IllegalArgumentException::class.java) {
                    LocalTuningOverlayStore.writeAtomically(project, output, overlay)
                }
                Files.list(target).use { assertEquals(0L, it.count(), relative) }
            } finally { Files.deleteIfExists(alias) }
        }
    }

    @Test fun `invalid destination preserves existing data without creating a temporary file`() {
        val destination = Files.createDirectories(temporary.resolve(".ares/local/tuning/runtime.arestuning"))
        Files.writeString(destination.resolve("keep"), "original bytes")
        assertThrows(IllegalArgumentException::class.java) {
            LocalTuningOverlayStore.writeAtomically(temporary, destination,
                runtime().localOverlay("local.test", "runtime", "Runtime"))
        }
        assertEquals("original bytes", Files.readString(destination.resolve("keep")))
        Files.list(destination.parent).use { assertEquals(listOf(destination), it.toList()) }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(condition(), "Writer condition did not complete")
    }

    @Test fun `interrupted close still joins its writer and preserves owner interruption`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val writerThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val writer = TuningOverlayWriter {
            writerThread.set(Thread.currentThread())
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
        }
        val closer = Executors.newSingleThreadExecutor()
        writer.submit(runtime().localOverlay("local.test", "runtime", "Runtime"))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closed = closer.submit<Boolean> {
            Thread.currentThread().interrupt()
            writer.close()
            Thread.interrupted()
        }
        try {
            assertThrows(java.util.concurrent.TimeoutException::class.java) { closed.get(100, TimeUnit.MILLISECONDS) }
        } finally {
            release.countDown()
            assertTrue(closed.get(5, TimeUnit.SECONDS))
            closer.shutdownNow()
            assertTrue(closer.awaitTermination(5, TimeUnit.SECONDS))
            writer.close()
        }
        assertFalse(writerThread.get().isAlive)
    }

    @Test fun `Windows failed atomic replacement preserves old bytes and cleans its staged file`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val parent = Files.createDirectories(temporary.resolve(".ares/local/tuning"))
        val destination = parent.resolve("runtime.arestuning")
        Files.writeString(destination, "original bytes")
        java.nio.channels.FileChannel.open(destination, java.nio.file.StandardOpenOption.READ,
            com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE).use {
            assertThrows(java.io.IOException::class.java) {
                LocalTuningOverlayStore.writeAtomically(temporary, destination,
                    runtime().localOverlay("local.test", "runtime", "Runtime"))
            }
        }
        assertEquals("original bytes", Files.readString(destination))
        Files.list(parent).use { assertEquals(listOf(destination), it.toList()) }
    }

    @Test fun `local overlay cannot overwrite a canonical file through a directory alias`() {
        val canonical = Files.createDirectories(temporary.resolve(".ares/tuning"))
        val destination = canonical.resolve("runtime.arestuning")
        Files.writeString(destination, "canonical bytes")
        val local = Files.createDirectories(temporary.resolve(".ares/local"))
        val alias = local.resolve("tuning")
        linkDirectory(alias, canonical)
        try {
            val overlay = runtime().localOverlay("local.test", "runtime", "Runtime")
            assertThrows(IllegalArgumentException::class.java) {
                LocalTuningOverlayStore.writeAtomically(temporary, alias.resolve(destination.fileName), overlay)
            }
            assertEquals("canonical bytes", Files.readString(destination))
        } finally { Files.deleteIfExists(alias) }
    }

    private fun linkDirectory(link: Path, target: Path) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Junction creation timed out")
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
            } finally { if (process.isAlive) process.destroyForcibly() }
        } else Files.createSymbolicLink(link, target)
    }
}
