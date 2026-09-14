package org.aresfirst.starter.frc

import com.areslib.Store
import com.areslib.hardware.SubsystemIO
import com.areslib.state.RobotState
import com.areslib.subsystem.Subsystem
import com.areslib.telemetry.ITelemetry
import com.areslib.tuning.TuningApplyContext
import com.areslib.tuning.TypedTuningConsumer
import com.areslib.tuning.TuningValue
import com.areslib.tuning.TuningTopics
import com.areslib.tuning.TypedTuningRuntime
import org.aresfirst.starter.frc.generated.drivebase.GeneratedAresTuningConfig
import com.areslib.util.RobotClock
import edu.wpi.first.hal.HAL
import edu.wpi.first.wpilibj.simulation.DriverStationSim
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StarterRuntimeLifecycleAuditTest {
    private class Telemetry : ITelemetry {
        val values = HashMap<String, Any>()
        var flushFailure: Throwable? = null
        var writeFailure: Throwable? = null
        var closes = 0
        override fun putNumber(key: String, value: Double) { writeFailure?.let { throw it }; values[key] = value }
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun putString(key: String, value: String) { values[key] = value }
        override fun putDoubleArray(key: String, value: DoubleArray) { values[key] = value.copyOf() }
        override fun getNumber(key: String, defaultValue: Double) = values[key] as? Double ?: defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun update() { flushFailure?.let { throw it } }
        override fun close() { closes++ }
    }

    private open class Mechanism : Subsystem {
        val scales = ArrayList<Double>()
        var reads = 0
        var closes = 0
        var readFailure: Throwable? = null
        var outputFailure: Throwable? = null
        var neutralFailure: Throwable? = null
        var closeFailure: Throwable? = null
        override fun readSensors(store: Store, timestampMs: Long) {
            reads++
            readFailure?.let { throw it }
        }
        override fun writeOutputs(state: RobotState, scale: Double) {
            scales += scale
            (if (scale == 0.0) neutralFailure else outputFailure)?.let { throw it }
        }
        override fun close() { closes++; closeFailure?.let { throw it } }
    }

    private inline fun withRuntime(block: (StarterRobotRuntime, Telemetry) -> Unit) {
        assertTrue(HAL.initialize(500, 0))
        DriverStationSim.resetData()
        DriverStationSim.setDsAttached(true)
        DriverStationSim.setEnabled(true)
        DriverStationSim.notifyNewData()
        val wasMocked = RobotClock.isMocked
        val before = RobotClock.currentTimeMillis()
        RobotClock.useMockTime(1000L)
        val telemetry = Telemetry()
        val runtime = StarterRobotRuntime(telemetry = telemetry,
            tuningContextProvider = { TuningApplyContext(sessionArmed = true, robotDisabled = true) })
        try { block(runtime, telemetry) } finally {
            try { runtime.close() } finally {
                DriverStationSim.resetData()
                DriverStationSim.notifyNewData()
                if (wasMocked) RobotClock.useMockTime(before) else RobotClock.useSystemTime()
            }
        }
    }

    @Test fun `sensor failure neutralizes every mechanism and latches against automatic resume`() = withRuntime { runtime, _ ->
        val first = Mechanism()
        val second = Mechanism()
        runtime.registerSubsystem(first)
        runtime.registerSubsystem(second)
        runtime.update()
        assertEquals(1.0, first.scales.last())
        val failure = IllegalStateException("sensor failure")
        first.readFailure = failure
        assertSame(failure, assertThrows(IllegalStateException::class.java) { runtime.update() })
        assertEquals(0.0, first.scales.last())
        assertEquals(0.0, second.scales.last())
        first.readFailure = null
        val reads = first.reads
        assertSame(failure, assertThrows(IllegalStateException::class.java) { runtime.update() })
        assertEquals(reads, first.reads)
        assertEquals(0.0, second.scales.last())
    }

    @Test fun `output failure neutralizes earlier and later mechanisms before propagating`() = withRuntime { runtime, _ ->
        val first = Mechanism()
        val broken = Mechanism()
        val last = Mechanism()
        listOf(first, broken, last).forEach(runtime::registerSubsystem)
        val failure = IllegalArgumentException("actuator write failure")
        broken.outputFailure = failure
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { runtime.update() })
        assertEquals(0.0, first.scales.last())
        assertEquals(0.0, broken.scales.last())
        assertEquals(listOf(0.0), last.scales)
    }

    @Test fun `registry refresh failure still neutralizes mechanisms and raw devices`() = withRuntime { runtime, _ ->
        val mechanism = Mechanism()
        runtime.registerSubsystem(mechanism)
        val failure = IllegalStateException("refresh failed")
        var safes = 0
        val device = object : SubsystemIO {
            override fun refresh() { throw failure }
            override fun safe() { safes++ }
        }
        runtime.hardwareRegistry.registerDevice("raw", device)
        assertSame(failure, assertThrows(IllegalStateException::class.java) { runtime.update() })
        assertEquals(listOf(0.0), mechanism.scales)
        assertEquals(1, safes)
    }

    @Test fun `telemetry failure after output writes also neutralizes the runtime`() = withRuntime { runtime, telemetry ->
        val mechanism = Mechanism()
        runtime.registerSubsystem(mechanism)
        telemetry.flushFailure = IllegalStateException("transport failed")
        assertThrows(IllegalStateException::class.java) { runtime.update() }
        assertEquals(listOf(1.0, 0.0), mechanism.scales)
    }

    @Test fun `neutral failure is observable and cannot skip later neutral outputs`() = withRuntime { runtime, _ ->
        val first = Mechanism()
        val second = Mechanism()
        runtime.registerSubsystem(first)
        runtime.registerSubsystem(second)
        val failure = IllegalStateException("neutral rejected")
        first.neutralFailure = failure
        try {
            assertSame(failure, assertThrows(IllegalStateException::class.java) { runtime.safeHardware() })
            assertEquals(listOf(0.0), second.scales)
        } finally { first.neutralFailure = null }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { runtime.update() })
        assertEquals(listOf(0.0, 0.0), second.scales)
    }

    @Test fun `repeated cleanup exception cannot skip raw registry or telemetry cleanup`() = withRuntime { runtime, telemetry ->
        val failure = IllegalStateException("same failure")
        val first = Mechanism().also { it.closeFailure = failure }
        val second = Mechanism().also { it.closeFailure = failure }
        runtime.registerSubsystem(first)
        runtime.registerSubsystem(second)
        var registryCloses = 0
        runtime.hardwareRegistry.registerCloseable { registryCloses++ }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { runtime.close() })
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
        assertEquals(1, registryCloses)
        assertEquals(1, telemetry.closes)
        assertEquals(0, failure.suppressed.size)
        assertDoesNotThrow { runtime.close() }
    }

    @Test fun `closed runtime rejects update tuning and topology without reentering owned resources`() = withRuntime { runtime, telemetry ->
        val mechanism = Mechanism()
        runtime.registerSubsystem(mechanism)
        runtime.close()
        val writes = mechanism.scales.size
        val telemetrySize = telemetry.values.size
        assertThrows(IllegalStateException::class.java) { runtime.update() }
        assertThrows(IllegalStateException::class.java) { runtime.updateTuningForTest(2000L) }
        assertThrows(IllegalStateException::class.java) { runtime.publishHardwareTopology("late") }
        assertEquals(0, mechanism.reads)
        assertEquals(writes, mechanism.scales.size)
        assertEquals(telemetrySize, telemetry.values.size)
    }

    @Test fun `same subsystem identity cannot be registered for duplicate reads and closes`() = withRuntime { runtime, _ ->
        val mechanism = Mechanism()
        runtime.registerSubsystem(mechanism)
        assertThrows(IllegalArgumentException::class.java) { runtime.registerSubsystem(mechanism) }
        runtime.update()
        assertEquals(1, mechanism.reads)
        runtime.close()
        assertEquals(1, mechanism.closes)
    }

    @Test fun `failed canonical tuning registration cannot later participate in enabled output`() = withRuntime { runtime, _ ->
        val mechanism = object : Mechanism(), TypedTuningConsumer {
            override fun supportsTuningParameter(parameterUid: String) = parameterUid == "frc.starter.path.velocity-scale"
            override fun applyTuningParameter(parameterUid: String, value: TuningValue) = false
        }
        assertThrows(IllegalStateException::class.java) { runtime.registerSubsystem(mechanism) }
        assertThrows(IllegalStateException::class.java) { runtime.update() }
        assertTrue(mechanism.scales.isNotEmpty())
        assertTrue(mechanism.scales.all { it == 0.0 })
    }

    @Test fun `first loop has no measured period and subsequent long stalls are not clipped`() = withRuntime { runtime, telemetry ->
        runtime.update()
        assertTrue(telemetry.getNumber("Robot/LoopTimeMs", 0.0).isNaN())
        RobotClock.useMockTime(1250L)
        runtime.update()
        assertEquals(250.0, telemetry.getNumber("Robot/LoopTimeMs", -1.0))
        assertEquals(4.0, telemetry.getNumber("Profiling/Hz", -1.0))
    }

    @Test fun `zero timestamp is a valid first observation rather than an initialization sentinel`() = withRuntime { runtime, telemetry ->
        RobotClock.useMockTime(0L)
        runtime.update()
        RobotClock.useMockTime(1L)
        runtime.update()
        assertEquals(1.0, telemetry.getNumber("Robot/LoopTimeMs", -1.0))
    }

    @Test fun `signed clock wrap does not publish a fictitious positive loop frequency`() = withRuntime { runtime, telemetry ->
        RobotClock.useMockTime(Long.MAX_VALUE - 2L)
        runtime.update()
        RobotClock.useMockTime(Long.MIN_VALUE + 2L)
        runtime.update()
        assertTrue(telemetry.getNumber("Robot/LoopTimeMs", 0.0).isNaN())
        assertTrue(telemetry.getNumber("Profiling/Hz", 0.0).isNaN())
    }

    @Test fun `additional declared tuning is safely unsupported before any consumer registers`() {
        val canonical = GeneratedAresTuningConfig.createRuntime()
        val additional = canonical.metadata.declarations.first().copy(uid = "frc.starter.extra", key = "drive.extra")
        val declarations = canonical.metadata.declarations + additional
        val tuning = TypedTuningRuntime(declarations,
            declarations.associate { it.uid to (canonical.canonicalValue(it.uid) ?: it.defaultValue) },
            canonical.metadata.copy(declarations = declarations))
        val telemetry = Telemetry()
        val runtime = assertDoesNotThrow<StarterRobotRuntime> { StarterRobotRuntime(telemetry = telemetry, tuningRuntime = tuning) }
        try {
            assertFalse(telemetry.getBoolean("${TuningTopics.ROOT}/Parameters/frc.starter.extra/ConsumerSupported", true))
        } finally { runtime.close() }
    }

    @Test fun `failed initial metadata publication releases the transferred telemetry owner`() {
        val failure = IllegalStateException("initial publication failed")
        val telemetry = Telemetry().also { it.writeFailure = failure }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { StarterRobotRuntime(telemetry = telemetry) })
        assertEquals(1, telemetry.closes)
    }

    @Test fun `invalid required tuning bindings release telemetry before manager construction`() {
        val canonical = GeneratedAresTuningConfig.createRuntime()
        val declarations = canonical.metadata.declarations.filterNot { it.key == "drive.pathVelocityScale" }
        val tuning = TypedTuningRuntime(declarations,
            declarations.associate { it.uid to requireNotNull(canonical.canonicalValue(it.uid)) },
            canonical.metadata.copy(declarations = declarations))
        val telemetry = Telemetry()
        assertThrows(IllegalArgumentException::class.java) { StarterRobotRuntime(telemetry = telemetry, tuningRuntime = tuning) }
        assertEquals(1, telemetry.closes)
    }

    @Test fun `failure cleanup retains direct interruption and every later failure without skipping owners`() = withRuntime { runtime, _ ->
        val first = Mechanism()
        val second = Mechanism()
        runtime.registerSubsystem(first)
        runtime.registerSubsystem(second)
        val primary = IllegalStateException("read failed")
        val interrupted = InterruptedException("neutral interrupted")
        val fatal = AssertionError("later neutral failed")
        first.readFailure = primary
        first.neutralFailure = interrupted
        second.neutralFailure = fatal
        val wasInterrupted = Thread.interrupted()
        try {
            assertSame(primary, assertThrows(IllegalStateException::class.java) { runtime.update() })
            assertArrayEquals(arrayOf(interrupted, fatal), primary.suppressed)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(listOf(0.0), second.scales)
            // A retry attempts neutral again but must not accumulate duplicate suppressed identities.
            assertSame(primary, assertThrows(IllegalStateException::class.java) { runtime.update() })
            assertArrayEquals(arrayOf(interrupted, fatal), primary.suppressed)
        } finally {
            first.neutralFailure = null
            second.neutralFailure = null
            Thread.interrupted()
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test fun `close retains interruption while finishing device manager and telemetry teardown`() = withRuntime { runtime, telemetry ->
        val interrupted = InterruptedException("close interrupted")
        val first = Mechanism()
        val second = Mechanism().also { it.closeFailure = interrupted }
        runtime.registerSubsystem(first)
        runtime.registerSubsystem(second)
        val wasInterrupted = Thread.interrupted()
        try {
            assertSame(interrupted, assertThrows(InterruptedException::class.java) { runtime.close() })
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, first.closes)
            assertEquals(1, second.closes)
            assertEquals(1, telemetry.closes)
        } finally {
            Thread.interrupted()
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test fun `healthy disabled loops read once per owner and write only neutral`() = withRuntime { runtime, _ ->
        DriverStationSim.setEnabled(false)
        DriverStationSim.notifyNewData()
        var refreshes = 0
        runtime.hardwareRegistry.registerDevice("cached", object : SubsystemIO {
            override fun refresh() { refreshes++ }
        })
        val first = Mechanism()
        val second = Mechanism()
        runtime.registerSubsystem(first)
        runtime.registerSubsystem(second)
        repeat(3) { RobotClock.useMockTime(1000L + it * 20L); runtime.update() }
        assertEquals(3, refreshes)
        assertEquals(3, first.reads)
        assertEquals(3, second.reads)
        assertEquals(listOf(0.0, 0.0, 0.0), first.scales)
        assertEquals(listOf(0.0, 0.0, 0.0), second.scales)
        runtime.close()
        val writes = first.scales.size
        assertDoesNotThrow { runtime.safeHardware() }
        assertEquals(writes, first.scales.size)
    }

    @Test fun `subsystem tuning is acknowledged only while exactly one live consumer owns the parameter`() {
        val canonical = GeneratedAresTuningConfig.createRuntime()
        val additional = canonical.metadata.declarations.first().copy(uid = "frc.starter.extra", key = "drive.extra")
        val declarations = canonical.metadata.declarations + additional
        val tuning = TypedTuningRuntime(declarations,
            declarations.associate { it.uid to (canonical.canonicalValue(it.uid) ?: it.defaultValue) },
            canonical.metadata.copy(declarations = declarations))
        val telemetry = Telemetry()
        val runtime = StarterRobotRuntime(telemetry = telemetry, tuningRuntime = tuning,
            tuningContextProvider = { TuningApplyContext(sessionArmed = true, robotDisabled = true) })
        class Consumer : Mechanism(), TypedTuningConsumer {
            var value = -1.0
            override fun supportsTuningParameter(parameterUid: String) = parameterUid == additional.uid
            override fun applyTuningParameter(parameterUid: String, value: TuningValue): Boolean {
                this.value = requireNotNull(value.doubleValue)
                return true
            }
        }
        try {
            val first = Consumer()
            runtime.registerSubsystem(first)
            assertEquals(2.0, first.value)
            val root = "${TuningTopics.ROOT}/Parameters/${additional.uid}"
            assertTrue(telemetry.getBoolean("$root/ConsumerSupported", false))
            telemetry.putNumber("$root/Requested", 3.0)
            telemetry.putNumber("$root/RequestNonce", 1.0)
            runtime.updateTuningForTest(1000L)
            assertEquals(3.0, first.value)
            assertEquals("APPLIED", telemetry.getString("$root/LastResult", ""))
            val second = Consumer()
            runtime.registerSubsystem(second)
            assertFalse(telemetry.getBoolean("$root/ConsumerSupported", true))
            telemetry.putNumber("$root/Requested", 4.0)
            telemetry.putNumber("$root/RequestNonce", 2.0)
            runtime.updateTuningForTest(2000L)
            assertEquals(3.0, first.value)
            assertEquals(3.0, second.value)
            assertEquals("CONSUMER_REJECTED", telemetry.getString("$root/LastResult", ""))
        } finally { runtime.close() }
    }
}
