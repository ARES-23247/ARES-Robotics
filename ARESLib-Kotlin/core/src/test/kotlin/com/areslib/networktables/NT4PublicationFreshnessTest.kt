package com.areslib.networktables

import com.areslib.util.RobotClock
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.protocols.Protocol
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.test.assertEquals

/** Wire-level evidence: identical *new publications* must remain distinguishable from silence. */
class NT4PublicationFreshnessTest {
    @AfterEach fun reset() {
        NT4Server.resetSharedState()
        RobotClock.useSystemTime()
    }

    @Test fun `steady health refreshes at bounded rate without notifying value change listeners`() {
        val fixture = Fixture()
        var changes = 0
        RobotClock.useMockTime(1_000)
        val entry = fixture.server.putTopic("Robot/LoopTimeMs", 20.0)
        entry.addListener { _, _, _ -> changes++ }
        fixture.server.flush()
        for (millis in 1_020L..3_500L step 20) {
            RobotClock.useMockTime(millis)
            fixture.server.putTopic("Robot/LoopTimeMs", 20.0)
            fixture.server.flush()
        }
        assertEquals(listOf(1_000_000L, 1_500_000L, 2_000_000L, 2_500_000L, 3_000_000L, 3_500_000L), fixture.messages.map { it.timestamp })
        assertEquals(List(6) { 20.0 }, fixture.messages.map { it.dataValue })
        assertEquals(0, changes, "Freshness is not a value-change event")
    }

    @Test fun `unrelated traffic and flushes cannot refresh a stopped publisher`() {
        val fixture = Fixture()
        RobotClock.useMockTime(1_000)
        val health = fixture.server.putTopic("Robot/BatteryVoltage", 12.8)
        fixture.server.flush()
        for (millis in 1_020L..5_000L step 20) {
            RobotClock.useMockTime(millis)
            fixture.server.putTopic("ARES/Telemetry/FrameSequence", millis.toDouble())
            fixture.server.flush()
        }
        val healthMessages = fixture.messages.filter { it.id == health.id.toLong() }
        assertEquals(1, healthMessages.size)
        assertEquals(1_000_000L, healthMessages.single().timestamp)
    }

    @Test fun `value changes are immediate and identical publications recover after clock reset`() {
        val fixture = Fixture()
        RobotClock.useMockTime(1_000)
        fixture.server.putTopic("Robot/BatteryVoltage", 12.8)
        fixture.server.flush()
        RobotClock.useMockTime(1_020)
        fixture.server.putTopic("Robot/BatteryVoltage", 12.0)
        fixture.server.flush()
        RobotClock.useMockTime(0)
        fixture.server.putTopic("Robot/BatteryVoltage", 12.0)
        fixture.server.flush()
        RobotClock.useMockTime(499)
        fixture.server.putTopic("Robot/BatteryVoltage", 12.0)
        fixture.server.flush()
        assertEquals(listOf(12.8, 12.0, 12.0), fixture.messages.map { it.dataValue })
        assertEquals(listOf(1_000_000L, 1_020_000L, 0L), fixture.messages.map { it.timestamp })
    }

    private class Fixture {
        val server = NT4Server(InetSocketAddress("127.0.0.1", 0), Draft_6455(emptyList(), listOf(Protocol("v4.1.networktables.first.wpi.edu"))))
        val messages = mutableListOf<NT4Message>()
        init {
            NT4Server.resetSharedState()
            val connection = proxy<WebSocket> { name, args ->
                when (name) {
                    "isOpen" -> true
                    "hasBufferedData" -> false
                    "send" -> {
                        (args?.firstOrNull() as? ByteBuffer)?.let { buffer ->
                            val copy = ByteArray(buffer.remaining())
                            buffer.duplicate().get(copy)
                            messages += server.decodeNT4Messages(ByteBuffer.wrap(copy))
                        }
                        null
                    }
                    else -> null
                }
            }
            server.onOpen(connection, proxy<ClientHandshake> { _, _ -> null })
            server.onMessage(connection, """{"method":"subscribe","params":{"topics":[""],"subuid":1,"options":{"prefix":true}}}""")
        }
    }

    companion object {
        private inline fun <reified T> proxy(crossinline handler: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "PublicationFreshnessProxy"
                    else -> handler(method.name, args)
                }
            } as T
    }
}
