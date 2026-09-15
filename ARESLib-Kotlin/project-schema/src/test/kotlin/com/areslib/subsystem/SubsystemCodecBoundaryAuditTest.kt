package com.areslib.subsystem

import com.areslib.util.parseJsonElement
import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SubsystemCodecBoundaryAuditTest {
    private fun json(): JsonObject = parseJsonElement(SubsystemDocumentCodec.encode(SubsystemBoundaryFixtures.motor())).asJsonObject

    @Test
    fun `tuning type default and apply policy must remain explicit`() {
        val parameter = com.areslib.tuning.TuningParameterDeclaration(
            uid = "control-gain", key = "subsystem.arm.control.kp", componentUid = "control",
            displayName = "Control gain", description = "Explicit control gain for the codec boundary test",
            type = com.areslib.tuning.TuningParameterType.DOUBLE,
            defaultValue = com.areslib.tuning.TuningValue(doubleValue = 0.4),
            applyPolicy = com.areslib.tuning.TuningApplyPolicy.DISABLED_ONLY,
        )
        val document = SubsystemBoundaryFixtures.motor().copy(tuningParameters = listOf(parameter))
        val encoded = SubsystemDocumentCodec.encode(document)
        assertEquals(document, SubsystemDocumentCodec.decode(encoded))
        for (key in listOf("applyPolicy", "type", "defaultValue")) {
            val root = parseJsonElement(encoded).asJsonObject
            root.getAsJsonArray("tuningParameters")[0].asJsonObject.remove(key)
            assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(root.toString()) }
        }
    }

    @Test
    fun `omitted linkage center of mass follows the declared link length`() {
        val original = SubsystemBoundaryFixtures.linkage()
        val root = parseJsonElement(SubsystemDocumentCodec.encode(original)).asJsonObject
        val linkage = root.getAsJsonObject("linkage")
        linkage.addProperty("link1LengthMeters", 0.8)
        linkage.addProperty("link2LengthMeters", 0.6)
        linkage.remove("link1CenterOfMassMeters")
        linkage.remove("link2CenterOfMassMeters")
        val decoded = SubsystemDocumentCodec.decode(root.toString())
        assertEquals(0.4, decoded.linkage.link1CenterOfMassMeters)
        assertEquals(0.3, decoded.linkage.link2CenterOfMassMeters)
        linkage.addProperty("link1CenterOfMassMeters", 0.2)
        assertEquals(0.2, SubsystemDocumentCodec.decode(root.toString()).linkage.link1CenterOfMassMeters)
    }

    @Test
    fun `omitted startup and hardware required flags keep constructor defaults`() {
        val root = json()
        root.remove("requiredAtStartup")
        root.getAsJsonArray("hardware")[0].asJsonObject.remove("required")
        val decoded = SubsystemDocumentCodec.decode(root.toString())
        assertTrue(decoded.requiredAtStartup)
        assertTrue(decoded.hardware.single().required)
    }

    @Test
    fun `omitted measurement scale revision and output limits keep constructor defaults`() {
        val root = json()
        root.remove("revision")
        root.getAsJsonArray("hardware")[0].asJsonObject.getAsJsonArray("measurements")[0].asJsonObject.remove("scale")
        val loop = root.getAsJsonArray("controlLoops")[0].asJsonObject
        for (key in listOf("minimumOutput", "maximumOutput", "derivativeFilterTimeConstantSeconds")) loop.remove(key)
        val decoded = SubsystemDocumentCodec.decode(root.toString())
        assertEquals(1, decoded.revision)
        assertEquals(1.0, decoded.hardware.single().measurements.first().scale)
        assertEquals(-12.0, decoded.controlLoops.single().minimumOutput)
        assertEquals(12.0, decoded.controlLoops.single().maximumOutput)
        assertEquals(0.02, decoded.controlLoops.single().derivativeFilterTimeConstantSeconds)
    }

    @Test
    fun `unknown hardware and safety enums are rejected instead of replaced by defaults`() {
        for (path in listOf("hardware", "safety")) {
            val root = json()
            if (path == "hardware") root.getAsJsonArray("hardware")[0].asJsonObject.addProperty("kind", "TYPO_MOTOR")
            else root.getAsJsonObject("safety").getAsJsonObject("homing").addProperty("method", "TYPO_HOMING")
            assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(root.toString()) }
        }
    }

    @Test
    fun `fractional schema and revision values are rejected instead of truncated`() {
        val schema = json().apply { addProperty("schemaVersion", ARES_SUBSYSTEM_SCHEMA_VERSION + 0.5) }
        assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(schema.toString()) }
        val number = json().apply { addProperty("revision", 1.5) }
        assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(number.toString()) }
    }

    @Test
    fun `string boolean cannot silently disable required startup`() {
        val root = json().apply { addProperty("requiredAtStartup", "flase") }
        assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(root.toString()) }
    }

    @Test
    fun `explicit simulation ownership contradictions survive decoding validation`() {
        val root = json()
        root.getAsJsonObject("implementation").getAsJsonObject("simulation").addProperty("support", "UNAVAILABLE")
        assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(root.toString()) }
    }

    @Test
    fun `integer overflow and fractional feedback leases cannot truncate through the parsed tree`() {
        for (value in listOf("2147483648", "-2147483649", "1.5")) {
            val root = json().apply { add("revision", parseJsonElement(value)) }
            assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(root.toString()) }
        }
        for (value in listOf("9223372036854775808", "-9223372036854775809", "250.5")) {
            val root = json()
            root.getAsJsonObject("safety").add("feedbackTimeoutMs", parseJsonElement(value))
            assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(root.toString()) }
        }
        val exact = json().apply { add("revision", parseJsonElement("1e0")) }
        assertEquals(1, SubsystemDocumentCodec.decode(exact.toString()).revision)
    }

    @Test
    fun `nonfinite numeric JSON and wrong scalar types are rejected at the codec boundary`() {
        for (value in listOf("1e999", "\"1.0\"", "true")) {
            val root = json()
            root.getAsJsonArray("controlLoops")[0].asJsonObject.add("kP", parseJsonElement(value))
            assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(root.toString()) }
        }
        val name = json().apply { addProperty("displayName", 123) }
        assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(name.toString()) }
    }

    @Test
    fun `nullable hardware metadata remains nullable but safety scalar nulls are rejected`() {
        val allowed = json()
        allowed.getAsJsonArray("hardware")[0].asJsonObject.getAsJsonObject("connection").add("pneumaticsModuleType", com.google.gson.JsonNull.INSTANCE)
        assertEquals(SubsystemBoundaryFixtures.motor(), SubsystemDocumentCodec.decode(allowed.toString()))
        for (key in listOf("requiredAtStartup", "revision", "platform")) {
            val root = json().apply { add(key, com.google.gson.JsonNull.INSTANCE) }
            assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(root.toString()) }
        }
        val homing = json()
        homing.getAsJsonObject("safety").getAsJsonObject("homing").add("method", com.google.gson.JsonNull.INSTANCE)
        assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(homing.toString()) }
    }

    @Test
    fun `missing required device identity enums are not guessed by normalization`() {
        for (key in listOf("kind", "source", "type", "strategy")) {
            val root = json()
            when (key) {
                "kind" -> root.getAsJsonArray("hardware")[0].asJsonObject.remove(key)
                "source" -> root.getAsJsonArray("hardware")[0].asJsonObject.getAsJsonArray("measurements")[0].asJsonObject.remove(key)
                "type" -> root.getAsJsonArray("stateFields")[0].asJsonObject.remove(key)
                else -> root.getAsJsonArray("controlLoops")[0].asJsonObject.remove(key)
            }
            assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(root.toString()) }
        }
    }

    @Test
    fun `explicit generated adapter metadata is rejected but omitted inferred metadata remains valid`() {
        val root = json()
        root.getAsJsonObject("implementation").getAsJsonObject("simulation").addProperty("adapterClassName", "example.CustomAdapter")
        assertThrows<IllegalArgumentException> { SubsystemDocumentCodec.decode(root.toString()) }
        val omitted = json()
        omitted.getAsJsonObject("implementation").getAsJsonObject("simulation").remove("support")
        assertEquals(SubsystemBoundaryFixtures.motor(), SubsystemDocumentCodec.decode(omitted.toString()))
    }

    @Test
    fun `explicit false zero and complete descriptor round trips retain their meaning`() {
        val original = SubsystemBoundaryFixtures.motor().copy(requiredAtStartup = false, hardware = SubsystemBoundaryFixtures.motor().hardware.map { it.copy(required = false) })
        assertEquals(original, SubsystemDocumentCodec.decode(SubsystemDocumentCodec.encode(original)))
    }
}
