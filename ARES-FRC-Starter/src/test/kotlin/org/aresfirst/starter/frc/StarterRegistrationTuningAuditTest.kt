package org.aresfirst.starter.frc

import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.subsystem.Subsystem
import com.areslib.telemetry.ITelemetry
import com.areslib.tuning.*
import edu.wpi.first.hal.HAL
import edu.wpi.first.wpilibj.simulation.DriverStationSim
import org.aresfirst.starter.frc.generated.drivebase.GeneratedAresTuningConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StarterRegistrationTuningAuditTest {
    private class Telemetry : ITelemetry {
        val values = HashMap<String, Any>()
        override fun putNumber(key: String, value: Double) { values[key] = value }
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun putString(key: String, value: String) { values[key] = value }
        override fun putDoubleArray(key: String, value: DoubleArray) { values[key] = value.copyOf() }
        override fun getNumber(key: String, defaultValue: Double) = values[key] as? Double ?: defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
    }

    private open class Mechanism : Subsystem {
        val scales = ArrayList<Double>()
        var reads = 0
        var closes = 0
        override fun readSensors(store: Store, timestampMs: Long) { reads++ }
        override fun writeOutputs(state: RobotState, scale: Double) { scales += scale }
        override fun close() { closes++ }
    }

    private class Consumer(val uid: String, var reject: Boolean = false) : Mechanism(), TypedTuningConsumer {
        var value = -1.0
        var failure: Exception? = null
        override fun supportsTuningParameter(parameterUid: String) = parameterUid == uid
        override fun applyTuningParameter(parameterUid: String, value: TuningValue): Boolean {
            failure?.let { throw it }
            if (reject) return false
            this.value = requireNotNull(value.doubleValue)
            return true
        }
    }

    private inline fun withRuntime(tuning: TypedTuningRuntime? = null, block: (StarterRobotRuntime, Telemetry) -> Unit) {
        assertTrue(HAL.initialize(500, 0))
        DriverStationSim.resetData()
        DriverStationSim.setDsAttached(true)
        DriverStationSim.setEnabled(true)
        DriverStationSim.notifyNewData()
        val telemetry = Telemetry()
        val runtime = StarterRobotRuntime(telemetry,
            tuningContextProvider = { TuningApplyContext(sessionArmed = true, robotDisabled = true) },
            tuningRuntime = tuning)
        try { block(runtime, telemetry) } finally {
            try { runtime.close() } finally {
                DriverStationSim.resetData()
                DriverStationSim.notifyNewData()
            }
        }
    }

    @Test fun `failed generated registration releases untransferred tail exactly once`() = withRuntime { runtime, _ ->
        val first = Mechanism()
        val broken = Consumer("frc.starter.path.velocity-scale", reject = true)
        val tail = Mechanism()
        assertThrows(IllegalStateException::class.java) {
            installGeneratedSubsystems(false, runtime.hardwareRegistry, runtime::registerSubsystems,
                createAll = { _, _ -> listOf(first, broken, tail, tail) })
        }
        assertEquals(listOf(0.0), tail.scales)
        assertEquals(1, tail.closes)
        assertEquals(0, first.closes)
        assertEquals(0, broken.closes)
        runtime.close()
        assertEquals(1, first.closes)
        assertEquals(1, broken.closes)
        assertEquals(1, tail.closes)
    }

    @Test fun `failed superstructure registration releases untransferred tail`() = withRuntime { runtime, _ ->
        val broken = Consumer("frc.starter.path.velocity-scale", reject = true)
        val tail = Mechanism()
        assertThrows(IllegalStateException::class.java) {
            installGeneratedSuperstructures(runtime::registerSubsystems) { listOf(broken, tail) }
        }
        assertEquals(listOf(0.0), tail.scales)
        assertEquals(1, tail.closes)
        runtime.close()
        assertEquals(1, broken.closes)
        assertEquals(1, tail.closes)
    }

    @Test fun `batch offered to a closed owner is neutralized and released`() = withRuntime { runtime, _ ->
        runtime.close()
        val incoming = Mechanism()
        assertThrows(IllegalStateException::class.java) {
            installGeneratedSuperstructures(runtime::registerSubsystems) { listOf(incoming, incoming) }
        }
        assertEquals(listOf(0.0), incoming.scales)
        assertEquals(1, incoming.closes)
    }

    @Test fun `a builtin and subsystem cannot both claim one acknowledged tuning value`() = withRuntime { runtime, telemetry ->
        val uid = "frc.starter.path.velocity-scale"
        val consumer = Consumer(uid)
        runtime.registerSubsystem(consumer)
        val root = "${TuningTopics.ROOT}/Parameters/$uid"
        assertFalse(telemetry.getBoolean("$root/ConsumerSupported", true))
        telemetry.putNumber("$root/Requested", 0.7)
        telemetry.putNumber("$root/RequestNonce", 1.0)
        runtime.updateTuningForTest(1000L)
        assertEquals("CONSUMER_REJECTED", telemetry.getString("$root/LastResult", ""))
        assertEquals(0.5, runtime.store.state.tuning.drive.pathVelocityScale)
        assertEquals(0.5, consumer.value)
    }

    @Test fun `live consumer exception propagates through tuning acknowledgement into the runtime latch`() {
        val canonical = GeneratedAresTuningConfig.createRuntime()
        val additional = canonical.metadata.declarations.first().copy(uid = "frc.starter.extra", key = "drive.extra")
        val declarations = canonical.metadata.declarations + additional
        val tuning = TypedTuningRuntime(declarations,
            declarations.associate { it.uid to (canonical.canonicalValue(it.uid) ?: it.defaultValue) },
            canonical.metadata.copy(declarations = declarations))
        withRuntime(tuning) { runtime, telemetry ->
            val consumer = Consumer(additional.uid)
            runtime.registerSubsystem(consumer)
            val failure = IllegalStateException("live consumer failed")
            consumer.failure = failure
            val root = "${TuningTopics.ROOT}/Parameters/${additional.uid}"
            telemetry.putNumber("$root/Requested", 3.0)
            telemetry.putNumber("$root/RequestNonce", 1.0)
            assertSame(failure, assertThrows(IllegalStateException::class.java) { runtime.updateTuningForTest(1000L) })
            assertEquals("APPLY_CALLBACK_FAILED", telemetry.getString("$root/LastResult", ""))
            assertEquals(2.0, telemetry.getNumber("$root/Current", -1.0))
            assertEquals(1.0, telemetry.getNumber("$root/ProcessedNonce", -1.0))
            assertEquals(listOf(0.0), consumer.scales)
            consumer.failure = null
            assertSame(failure, assertThrows(IllegalStateException::class.java) { runtime.update() })
            assertEquals(listOf(0.0, 0.0), consumer.scales)
        }
    }

    @Test fun `every builtin tuning acknowledgement reaches its correct Redux coefficient`() = withRuntime { runtime, telemetry ->
        data class Binding(val suffix: String, val value: Double, val read: (RobotState) -> Double)
        val bindings = listOf(
            Binding("translation-kp", 2.3) { it.tuning.drive.pathTranslationGains.kP },
            Binding("translation-ki", 0.07) { it.tuning.drive.pathTranslationGains.kI },
            Binding("translation-kd", 0.21) { it.tuning.drive.pathTranslationGains.kD },
            Binding("rotation-kp", 4.2) { it.tuning.drive.pathRotationGains.kP },
            Binding("rotation-ki", 0.08) { it.tuning.drive.pathRotationGains.kI },
            Binding("rotation-kd", 0.31) { it.tuning.drive.pathRotationGains.kD },
            Binding("velocity-scale", 0.71) { it.tuning.drive.pathVelocityScale },
            Binding("acceleration-limit", 3.2) { it.tuning.drive.pathAccelerationLimit },
        )
        for ((index, binding) in bindings.withIndex()) {
            val root = "${TuningTopics.ROOT}/Parameters/frc.starter.path.${binding.suffix}"
            telemetry.putNumber("$root/Requested", binding.value)
            telemetry.putNumber("$root/RequestNonce", 1.0)
            runtime.updateTuningForTest(1000L + index * 500L)
            assertEquals("APPLIED", telemetry.getString("$root/LastResult", ""), binding.suffix)
            assertEquals(binding.value, binding.read(runtime.store.state), binding.suffix)
            assertEquals(binding.value, telemetry.getNumber("$root/Current", -1.0))
        }
    }

    @Test fun `successful batch transfers the whole list and retains one read per owner`() = withRuntime { runtime, _ ->
        val first = Mechanism()
        val second = Mechanism()
        val created = listOf(first, second)
        assertSame(created, installGeneratedSuperstructures(runtime::registerSubsystems) { created })
        runtime.update()
        assertEquals(1, first.reads)
        assertEquals(1, second.reads)
        assertEquals(listOf(1.0), first.scales)
        assertEquals(listOf(1.0), second.scales)
        runtime.close()
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
    }

    @Test fun `duplicate in a batch faults the prefix without double closing an existing owner`() = withRuntime { runtime, _ ->
        val first = Mechanism()
        val tail = Mechanism()
        runtime.registerSubsystem(first)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runtime.registerSubsystems(listOf(first, tail, first))
        }
        assertEquals(listOf(0.0), first.scales)
        assertEquals(0, first.closes)
        assertEquals(listOf(0.0), tail.scales)
        assertEquals(1, tail.closes)
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { runtime.update() })
        runtime.close()
        assertEquals(1, first.closes)
        assertEquals(1, tail.closes)
    }

    @Test fun `batch failure attempts all neutral writes before reverse cleanup and retains interruption`() = withRuntime { runtime, _ ->
        val wasInterrupted = Thread.interrupted()
        try {
            val events = ArrayList<String>()
            val repeated = IllegalStateException("tail output and close failed")
            val interruption = InterruptedException("tail close interrupted")
            val firstTail = object : Mechanism() {
                override fun writeOutputs(state: RobotState, scale: Double) {
                    super.writeOutputs(state, scale); events += "neutral first"; throw repeated
                }
                override fun close() { super.close(); events += "close first"; throw repeated }
            }
            val lastTail = object : Mechanism() {
                override fun writeOutputs(state: RobotState, scale: Double) {
                    super.writeOutputs(state, scale); events += "neutral last"
                }
                override fun close() { super.close(); events += "close last"; throw interruption }
            }
            val broken = Consumer("frc.starter.path.velocity-scale", reject = true)
            val failure = assertThrows(IllegalStateException::class.java) {
                runtime.registerSubsystems(listOf(broken, firstTail, lastTail, firstTail))
            }
            assertEquals(listOf("neutral first", "neutral last", "close last", "close first"), events)
            assertEquals(listOf(repeated, interruption), failure.suppressed.toList())
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, firstTail.closes)
            assertEquals(1, lastTail.closes)
        } finally {
            Thread.interrupted()
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }
}
