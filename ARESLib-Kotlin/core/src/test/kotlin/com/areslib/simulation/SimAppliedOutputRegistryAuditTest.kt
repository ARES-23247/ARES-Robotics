package com.areslib.simulation

import java.lang.ref.WeakReference
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SimAppliedOutputRegistryAuditTest {
    @Test
    fun `opaque identifier pairs cannot alias through separators`() {
        val first = SimAppliedOutputRegistry.register("audit/pair", "motor")
        val second = SimAppliedOutputRegistry.register("audit", "pair/motor")
        assertNotSame(first, second)
        first.publish(3.0)
        second.publish(-2.0)
        assertEquals(3.0, first.value)
        assertEquals(-2.0, second.value)
        assertSame(first, SimAppliedOutputRegistry.find("audit/pair", "motor"))
        assertSame(second, SimAppliedOutputRegistry.find("audit", "pair/motor"))
    }

    @Test
    fun `reset preserves a model binding created before robot IO`() {
        val modelBinding = SimAppliedOutputRegistry.register("audit-model-first", "motor")
        modelBinding.publish(4.0)
        SimAppliedOutputRegistry.reset()
        assertEquals(0.0, modelBinding.value)
        val robotOutput = SimAppliedOutputRegistry.register("audit-model-first", "motor")
        assertSame(modelBinding, robotOutput)
        robotOutput.publish(-6.0)
        assertEquals(-6.0, modelBinding.value)
    }

    @Test
    fun `finite signed physical outputs survive and nonfinite outputs neutralize`() {
        val signal = SimAppliedOutputRegistry.register("audit-numeric", "motor")
        for (value in doubleArrayOf(12.0, -12.0, 0.0, -0.0, Double.MIN_VALUE, Double.MAX_VALUE)) {
            signal.publish(value)
            assertEquals(value.toRawBits(), signal.value.toRawBits())
        }
        for (value in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            signal.publish(value)
            assertEquals(0.0, signal.value)
        }
    }

    @Test
    fun `concurrent registration shares one live actuator signal`() {
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 8).map {
                workers.submit<SimAppliedOutputSignal> {
                    check(start.await(5, TimeUnit.SECONDS))
                    SimAppliedOutputRegistry.register("audit-concurrent", "motor")
                }
            }
            start.countDown()
            val signals = futures.map { it.get(5, TimeUnit.SECONDS) }
            signals.forEach { assertSame(signals.first(), it) }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `registry does not own abandoned model or robot signals forever`() {
        val abandoned = registerUnownedSignal()
        // A helper frame has returned, so the observer below is the only application reference.
        // The registry may retain a weak lookup but must not be the signal's lifetime owner.
        for (attempt in 0 until 30) {
            System.gc()
            if (abandoned.get() == null) break
            Thread.sleep(20)
        }
        assertNull(abandoned.get(), "An unused signal is retained by the process-wide registry")
        SimAppliedOutputRegistry.reset()
        assertNull(SimAppliedOutputRegistry.find("audit-unowned", "motor"))
        val replacement = SimAppliedOutputRegistry.register("audit-unowned", "motor")
        assertEquals(0.0, replacement.value)
        replacement.publish(2.0)
        assertSame(replacement, SimAppliedOutputRegistry.find("audit-unowned", "motor"))
    }

    @Test
    fun `blank identifiers fail before registration`() {
        assertThrows(IllegalArgumentException::class.java) { SimAppliedOutputRegistry.register(" ", "motor") }
        assertThrows(IllegalArgumentException::class.java) { SimAppliedOutputRegistry.register("audit-blank", "\t") }
        assertNull(SimAppliedOutputRegistry.find("audit-blank", "\t"))
    }

    @Test
    fun `late collected reference cannot remove a replacement signal`() {
        val original = SimAppliedOutputRegistry.register("audit-collected", "motor")
        val map = SimAppliedOutputRegistry::class.java.getDeclaredField("signals")
            .apply { isAccessible = true }.get(SimAppliedOutputRegistry) as Map<*, *>
        // Inject the separate weak-clear and queue-notification events without GC timing races.
        val reference = map.values.filterIsInstance<WeakReference<*>>().single { it.get() === original }
        reference.clear()
        val replacement = SimAppliedOutputRegistry.register("audit-collected", "motor")
        assertNotSame(original, replacement)
        replacement.publish(7.0)
        assertTrue(reference.enqueue())
        assertSame(replacement, SimAppliedOutputRegistry.find("audit-collected", "motor"))
        assertEquals(7.0, replacement.value)
    }

    @Test
    fun `periodic publication uses primitive cached handles without allocation growth`() {
        val signal = SimAppliedOutputRegistry.register("audit-allocation", "motor")
        repeat(100_000) { signal.publish(if (it and 1 == 0) 6.0 else -6.0) }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(10_000) { signal.publish(if (it and 1 == 0) 6.0 else -6.0) }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertEquals(-6.0, signal.value)
        println("Sim signal publication: $allocated bytes / 10,000 warmed writes (desktop JVM)")
        assertTrue(allocated <= 4096L, "Periodic signal writes allocated $allocated bytes")
    }

    private fun registerUnownedSignal(): WeakReference<SimAppliedOutputSignal> =
        WeakReference(SimAppliedOutputRegistry.register("audit-unowned", "motor"))
}
