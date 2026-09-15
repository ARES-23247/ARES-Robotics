package com.areslib.hardware

import com.areslib.hardware.actuator.MotorIO
import com.areslib.telemetry.ITelemetry
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HardwareRegistryPublicationPollingAuditTest {
    @Test
    fun `heartbeat remains distinguishable at the double integer precision boundary`() {
        val registry = HardwareRegistry()
        val publisher = HardwareRegistry::class.java.getDeclaredField("telemetryPublisher")
            .apply { isAccessible = true }.get(registry)
        val sequence = HardwareTelemetryPublisher::class.java.getDeclaredField("telemetryPublishSequence")
            .apply { isAccessible = true }.get(publisher) as AtomicLong
        val sink = RecordingTelemetry()
        registry.registerTelemetryDevice("Subsystems/arm", object : LoggableDevice {})
        try {
            // Seed a real counter; reaching this boundary by waiting would take millions of years.
            for (seed in longArrayOf((1L shl 53) - 2, 1L shl 53, Long.MAX_VALUE, -1L)) {
                sequence.set(seed)
                sink.numbers.clear()
                repeat(4) { registry.publishAll(sink) }
                val values = sink.numbers.map { it.second }
                assertEquals(4, values.distinct().size, "A live producer must change its heartbeat each pass")
                assertTrue(values.all { it.isFinite() && it > 0.0 })
                if (seed == (1L shl 53) - 2) {
                    assertEquals(listOf(9_007_199_254_740_991.0, 1.0, 2.0, 3.0), values)
                }
            }
            registry.closeAll()
            registry.registerTelemetryDevice("Subsystems/arm", object : LoggableDevice {})
            sink.numbers.clear()
            registry.publishAll(sink)
            assertEquals(listOf("Subsystems/arm/TelemetryHeartbeat" to 1.0), sink.numbers)
        } finally { registry.closeAll() }
    }

    @Test
    fun `polling failure count saturates and resets after a successful read`() {
        val registry = HardwareRegistry()
        val entered = List(4) { CountDownLatch(1) }
        val release = List(4) { CountDownLatch(1) }
        var calls = 0
        val device = object : SyncPolledDevice {
            override fun pollSync() {
                val step = (calls++).coerceAtMost(3)
                entered[step].countDown()
                release[step].await()
                if (step < 2) error("offline")
            }
        }
        try {
            registry.setPollingIntervalMs(10L)
            registry.registerSyncPolledDevice(device)
            assertTrue(entered[0].await(2, TimeUnit.SECONDS))
            val entries = HardwareRegistry::class.java.getDeclaredField("pollingEntriesByIdentity")
                .apply { isAccessible = true }.get(registry) as Map<*, *>
            val entry = entries[device]!!
            val failures = entry.javaClass.getDeclaredField("consecutiveFailures").apply { isAccessible = true }
            // The worker is blocked; the latch release publishes this boundary seed to it.
            failures.setLong(entry, Long.MAX_VALUE - 1L)
            release[0].countDown()
            assertTrue(entered[1].await(2, TimeUnit.SECONDS))
            assertEquals(Long.MAX_VALUE, failures.getLong(entry))
            release[1].countDown()
            assertTrue(entered[2].await(2, TimeUnit.SECONDS))
            assertEquals(Long.MAX_VALUE, failures.getLong(entry), "Further failures must not overflow")
            release[2].countDown()
            assertTrue(entered[3].await(2, TimeUnit.SECONDS))
            assertEquals(0L, failures.getLong(entry), "A successful read starts a fresh failure streak")
        } finally {
            release.forEach { it.countDown() }
            registry.closeAll()
        }
    }

    @Test
    fun `concurrent registration preserves the publishers captured device frame`() {
        val registry = HardwareRegistry()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val values = mutableListOf<String>()
        val failure = AtomicReference<Throwable>()
        registry.registerDevice("first", object : LoggableDevice {
            override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                values.add("first")
            }
        })
        registry.registerDevice("second", object : LoggableDevice {
            override fun logTelemetry(telemetry: ITelemetry, prefix: String) { values.add("old") }
        })
        val worker = Thread {
            try { registry.publishAll(RecordingTelemetry()) } catch (error: Throwable) { failure.set(error) }
        }
        try {
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            registry.registerDevice("second", object : LoggableDevice {
                override fun logTelemetry(telemetry: ITelemetry, prefix: String) { values.add("new") }
            })
            release.countDown()
            worker.join(2000L)
            assertFalse(worker.isAlive)
            assertNull(failure.get())
            assertEquals(listOf("first", "old"), values)
            values.clear()
            registry.publishAll(RecordingTelemetry())
            assertEquals(listOf("first", "new"), values)
        } finally {
            release.countDown()
            worker.join(2000L)
            registry.closeAll()
            assertFalse(worker.isAlive, "Test publisher must terminate")
        }
    }

    @Test
    fun `device telemetry failures cannot skip healthy later devices or invent heartbeats`() {
        for (failure in listOf(IllegalStateException("offline"), IndexOutOfBoundsException("device"), AssertionError("device"), LinkageError("device"))) {
            val registry = HardwareRegistry()
            val sink = RecordingTelemetry()
            var healthyCalls = 0
            registry.registerTelemetryDevice("Subsystems/bad", object : LoggableDevice {
                override fun logTelemetry(telemetry: ITelemetry, prefix: String) { throw failure }
            })
            registry.registerTelemetryDevice("Subsystems/good", object : LoggableDevice {
                override fun logTelemetry(telemetry: ITelemetry, prefix: String) { healthyCalls++ }
            })
            try {
                registry.publishAll(sink)
                assertEquals(1, healthyCalls, failure.toString())
                assertEquals(listOf("Subsystems/good/TelemetryHeartbeat" to 1.0), sink.numbers)
            } finally { registry.closeAll() }
        }
    }

    @Test
    fun `heartbeat sink failure cannot suppress the next device`() {
        val registry = HardwareRegistry()
        val calls = mutableListOf<String>()
        val sink = RecordingTelemetry().apply {
            onNumber = { key -> if (key == "Subsystems/one/TelemetryHeartbeat") error("sink") }
        }
        for (name in listOf("one", "two")) registry.registerTelemetryDevice("Subsystems/$name", object : LoggableDevice {
            override fun logTelemetry(telemetry: ITelemetry, prefix: String) { calls.add(name) }
        })
        try {
            registry.publishAll(sink)
            assertEquals(listOf("one", "two"), calls)
            assertEquals(listOf("Subsystems/two/TelemetryHeartbeat" to 1.0), sink.numbers)
        } finally { registry.closeAll() }
    }

    @Test
    fun `reentrant replacement cannot attach a new heartbeat to the old device frame`() {
        val registry = HardwareRegistry()
        val sink = RecordingTelemetry()
        val replacement = object : LoggableDevice {
            override fun logTelemetry(telemetry: ITelemetry, prefix: String) { telemetry.putNumber("$prefix/Value", 2.0) }
        }
        registry.registerDevice("Subsystems/arm", object : LoggableDevice {
            override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
                telemetry.putNumber("$prefix/Value", 1.0)
                registry.registerTelemetryDevice("Subsystems/arm", replacement)
            }
        })
        try {
            registry.publishAll(sink)
            assertEquals(listOf("Hardware/Subsystems/arm/Value" to 1.0), sink.numbers)
            sink.numbers.clear()
            registry.publishAll(sink)
            assertEquals(listOf("Subsystems/arm/Value" to 2.0, "Subsystems/arm/TelemetryHeartbeat" to 2.0), sink.numbers)
        } finally { registry.closeAll() }
    }

    @Test
    fun `registration changes take effect on the next complete telemetry pass`() {
        val registry = HardwareRegistry()
        val calls = mutableListOf<String>()
        fun device(label: String) = object : LoggableDevice {
            override fun logTelemetry(telemetry: ITelemetry, prefix: String) { calls.add(label) }
        }
        var changed = false
        registry.registerDevice("first", object : LoggableDevice {
            override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
                calls.add("first")
                if (!changed) {
                    changed = true
                    registry.registerDevice("second", device("new"))
                    registry.registerDevice("third", device("added"))
                }
            }
        })
        registry.registerDevice("second", device("old"))
        try {
            registry.publishAll(RecordingTelemetry())
            assertEquals(listOf("first", "old"), calls)
            calls.clear()
            registry.publishAll(RecordingTelemetry())
            assertEquals(listOf("first", "new", "added"), calls)
        } finally { registry.closeAll() }
    }

    @Test
    fun `blocked regular poll cannot enter the next generations round robin lane`() {
        val registry = HardwareRegistry()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldWorker = AtomicReference<Thread>()
        val replacementObserved = CountDownLatch(1)
        val wrongWorkerCalls = AtomicInteger()
        val blocking = object : SyncPolledDevice {
            override fun pollSync() {
                oldWorker.set(Thread.currentThread())
                entered.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { /* Model uninterruptible vendor IO. */ }
                }
            }
        }
        try {
            registry.setPollingIntervalMs(10L)
            registry.registerSyncPolledDevice(blocking)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            registry.closeAll()
            registry.registerRoundRobinDevice(object : SyncPolledDevice {
                override fun pollSync() {
                    if (Thread.currentThread() === oldWorker.get()) wrongWorkerCalls.incrementAndGet()
                    else replacementObserved.countDown()
                }
            })
            assertTrue(replacementObserved.await(2, TimeUnit.SECONDS))
            release.countDown()
            oldWorker.get().join(2000L)
            assertFalse(oldWorker.get().isAlive)
            assertEquals(0, wrongWorkerCalls.get(), "Retired worker touched replacement hardware")
        } finally {
            release.countDown()
            oldWorker.get()?.join(2000L)
            registry.closeAll()
            assertTrue(oldWorker.get()?.isAlive != true, "Test must release its old worker")
        }
    }

    @Test
    fun `equal but distinct polling devices both receive reads in either lane`() {
        for (regular in listOf(true, false)) {
            val registry = HardwareRegistry()
            class EqualPolledDevice : SyncPolledDevice {
                val observed = CountDownLatch(1)
                override fun equals(other: Any?) = other is EqualPolledDevice
                override fun hashCode() = 1
                override fun pollSync() { observed.countDown() }
            }
            val first = EqualPolledDevice()
            val second = EqualPolledDevice()
            try {
                registry.setPollingIntervalMs(10L)
                if (regular) {
                    registry.registerSyncPolledDevice(first)
                    registry.registerSyncPolledDevice(first)
                    registry.registerSyncPolledDevice(second)
                } else {
                    registry.registerRoundRobinDevice(first)
                    registry.registerRoundRobinDevice(first)
                    registry.registerRoundRobinDevice(second)
                }
                assertTrue(first.observed.await(2, TimeUnit.SECONDS))
                assertTrue(second.observed.await(2, TimeUnit.SECONDS), "Equal hardware must retain separate ownership")
            } finally { registry.closeAll() }
        }
    }

    @Test
    fun `equal but distinct auxiliary resources close once each`() {
        val registry = HardwareRegistry()
        class EqualResource : AutoCloseable {
            var closes = 0
            override fun equals(other: Any?) = other is EqualResource
            override fun hashCode() = 1
            override fun close() { closes++ }
        }
        val first = EqualResource()
        val second = EqualResource()
        registry.registerCloseable(first)
        registry.registerCloseable(first)
        registry.registerCloseable(second)
        registry.closeAll()
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
    }

    @Test
    fun `equal motors retain independent power feedback and identity removal`() {
        val registry = HardwareRegistry()
        val first = EqualMotor(1.0)
        val second = EqualMotor(2.0)
        val motors = registry.getRegisteredMotors()
        val currents = registry.getRegisteredCurrentSources()
        try {
            registry.registerMotor("one", first)
            registry.registerMotor("two", second)
            assertEquals(2, motors.size)
            assertEquals(3.0, CurrentSourceSampler().sample(currents), 0.0)
            registry.registerDevice("Motors/one", object : LoggableDevice {})
            assertSame(second, motors.single())
            assertSame(second, currents.single())
            assertSame(second, registry.getRegisteredMotorsWithNames()["two"])
            registry.clear()
            assertTrue(motors.isEmpty())
            assertTrue(currents.isEmpty())
        } finally { registry.closeAll() }
    }

    @Test
    fun `removing a short name collision restores the surviving named motor`() {
        val registry = HardwareRegistry()
        val first = EqualMotor(1.0)
        val second = object : MotorIO {
            override val position = 0.0
            override val velocity = 0.0
            override var power = 0.0
            override fun resetEncoder() = Unit
        }
        try {
            registry.registerMotor("arm", first)
            registry.registerDevice("arm", second)
            assertSame(second, registry.getRegisteredMotorsWithNames()["arm"])
            registry.registerDevice("arm", object : LoggableDevice {})
            assertSame(first, registry.getRegisteredMotorsWithNames()["arm"])
            assertSame(first, registry.getRegisteredMotors().single())
        } finally { registry.closeAll() }
    }

    @Test
    fun `warm publication retains alias topics without per pass collection allocation`() {
        val registry = HardwareRegistry()
        var calls = 0L
        var beats = 0L
        val device = object : LoggableDevice {
            override fun logTelemetry(telemetry: ITelemetry, prefix: String) { calls++ }
        }
        val sink = object : EmptyTelemetry() {
            override fun putNumber(key: String, value: Double) { beats++ }
        }
        repeat(32) { registry.registerTelemetryDevice("Subsystems/alias$it", device) }
        try {
            repeat(100_000) { registry.publishAll(sink) }
            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            bean.isThreadAllocatedMemoryEnabled = true
            val threadId = Thread.currentThread().id
            val before = bean.getThreadAllocatedBytes(threadId)
            repeat(10_000) { registry.publishAll(sink) }
            val allocated = bean.getThreadAllocatedBytes(threadId) - before
            assertEquals(3_520_000L, calls)
            assertEquals(calls, beats)
            println("Registry publication: $allocated bytes / 10,000 passes, 32 aliases (desktop JVM)")
            assertTrue(allocated <= 4096L, "Warm publication allocated $allocated bytes")
        } finally { registry.closeAll() }
    }

    private class EqualMotor(override val currentAmps: Double) : MotorIO {
        override val position = 0.0
        override val velocity = 0.0
        override var power = 0.0
        override fun resetEncoder() = Unit
        override fun equals(other: Any?) = other is EqualMotor
        override fun hashCode() = 1
    }
    private class RecordingTelemetry : EmptyTelemetry() {
        val numbers = mutableListOf<Pair<String, Double>>()
        var onNumber: ((String) -> Unit)? = null
        override fun putNumber(key: String, value: Double) { onNumber?.invoke(key); numbers.add(key to value) }
    }
    private open class EmptyTelemetry : ITelemetry {
        override fun putNumber(key: String, value: Double) = Unit
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putString(key: String, value: String) = Unit
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
    }
}
