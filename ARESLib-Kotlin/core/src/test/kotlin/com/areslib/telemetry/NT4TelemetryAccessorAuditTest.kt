package com.areslib.telemetry

import com.areslib.networktables.NT4Server
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.protocols.Protocol
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class NT4TelemetryAccessorAuditTest {
    private lateinit var server: NT4Server
    private lateinit var telemetry: NT4Telemetry
    private var previousServer: NT4Server? = null
    private val namespace = "AccessorAudit/${java.util.UUID.randomUUID()}/"
    private fun topic(name: String) = namespace + name
    private val instanceField = NT4Server::class.java.getDeclaredField("serverInstance").apply { isAccessible = true }
    @BeforeEach fun setup() {
        previousServer = NT4Server.getInstance()
        server = NT4Server(InetSocketAddress("127.0.0.1", 0), Draft_6455(emptyList(), listOf(Protocol("v4.1.networktables.first.wpi.edu"))))
        // Registry/accessor fixture only: no listener or WebSocket worker is started.
        instanceField.set(null, server)
        telemetry = NT4Telemetry()
    }
    @AfterEach fun cleanup() {
        if (::server.isInitialized) server.stop()
        instanceField.set(null, previousServer)
        // Other tests may retain a singleton. Restore its ownership and remove only our namespace.
        val entries = NT4Server::class.java.getDeclaredField("entries").apply { isAccessible = true }.get(null) as MutableMap<*, *>
        entries.keys.filterIsInstance<String>().filter { it.startsWith(namespace) }.forEach(entries::remove)
        val owned = NT4Server::class.java.getDeclaredField("serverOwnedTopics").apply { isAccessible = true }.get(null) as MutableSet<*>
        owned.filterIsInstance<String>().filter { it.startsWith(namespace) }.forEach(owned::remove)
    }
    @Test fun `all leading slash spellings read the values that they publish`() {
        for (prefix in listOf("", "/", "//", "////")) {
            telemetry.putNumber(prefix + topic("Number"), 4.5); telemetry.putBoolean(prefix + topic("Flag"), true); telemetry.putString(prefix + topic("Text"), "value")
            assertEquals(4.5, telemetry.getNumber(prefix + topic("Number"), -1.0))
            assertTrue(telemetry.getBoolean(prefix + topic("Flag"), false))
            assertEquals("value", telemetry.getString(prefix + topic("Text"), "missing"))
            assertEquals(4.5, NT4Server.getDouble(prefix + topic("Number"), -1.0))
            assertEquals("value", NT4Server.getString(prefix + topic("Text"), "missing"))
        }
    }
    @Test fun `typed adapter getters reject incompatible strings and nonstrings`() {
        telemetry.putString(topic("Number"), "12.5"); telemetry.putString(topic("Flag"), "true"); telemetry.putNumber(topic("Text"), 12.5)
        assertEquals(-7.0, telemetry.getNumber(topic("Number"), -7.0))
        assertFalse(telemetry.getBoolean(topic("Flag"), false))
        assertEquals("missing", telemetry.getString(topic("Text"), "missing"))
    }
    @Test fun `announced placeholders are absent until a value is received`() {
        val number = server.putTopic(topic("Number"), 0.0); number.hasValue = false
        val flag = server.putTopic(topic("Flag"), false); flag.hasValue = false
        val text = server.putTopic(topic("Text"), ""); text.hasValue = false
        val array = server.putTopic(topic("Array"), doubleArrayOf()); array.hasValue = false
        assertEquals(-7.0, telemetry.getNumber(topic("Number"), -7.0))
        assertTrue(telemetry.getBoolean(topic("Flag"), true)); assertEquals("missing", telemetry.getString(topic("Text"), "missing"))
        assertEquals(-7.0, NT4Server.getDouble(topic("Number"), -7.0))
        assertTrue(NT4Server.getBoolean(topic("Flag"), true)); assertEquals("missing", NT4Server.getString(topic("Text"), "missing"))
        val fallback = doubleArrayOf(-7.0); assertSame(fallback, NT4Server.getDoubleArray(topic("Array"), fallback))
    }
    @Test fun `arrays and pose publication snapshot caller data and close preserves server ownership`() {
        val values = doubleArrayOf(1.0, 2.0)
        telemetry.putDoubleArray(topic("Array"), values); values[0] = 99.0
        assertContentEquals(doubleArrayOf(1.0, 2.0), NT4Server.getDoubleArray(topic("Array"), doubleArrayOf()))
        telemetry.putPose2d(topic("Pose"), 1.0, 2.0, 0.5)
        val retained = NT4Server.getDoubleArray(topic("Pose"), doubleArrayOf())
        telemetry.putPose2d(topic("Pose"), -1.0, -2.0, -0.5)
        assertContentEquals(doubleArrayOf(1.0, 2.0, 0.5), retained)
        telemetry.close(); assertSame(server, NT4Server.getInstance())
        assertEquals(-1.0, NT4Server.getDoubleArray(topic("Pose"), doubleArrayOf())[0])
        server.stop()
        assertEquals(8.0, telemetry.getNumber("missing", 8.0))
    }

    @Test fun `numeric families and static compatibility reads preserve value and array ownership`() {
        for (number in listOf(2L, 2.5f, 3.5)) {
            server.putTopic(topic("Numeric"), number)
            assertEquals(number.toDouble(), telemetry.getNumber(topic("Numeric"), -1.0))
        }
        telemetry.putString(topic("LegacyNumber"), "12.5")
        assertEquals(12.5, NT4Server.getDouble(topic("LegacyNumber"), -1.0))
        assertEquals(-1.0, telemetry.getNumber(topic("LegacyNumber"), -1.0))
        server.putTopic(topic("Floats"), floatArrayOf(1.5f, -2.5f))
        val converted = NT4Server.getDoubleArray("///" + topic("Floats"), doubleArrayOf())
        assertContentEquals(doubleArrayOf(1.5, -2.5), converted)
        converted[0] = 99.0
        assertContentEquals(doubleArrayOf(1.5, -2.5), NT4Server.getDoubleArray(topic("Floats"), doubleArrayOf()))
        val destination = doubleArrayOf(-1.0)
        server.putTopic(topic("Copy"), doubleArrayOf(4.0, 5.0))
        assertEquals(2, NT4Server.copyDoubleArray("////" + topic("Copy"), destination))
        assertContentEquals(doubleArrayOf(4.0), destination)
        assertEquals(-2, NT4Server.copyDoubleArray(topic("Floats"), destination))
        assertEquals(-1, NT4Server.copyDoubleArray(topic("Absent"), destination))
    }
}
