package com.areslib.hardware

import com.areslib.telemetry.ITelemetry
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HardwareRegistryLifecycleAuditTest {
    @Test
    fun `safety attempts every identity before rethrowing serious failures`() {
        val registry = HardwareRegistry()
        val events = mutableListOf<String>()
        val primary = AssertionError("first")
        val secondary = LinkageError("second")
        registry.registerDevice("first", Probe("first", events, safeFailure = primary))
        registry.registerDevice("ordinary", Probe("ordinary", events, safeFailure = IllegalStateException("ordinary")))
        registry.registerDevice("second", Probe("second", events, safeFailure = secondary))
        registry.registerDevice("shared", Probe("shared", events, safeFailure = primary))
        registry.registerDevice("last", Probe("last", events))
        try {
            assertSame(primary, assertThrows(AssertionError::class.java) { registry.safeAll() })
            assertEquals(listOf("safe:first", "safe:ordinary", "safe:second", "safe:shared", "safe:last"), events)
            assertEquals(listOf(secondary), primary.suppressed.toList())
            registry.safeAllFailure(primary)
            assertEquals(listOf(secondary), primary.suppressed.toList(), "Repeated failures must not grow diagnostics")
        } finally { registry.closeAll() }
    }

    @Test
    fun `close attempts all resources once clears state and preserves serious failure`() {
        val registry = HardwareRegistry()
        val events = mutableListOf<String>()
        val primary = AssertionError("device")
        val secondary = LinkageError("auxiliary")
        val first = Probe("first", events, closeFailure = primary)
        registry.registerDevice("first", first)
        registry.registerDevice("alias", first)
        registry.registerCloseable(first)
        registry.registerDevice("last", Probe("last", events))
        registry.registerCloseable(AutoCloseable { events.add("close:ordinary"); throw IllegalStateException("ordinary") })
        registry.registerCloseable(AutoCloseable { events.add("close:auxiliary"); throw secondary })
        registry.registerCloseable(AutoCloseable { events.add("close:shared"); throw primary })
        registry.registerCloseable(AutoCloseable { events.add("close:final") })
        try {
            assertSame(primary, assertThrows(AssertionError::class.java) { registry.closeAll() })
            assertEquals(listOf("close:first", "close:last", "close:ordinary", "close:auxiliary", "close:shared", "close:final"), events)
            assertEquals(listOf(secondary), primary.suppressed.toList())
            events.clear()
            registry.closeAll()
            registry.refreshAll()
            registry.safeAll()
            assertTrue(events.isEmpty(), "Closed resources must be forgotten even after failure")
            registry.registerDevice("first", Probe("replacement", events))
            registry.refreshAll()
            assertEquals(listOf("refresh:replacement"), events)
        } finally {
            first.closeFailure = null
            runCatching { registry.closeAll() }
        }
    }

    @Test
    fun `ordinary safety and close exceptions remain isolated`() {
        val registry = HardwareRegistry()
        val events = mutableListOf<String>()
        registry.registerDevice("bad", Probe("bad", events, IllegalStateException("safe"), IllegalStateException("close")))
        registry.registerDevice("good", Probe("good", events))
        registry.safeAll()
        registry.closeAll()
        assertEquals(listOf("safe:bad", "safe:good", "close:bad", "close:good"), events)
    }

    @Test
    fun `aliases read and safe once while retaining each telemetry prefix`() {
        val registry = HardwareRegistry()
        val events = mutableListOf<String>()
        val probe = Probe("physical", events)
        registry.registerDevice("one", probe)
        registry.registerDevice("two", probe)
        try {
            registry.refreshAll()
            registry.safeAll()
            assertEquals(listOf("refresh:physical", "safe:physical"), events)
            registry.publishAll(EmptyTelemetry())
            assertEquals(listOf("Hardware/one", "Hardware/two"), probe.prefixes)
        } finally { registry.closeAll() }
    }

    @Test
    fun `replacement keeps surviving aliases in first logical registration order`() {
        val registry = HardwareRegistry()
        val events = mutableListOf<String>()
        val old = Probe("old", events)
        registry.registerDevice("one", old)
        registry.registerDevice("two", old)
        registry.registerDevice("three", Probe("third", events))
        registry.registerDevice("one", Probe("new", events))
        try {
            registry.refreshAll()
            assertEquals(listOf("refresh:new", "refresh:old", "refresh:third"), events)
            events.clear()
            registry.registerDevice("two", object : LoggableDevice {})
            registry.refreshAll()
            assertEquals(listOf("refresh:new", "refresh:third"), events)
        } finally { registry.closeAll() }
    }

    @Test
    fun `equal but distinct hardware objects retain independent lifecycle calls`() {
        val registry = HardwareRegistry()
        var reads = 0
        var stops = 0
        class EqualDevice : SubsystemIO {
            override fun equals(other: Any?) = other is EqualDevice
            override fun hashCode() = 1
            override fun refresh() { reads++ }
            override fun safe() { stops++ }
        }
        registry.registerDevice("one", EqualDevice())
        registry.registerDevice("two", EqualDevice())
        try {
            registry.refreshAll()
            registry.safeAll()
            assertEquals(2, reads)
            assertEquals(2, stops)
        } finally { registry.closeAll() }
    }

    @Test
    fun `warm refresh and safety allocate no per loop collections`() {
        val registry = HardwareRegistry()
        var calls = 0L
        val probe = object : SubsystemIO {
            override fun refresh() { calls++ }
            override fun safe() { calls++ }
        }
        repeat(32) { registry.registerDevice("alias$it", probe) }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        try {
            repeat(100_000) { registry.refreshAll(); registry.safeAll() }
            calls = 0
            val before = bean.getThreadAllocatedBytes(threadId)
            repeat(10_000) { registry.refreshAll(); registry.safeAll() }
            val allocated = bean.getThreadAllocatedBytes(threadId) - before
            assertEquals(20_000L, calls)
            assertTrue(allocated <= 4096L, "10,000 lifecycle pairs allocated $allocated bytes")
            println("Registry lifecycle: $allocated bytes / 10,000 refresh+safety pairs, 32 aliases (desktop JVM)")
        } finally { registry.closeAll() }
    }

    private fun HardwareRegistry.safeAllFailure(expected: Throwable) {
        assertSame(expected, assertThrows(Throwable::class.java) { safeAll() })
    }

    private class Probe(
        val name: String,
        val events: MutableList<String>,
        var safeFailure: Throwable? = null,
        var closeFailure: Throwable? = null
    ) : SubsystemIO, AutoCloseable {
        val prefixes = mutableListOf<String>()
        override fun refresh() { events.add("refresh:$name") }
        override fun safe() { events.add("safe:$name"); safeFailure?.let { throw it } }
        override fun close() { events.add("close:$name"); closeFailure?.let { throw it } }
        override fun logTelemetry(telemetry: ITelemetry, prefix: String) { prefixes.add(prefix) }
    }

    private class EmptyTelemetry : ITelemetry {
        override fun putNumber(key: String, value: Double) = Unit
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putString(key: String, value: String) = Unit
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
    }
}
