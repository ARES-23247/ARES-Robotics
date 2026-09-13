package com.areslib.tuning

import com.areslib.telemetry.ITelemetry
import com.areslib.telemetry.schema.TuningAcknowledgement
import com.areslib.telemetry.schema.TuningAcknowledgementCodec
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class TuningPollingAuditTest {
    private fun declaration(uid: String = "control.count") = TuningParameterDeclaration(
        uid = uid, key = uid, componentUid = "control.main", displayName = "Count", description = "Requested count",
        type = TuningParameterType.INT, defaultValue = TuningValue(intValue = 2), applyPolicy = TuningApplyPolicy.LIVE_SAFE,
    )
    private fun metadata(declarations: List<TuningParameterDeclaration>, profiles: List<String> = listOf("profile.base")) =
        TuningMetadataSnapshot("project.test", null, "profile.base", declarations, profiles)
    private fun runtime(declarations: List<TuningParameterDeclaration> = listOf(declaration())) =
        TypedTuningRuntime(declarations, emptyMap(), metadata(declarations))
    private class Wire : ITelemetry {
        val numbers = mutableMapOf<String, Double>(); val strings = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()
        val writes = mutableListOf<String>()
        var failStringKey: String? = null
        override fun putNumber(key: String, value: Double) { numbers[key] = value; writes += key }
        override fun putString(key: String, value: String) {
            check(key != failStringKey) { "telemetry write failed" }
            strings[key] = value; writes += key
        }
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double) = numbers[key] ?: defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = booleans[key] ?: defaultValue
        override fun getString(key: String, defaultValue: String) = strings[key] ?: defaultValue
        fun request(value: Double, nonce: Double, uid: String = "control.count") {
            numbers["Tuning/Parameters/$uid/Requested"] = value
            numbers["Tuning/Parameters/$uid/RequestNonce"] = nonce
        }
    }
    private val root = "Tuning/Parameters/control.count"

    @Test fun `invalid integer payloads are rejected without invoking or acknowledging a consumer apply`() {
        val runtime = runtime(); val wire = Wire(); var calls = 0
        val manager = TuningManager(runtime, wire, { TuningApplyContext(true, true) }, { _, _ -> calls++; true }, { true })
        val invalid = doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 2.5, Int.MAX_VALUE.toDouble() + 1, Int.MIN_VALUE.toDouble() - 1)
        invalid.forEachIndexed { index, value ->
            wire.request(value, index.toDouble())
            manager.update((index + 1L) * 500L)
            assertEquals("INVALID_VALUE", wire.strings["$root/LastResult"], "value=$value")
            assertEquals(index.toDouble(), wire.numbers["$root/ProcessedNonce"])
            assertEquals("$root/Acknowledgement", wire.writes.last())
            assertEquals(TuningAcknowledgement(index.toLong(), "INVALID_VALUE"), TuningAcknowledgementCodec.decode(wire.strings["$root/Acknowledgement"]))
            assertEquals(2, runtime.int("control.count"))
        }
        assertEquals(0, calls)
        wire.request(3.0, invalid.size.toDouble()); manager.update(10_000)
        assertEquals(3, runtime.int("control.count")); assertEquals(1, calls)
    }

    @Test fun `missing numeric payload is invalid even when its nonce is fresh`() {
        val runtime = runtime(); val wire = Wire(); var calls = 0
        val manager = TuningManager(runtime, wire, { TuningApplyContext(true, true) }, { _, _ -> calls++; true }, { true })
        wire.numbers.remove("$root/Requested"); wire.numbers["$root/RequestNonce"] = 0.0
        manager.update(1_000)
        assertEquals("INVALID_VALUE", wire.strings["$root/LastResult"])
        assertEquals(0, calls); assertEquals(2, runtime.int("control.count"))
    }

    @Test fun `metadata refresh preserves an unprocessed request and its nonce`() {
        val runtime = runtime(); val wire = Wire()
        val manager = TuningManager(runtime, wire, { TuningApplyContext(true, true) }, { _, _ -> true }, { true })
        wire.request(4.0, 7.0)
        manager.publishMetadataAndValues()
        manager.update(1_000)
        assertEquals(4, runtime.int("control.count"))
        assertEquals(7.0, wire.numbers["$root/ProcessedNonce"])
    }

    @Test fun `all nonnumeric types reject absent payloads while preserving valid false and sentinel text`() {
        for (type in listOf(TuningParameterType.BOOLEAN, TuningParameterType.TEXT, TuningParameterType.ENUM)) {
            val initial = if (type == TuningParameterType.BOOLEAN) TuningValue(booleanValue = true) else TuningValue(textValue = "off")
            val declaration = declaration().copy(type = type, defaultValue = initial, enumOptions = if (type == TuningParameterType.ENUM) listOf("off", "on") else emptyList())
            val runtime = runtime(listOf(declaration)); val wire = Wire(); var calls = 0
            val manager = TuningManager(runtime, wire, { TuningApplyContext(true, true) }, { _, _ -> calls++; true }, { true })
            wire.booleans.remove("$root/Requested"); wire.strings.remove("$root/Requested")
            wire.request(42.0, 0.0) // Wrong type in the transport is also an absent typed payload.
            manager.update(500)
            assertEquals("INVALID_VALUE", wire.strings["$root/LastResult"], type.name)
            assertEquals(0, calls)
            val expected = when (type) {
                TuningParameterType.BOOLEAN -> TuningValue(booleanValue = false).also { wire.booleans["$root/Requested"] = false }
                TuningParameterType.ENUM -> TuningValue(textValue = "on").also { wire.strings["$root/Requested"] = "on" }
                else -> TuningValue(textValue = "\u0000").also { wire.strings["$root/Requested"] = "\u0000" }
            }
            wire.numbers["$root/RequestNonce"] = 1.0; manager.update(1_000)
            assertEquals(expected, runtime.value(declaration.uid), type.name)
            assertEquals(1, calls)
        }
    }

    @Test fun `numeric transport preserves signed integer limits and rejects missing doubles`() {
        val runtime = runtime(); val wire = Wire()
        val manager = TuningManager(runtime, wire, { TuningApplyContext(true, true) }, { _, _ -> true }, { true })
        for ((index, value) in listOf(Int.MIN_VALUE, Int.MAX_VALUE).withIndex()) {
            wire.request(value.toDouble(), index.toDouble()); manager.update((index + 1L) * 500)
            assertEquals(value, runtime.int("control.count"))
        }
        val largestNonce = 9_007_199_254_740_991.0
        wire.request(4.0, largestNonce); manager.update(1_500)
        assertEquals(4, runtime.int("control.count"))
        for ((index, nonce) in listOf(largestNonce, largestNonce + 1.0, Double.NaN).withIndex()) {
            wire.request(5.0, nonce); manager.update(2_000L + index * 500L)
            assertEquals(4, runtime.int("control.count"))
        }
        val doubleDeclaration = declaration().copy(type = TuningParameterType.DOUBLE, defaultValue = TuningValue(doubleValue = 0.0))
        val doubleRuntime = runtime(listOf(doubleDeclaration)); val doubleWire = Wire()
        val doubleManager = TuningManager(doubleRuntime, doubleWire, { TuningApplyContext(true, true) }, { _, _ -> true }, { true })
        doubleWire.numbers.remove("$root/Requested"); doubleWire.numbers["$root/RequestNonce"] = 0.0
        doubleManager.update(500)
        assertEquals("INVALID_VALUE", doubleWire.strings["$root/LastResult"])
        doubleWire.request(Double.MAX_VALUE, 1.0); doubleManager.update(1_000)
        assertEquals(Double.MAX_VALUE, doubleRuntime.double("control.count"))
    }

    @Test fun `declared tuning topic paths retain exact case without telemetry aliases`() {
        assertEquals(3, TuningTopics.SCHEMA_VERSION)
        assertEquals("Tuning/SchemaVersion", TuningTopics.SCHEMA_VERSION_TOPIC)
        for (path in listOf("Tuning/Parameters/control.count/Requested", "/Tuning/Parameters/control.count/Requested", "///Tuning/Parameters/control.count/Requested", "Parameters/control.count/Requested")) {
            assertEquals("Tuning/Parameters/control.count/Requested", TuningTopics.canonicalize(path))
        }
        assertEquals("Tuning/tuning/SchemaVersion", TuningTopics.canonicalize("tuning/SchemaVersion"))
    }

    @Test fun `telemetry failure after a committed apply cannot roll back only the tuning store`() {
        val runtime = runtime(); val wire = Wire(); var controllerValue = 2
        val manager = TuningManager(runtime, wire, { TuningApplyContext(true, true) }, { _, value -> controllerValue = value.intValue!!; true }, { true })
        wire.request(3.0, 1.0); wire.failStringKey = "$root/LastResult"
        val failure = assertThrows(IllegalStateException::class.java) { manager.update(1_000) }
        assertEquals("telemetry write failed", failure.message)
        assertEquals(3, controllerValue); assertEquals(controllerValue, runtime.int("control.count"))
    }

    @Test fun `callback failure remains primary when its diagnostic telemetry also fails`() {
        val runtime = runtime(); val wire = Wire(); val rejected = IllegalStateException("consumer failed")
        val manager = TuningManager(runtime, wire, { TuningApplyContext(true, true) }, { _, _ -> throw rejected }, { true })
        wire.request(3.0, 1.0); wire.failStringKey = "$root/LastResult"
        val failure = assertThrows(IllegalStateException::class.java) { manager.update(1_000) }
        assertSame(rejected, failure)
        assertEquals(listOf("telemetry write failed"), failure.suppressed.map { it.message })
        assertEquals(2, runtime.int("control.count"))
    }

    @Test fun `metadata refresh preserves the last transaction acknowledgement`() {
        val runtime = runtime(); val wire = Wire()
        val manager = TuningManager(runtime, wire, { TuningApplyContext(true, true) }, { _, _ -> true }, { true })
        wire.request(4.0, 7.0); manager.update(1_000)
        manager.publishMetadataAndValues()
        assertEquals(7.0, wire.numbers["$root/ProcessedNonce"])
        assertEquals("APPLIED", wire.strings["$root/LastResult"])
        assertEquals(TuningAcknowledgement(7, "APPLIED"), TuningAcknowledgementCodec.decode(wire.strings["$root/Acknowledgement"]))
        assertEquals(7.0, wire.numbers["$root/RequestNonce"])
    }

    @Test fun `atomic acknowledgement carries each policy and consumer outcome with its nonce`() {
        val runtime = runtime(); val wire = Wire()
        var armed = true; var supported = true; var accept = true; var throwFromConsumer = false
        TuningManager(runtime, wire, { TuningApplyContext(armed, true) }, { _, _ ->
            if (throwFromConsumer) error("consumer failed")
            accept
        }, { supported }).use { manager ->
            assertNull(TuningAcknowledgementCodec.decode(wire.strings["$root/Acknowledgement"]))
            fun checkRequest(nonce: Long, result: String) {
                wire.request(3.0, nonce.toDouble())
                if (throwFromConsumer) assertThrows(IllegalStateException::class.java) { manager.update((nonce + 1) * 500) }
                else manager.update((nonce + 1) * 500)
                assertEquals(TuningAcknowledgement(nonce, result), TuningAcknowledgementCodec.decode(wire.strings["$root/Acknowledgement"]))
            }
            armed = false; checkRequest(0, "SESSION_NOT_ARMED")
            armed = true; supported = false; checkRequest(1, "CONSUMER_REJECTED")
            supported = true; accept = false; checkRequest(2, "CONSUMER_REJECTED")
            accept = true; throwFromConsumer = true; checkRequest(3, "APPLY_CALLBACK_FAILED")
            throwFromConsumer = false; checkRequest(4, "APPLIED")
            assertEquals(3, runtime.int("control.count"))
            wire.writes.clear()
            repeat(100) { manager.update(3_000L + it * 500L) }
            assertTrue(wire.writes.isEmpty(), "Idle polling must not construct or publish acknowledgements")
        }
    }

    @Test fun `failed atomic acknowledgement publication does not undo an accepted controller value`() {
        val runtime = runtime(); val wire = Wire(); var controllerValue = 2
        TuningManager(runtime, wire, { TuningApplyContext(true, true) }, { _, v -> controllerValue = v.intValue!!; true }, { true }).use { manager ->
            wire.failStringKey = "$root/Acknowledgement"
            wire.request(3.0, 1.0)
            assertThrows(IllegalStateException::class.java) { manager.update(500) }
            assertEquals(3, controllerValue)
            assertEquals(controllerValue, runtime.int("control.count"))
            assertNull(TuningAcknowledgementCodec.decode(wire.strings["$root/Acknowledgement"]))
        }
    }

    @Test fun `every requested apply checks the current arm state after earlier callbacks`() {
        val runtime = runtime(listOf(declaration(), declaration("control.second"))); val wire = Wire()
        var armed = true; var calls = 0
        val manager = TuningManager(runtime, wire, { TuningApplyContext(armed, true) }, { _, _ -> calls++; armed = false; true }, { true })
        wire.request(3.0, 1.0); wire.request(4.0, 1.0, "control.second")
        manager.update(1_000)
        assertEquals(1, calls)
        assertEquals(3, runtime.int("control.count")); assertEquals(2, runtime.int("control.second"))
        assertEquals("SESSION_NOT_ARMED", wire.strings["Tuning/Parameters/control.second/LastResult"])
    }

    @Test fun `polling supports signed robot timestamps and rebases clock rewinds`() {
        val runtime = runtime(); val wire = Wire(); var calls = 0
        val manager = TuningManager(runtime, wire, { TuningApplyContext(true, true) }, { _, _ -> calls++; true }, { true })
        wire.request(3.0, 0.0); manager.update(Long.MIN_VALUE)
        assertEquals(1, calls)
        wire.request(4.0, 1.0); manager.update(Long.MIN_VALUE + 499)
        assertEquals(1, calls)
        manager.update(Long.MIN_VALUE + 500); assertEquals(2, calls)
        wire.request(5.0, 2.0); manager.update(Long.MAX_VALUE); assertEquals(3, calls)
        wire.request(6.0, 3.0); manager.update(Long.MIN_VALUE + 2); assertEquals(3, calls)
        manager.update(Long.MIN_VALUE + 502); assertEquals(4, calls)
    }

    @Test fun `returning to the canonical value removes a redundant experimental assignment`() {
        val runtime = runtime(); val context = TuningApplyContext(true, true)
        assertEquals(TuningUpdateResult.APPLIED, runtime.apply("control.count", TuningValue(intValue = 3), context))
        assertEquals(1, runtime.localOverlay("local.test", "local-test", "Local test").values.size)
        assertEquals(TuningUpdateResult.APPLIED, runtime.apply("control.count", TuningValue(intValue = 2), context))
        assertTrue(runtime.localOverlay("local.test", "local-test", "Local test").values.isEmpty())
    }

    @Test fun `runtime rejects invalid initial values and unknown canonical keys`() {
        val declaration = declaration().copy(minimum = 0.0, maximum = 10.0)
        for (candidate in listOf(TuningValue(intValue = 11), TuningValue(doubleValue = Double.NaN), TuningValue())) {
            assertThrows(IllegalArgumentException::class.java) {
                TypedTuningRuntime(listOf(declaration), mapOf(declaration.uid to candidate), metadata(listOf(declaration)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TypedTuningRuntime(listOf(declaration), mapOf("unknown.value" to TuningValue(intValue = 3)), metadata(listOf(declaration)))
        }
    }

    @Test fun `runtime rejects duplicate declarations and inconsistent transport metadata`() {
        val declaration = declaration()
        assertThrows(IllegalArgumentException::class.java) { runtime(listOf(declaration, declaration)) }
        assertThrows(IllegalArgumentException::class.java) { TypedTuningRuntime(listOf(declaration), emptyMap(), metadata(emptyList())) }
        assertThrows(IllegalArgumentException::class.java) {
            TypedTuningRuntime(listOf(declaration), emptyMap(), metadata(listOf(declaration.copy(applyPolicy = TuningApplyPolicy.READ_ONLY_VENDOR))))
        }
    }

    @Test fun `runtime owns immutable snapshots of declaration options and metadata lists`() {
        val options = mutableListOf("off", "on")
        val declaration = declaration().copy(type = TuningParameterType.ENUM, defaultValue = TuningValue(textValue = "off"), enumOptions = options)
        val declarations = mutableListOf(declaration); val profiles = mutableListOf("profile.base")
        val runtime = TypedTuningRuntime(declarations, emptyMap(), metadata(declarations, profiles))
        declarations.clear(); profiles.clear(); options += "unreviewed"
        assertEquals(listOf("profile.base"), runtime.metadata.profileUids)
        assertEquals(listOf("off", "on"), runtime.metadata.declarations.single().enumOptions)
        assertEquals(TuningUpdateResult.INVALID_VALUE, runtime.apply(declaration.uid, TuningValue(textValue = "unreviewed"), TuningApplyContext(true, true)))
    }

    private class IdleWire : ITelemetry {
        @Volatile var lastKey: String? = null
        var reads = 0
        override fun putNumber(key: String, value: Double) = Unit
        override fun putString(key: String, value: String) = Unit
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double): Double { lastKey = key; reads++; return -1.0 }
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
    }
    private class PollWindow(val manager: TuningManager) {
        var timestamp = 0L
        fun measure(bean: com.sun.management.ThreadMXBean, thread: Long): Long {
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(20_000) { timestamp += 500L; manager.update(timestamp) }
            return bean.getThreadAllocatedBytes(thread) - before
        }
    }

    @Test fun `idle tuning polls allocate nothing and avoid querying apply context`() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val wire = IdleWire(); var contexts = 0
        val manager = TuningManager(runtime(), wire, { contexts++; TuningApplyContext(true, true) }, { _, _ -> error("No request") }, { true })
        val window = PollWindow(manager); val thread = Thread.currentThread().id
        repeat(5) { window.measure(bean, thread) }
        val first = window.measure(bean, thread); val second = window.measure(bean, thread)
        println("Idle tuning polls: $first, $second bytes / 20000 polls")
        assertEquals(140_000, wire.reads)
        assertEquals("$root/RequestNonce", wire.lastKey)
        assertEquals(0L, first); assertEquals(0L, second); assertEquals(0, contexts)
    }
}
