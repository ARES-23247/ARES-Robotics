package org.aresfirst.marvin.config

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SimulatorInputConfigAuditTest {
    private val json = ObjectMapper()
    private fun read(path: String) = json.readTree(File(path))

    @Test fun `controller matcher requires every axis used by its own mappings`() {
        val profile = read(".ares/controllers/frc-driver.arescontroller")
        val highestAxis = profile["controls"].flatMap { it["mappings"].toList() }
            .filter { it.has("axisIndex") }.maxOf { it["axisIndex"].asInt() }
        for (matcher in profile["deviceMatchers"]) {
            assertTrue(matcher["minimumAxisCount"].asInt() > highestAxis)
        }
    }

    @Test fun `default keyboard provides an independently bound self-centering Xbox rotation axis`() {
        val config = read("simgui-ds.json")
        assertEquals("Keyboard0", config["robotJoysticks"][0]["guid"].asText())
        val joystick = config["keyboardJoysticks"][0]
        assertTrue(joystick["axisCount"].asInt() >= 6)
        val rotation = joystick["axisConfig"][4]
        assertNotNull(rotation)
        assertTrue(rotation["decKey"].asInt() >= 0)
        assertTrue(rotation["incKey"].asInt() >= 0)
        assertNotEquals(rotation["decKey"].asInt(), rotation["incKey"].asInt())
        assertTrue(rotation["decayRate"].asDouble() > 0.0)
        val otherKeys = joystick["axisConfig"].withIndex().filter { it.index != 4 }
            .flatMap { listOf(it.value.path("decKey").asInt(-1), it.value.path("incKey").asInt(-1)) } +
            joystick["buttonKeys"].map { it.asInt() }
        assertFalse(rotation["decKey"].asInt() in otherKeys)
        assertFalse(rotation["incKey"].asInt() in otherKeys)
    }

    @Test fun `saved simulator controls have complete arrays and no persisted networktable values`() {
        for (joystick in read("simgui-ds.json")["keyboardJoysticks"]) {
            assertEquals(joystick["axisCount"].asInt(), joystick.path("axisConfig").size())
            assertEquals(joystick["buttonCount"].asInt(), joystick.path("buttonKeys").size())
            assertEquals(joystick["povCount"].asInt(), joystick.path("povConfig").size())
        }
        val persistent = read("networktables.json")
        assertTrue(persistent.isArray && persistent.isEmpty)
        assertTrue(read("simgui.json")["NetworkTables Info"]["visible"].isBoolean)
        val window = read("simgui-window.json")["MainWindow"]["GLOBAL"]
        for (key in listOf("fps", "height", "width", "userScale")) assertTrue(window[key].asDouble() > 0.0)
    }
}
