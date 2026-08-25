package com.areslib.tuning

import com.areslib.telemetry.ITelemetry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TuningManagerTest {
    @Test
    fun `metadata is complete and current request and canonical topics are distinct`() {
        val fixture = fixture(TuningApplyPolicy.LIVE_SAFE)
        val root = "Tuning/Parameters/drive.heading.kp"
        assertEquals("drive.heading.kP", fixture.telemetry.strings["$root/Key"])
        assertEquals("drive.main", fixture.telemetry.strings["$root/ComponentUid"])
        assertEquals("LIVE_SAFE", fixture.telemetry.strings["$root/ApplyPolicy"])
        assertEquals(true, fixture.telemetry.booleans["$root/ConsumerSupported"])
        assertEquals(0.0, fixture.telemetry.numbers["$root/Minimum"])
        assertEquals(10.0, fixture.telemetry.numbers["$root/Maximum"])
        assertEquals(1.0, fixture.telemetry.numbers["$root/Default"])
        assertEquals(2.0, fixture.telemetry.numbers["$root/Canonical"])
        assertEquals(2.0, fixture.telemetry.numbers["$root/Current"])
        assertEquals(2.0, fixture.telemetry.numbers["$root/Requested"])
    }

    @Test
    fun `fresh armed nonce applies once while stale replay and unknown topics do nothing`() {
        val fixture = fixture(TuningApplyPolicy.LIVE_SAFE)
        val root = "Tuning/Parameters/drive.heading.kp"
        fixture.armed = true
        fixture.telemetry.numbers["$root/Requested"] = 3.0
        fixture.telemetry.numbers["$root/RequestNonce"] = 1.0
        fixture.telemetry.numbers["Tuning/Parameters/unknown/Requested"] = 9.0
        fixture.manager.update(1_000L)
        assertEquals(listOf(3.0), fixture.applied)
        assertEquals(3.0, fixture.runtime.double("drive.heading.kp"))

        fixture.telemetry.numbers["$root/Requested"] = 4.0
        fixture.manager.update(2_000L)
        assertEquals(listOf(3.0), fixture.applied)
        assertEquals(3.0, fixture.runtime.double("drive.heading.kp"))

        fixture.telemetry.numbers["$root/RequestNonce"] = 2.0
        fixture.manager.update(3_000L)
        assertEquals(listOf(3.0, 4.0), fixture.applied)
    }

    @Test
    fun `policy blocked request never dispatches or changes current`() {
        val fixture = fixture(TuningApplyPolicy.READ_ONLY_VENDOR)
        val root = "Tuning/Parameters/drive.heading.kp"
        fixture.armed = true
        fixture.telemetry.numbers["$root/Requested"] = 5.0
        fixture.telemetry.numbers["$root/RequestNonce"] = 1.0
        fixture.manager.update(1_000L)

        assertTrue(fixture.applied.isEmpty())
        assertEquals(2.0, fixture.runtime.double("drive.heading.kp"))
        assertEquals(2.0, fixture.telemetry.numbers["$root/Current"])
        assertEquals("READ_ONLY_VENDOR", fixture.telemetry.strings["$root/LastResult"])
        assertFalse(fixture.telemetry.numbers.containsKey("Tuning/Parameters/unknown/Current"))
    }

    @Test
    fun `nonces outside the exact safe integer range are ignored`() {
        val fixture = fixture(TuningApplyPolicy.LIVE_SAFE)
        val root = "Tuning/Parameters/drive.heading.kp"
        fixture.armed = true
        fixture.telemetry.numbers["$root/Requested"] = 5.0

        listOf(-1.0, 1.5, 9_007_199_254_740_992.0, Double.POSITIVE_INFINITY).forEachIndexed { index, nonce ->
            fixture.telemetry.numbers["$root/RequestNonce"] = nonce
            fixture.manager.update((index + 1) * 1_000L)
        }

        assertTrue(fixture.applied.isEmpty())
        assertEquals(2.0, fixture.runtime.double("drive.heading.kp"))
    }

    @Test
    fun `failed consumer callback restores the last confirmed current value and fails visibly`() {
        val declaration = declaration(TuningApplyPolicy.LIVE_SAFE)
        val runtime = runtime(declaration)
        val telemetry = MockTelemetry()
        val manager = TuningManager(
            runtime,
            telemetry,
            { TuningApplyContext(sessionArmed = true, robotDisabled = false) },
            { _, _ -> error("consumer rejected update") },
        )
        val root = "Tuning/Parameters/drive.heading.kp"
        telemetry.numbers["$root/Requested"] = 3.0
        telemetry.numbers["$root/RequestNonce"] = 1.0

        assertThrows(IllegalStateException::class.java) { manager.update(1_000L) }
        assertEquals(2.0, runtime.double("drive.heading.kp"))
        assertEquals(2.0, telemetry.numbers["$root/Current"])
        assertEquals("APPLY_CALLBACK_FAILED", telemetry.strings["$root/LastResult"])
        assertEquals(1.0, telemetry.numbers["$root/ProcessedNonce"])
    }

    @Test
    fun `unmapped consumer rejection restores current and reports it without crashing`() {
        val declaration = declaration(TuningApplyPolicy.LIVE_SAFE)
        val runtime = runtime(declaration)
        val telemetry = MockTelemetry()
        val manager = TuningManager(
            runtime,
            telemetry,
            { TuningApplyContext(sessionArmed = true, robotDisabled = false) },
            { _, _ -> false },
        )
        val root = "Tuning/Parameters/drive.heading.kp"
        telemetry.numbers["$root/Requested"] = 3.0
        telemetry.numbers["$root/RequestNonce"] = 1.0

        manager.update(1_000L)

        assertEquals(2.0, runtime.double("drive.heading.kp"))
        assertEquals(2.0, telemetry.numbers["$root/Current"])
        assertEquals("CONSUMER_REJECTED", telemetry.strings["$root/LastResult"])
        assertEquals(1.0, telemetry.numbers["$root/ProcessedNonce"])
    }

    @Test
    fun `unsupported consumer is advertised and rejected before runtime mutation or callback`() {
        val declaration = declaration(TuningApplyPolicy.LIVE_SAFE)
        val runtime = runtime(declaration)
        val telemetry = MockTelemetry()
        var callbackCount = 0
        val manager = TuningManager(
            runtime,
            telemetry,
            { TuningApplyContext(sessionArmed = true, robotDisabled = false) },
            { _, _ -> callbackCount += 1; true },
            isConsumerSupported = { false },
        )
        val root = "Tuning/Parameters/drive.heading.kp"
        assertEquals(false, telemetry.booleans["$root/ConsumerSupported"])
        telemetry.numbers["$root/Requested"] = 3.0
        telemetry.numbers["$root/RequestNonce"] = 1.0

        manager.update(1_000L)

        assertEquals(0, callbackCount)
        assertEquals(2.0, runtime.double("drive.heading.kp"))
        assertEquals(2.0, telemetry.numbers["$root/Current"])
        assertEquals("CONSUMER_REJECTED", telemetry.strings["$root/LastResult"])
        assertEquals(1.0, telemetry.numbers["$root/ProcessedNonce"])
    }

    private fun fixture(policy: TuningApplyPolicy): Fixture {
        val declaration = declaration(policy)
        val runtime = runtime(declaration)
        val telemetry = MockTelemetry()
        val fixture = Fixture(runtime, telemetry)
        fixture.manager = TuningManager(
            runtime, telemetry, { TuningApplyContext(fixture.armed, fixture.disabled) },
            { _, value -> fixture.applied += requireNotNull(value.doubleValue); true },
        )
        return fixture
    }

    private fun declaration(policy: TuningApplyPolicy) = TuningParameterDeclaration(
            uid = "drive.heading.kp", key = "drive.heading.kP", componentUid = "drive.main",
            displayName = "Heading P", description = "Heading proportional gain",
            type = TuningParameterType.DOUBLE, unit = "rad/s per rad", minimum = 0.0, maximum = 10.0,
            defaultValue = TuningValue(doubleValue = 1.0), applyPolicy = policy,
        )

    private fun runtime(declaration: TuningParameterDeclaration) = TypedTuningRuntime(
            listOf(declaration), mapOf(declaration.uid to TuningValue(doubleValue = 2.0)),
            TuningMetadataSnapshot("project.test", "drive.main", "profile.base", listOf(declaration), listOf("profile.base")),
        )

    private class Fixture(val runtime: TypedTuningRuntime, val telemetry: MockTelemetry) {
        var armed = false
        var disabled = false
        val applied = mutableListOf<Double>()
        lateinit var manager: TuningManager
    }

    private class MockTelemetry : ITelemetry {
        val numbers = mutableMapOf<String, Double>()
        val booleans = mutableMapOf<String, Boolean>()
        val strings = mutableMapOf<String, String>()
        override fun putNumber(key: String, value: Double) { numbers[key] = value }
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double) = numbers[key] ?: defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = booleans[key] ?: defaultValue
        override fun getString(key: String, defaultValue: String) = strings[key] ?: defaultValue
    }
}
