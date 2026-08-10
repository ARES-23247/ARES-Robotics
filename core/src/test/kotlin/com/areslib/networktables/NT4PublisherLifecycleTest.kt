package com.areslib.networktables

import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.protocols.Protocol
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NT4PublisherLifecycleTest {

    @BeforeEach
    fun startWithEmptySharedTopics() {
        NT4Server.resetSharedState()
    }

    @AfterEach
    fun resetSharedTopics() {
        NT4Server.resetSharedState()
    }

    @Test
    fun `unpublishing one of two owners does not emit final unpublish`() {
        val server = newServer()
        val first = webSocketProxy()
        val second = webSocketProxy()
        val handshake = handshakeProxy()
        server.onOpen(first, handshake)
        server.onOpen(second, handshake)

        server.onMessage(first, publish("Shared/Value", pubUid = 10))
        server.onMessage(second, publish("Shared/Value", pubUid = 20))
        val entry = requireNotNull(server.getTopicEntry("Shared/Value"))
        val events = mutableListOf<NT4EventType>()
        entry.addListener(NT4EventListener { _, eventType, _ -> events.add(eventType) })

        server.onMessage(first, unpublish(pubUid = 10))
        assertEquals(emptyList(), events)

        server.onMessage(second, unpublish(pubUid = 20))
        assertEquals(listOf(NT4EventType.TOPIC_UNPUBLISHED), events)
    }

    @Test
    fun `closing owners follows the same final-owner lifecycle and deletes the transient topic`() {
        val server = newServer()
        val first = webSocketProxy()
        val second = webSocketProxy()
        val handshake = handshakeProxy()
        server.onOpen(first, handshake)
        server.onOpen(second, handshake)

        server.onMessage(first, publish("Shared/Close", pubUid = 11))
        server.onMessage(second, publish("Shared/Close", pubUid = 21))
        val entry = requireNotNull(server.getTopicEntry("Shared/Close"))
        val events = mutableListOf<NT4EventType>()
        entry.addListener(NT4EventListener { _, eventType, _ -> events.add(eventType) })

        server.onClose(first, 1000, "test", false)
        assertEquals(emptyList(), events)

        server.onClose(second, 1000, "test", false)
        assertEquals(listOf(NT4EventType.TOPIC_UNPUBLISHED), events)
        assertNull(server.getTopicEntry("/Shared/Close"))
    }

    @Test
    fun `reusing a publisher id transfers ownership without unpublishing a still-owned old topic`() {
        val server = newServer()
        val first = webSocketProxy()
        val second = webSocketProxy()
        val handshake = handshakeProxy()
        server.onOpen(first, handshake)
        server.onOpen(second, handshake)

        server.onMessage(first, publish("Old/Topic", pubUid = 7))
        server.onMessage(second, publish("Old/Topic", pubUid = 8))
        val oldEntry = requireNotNull(server.getTopicEntry("Old/Topic"))
        val events = mutableListOf<NT4EventType>()
        oldEntry.addListener(NT4EventListener { _, eventType, _ -> events.add(eventType) })

        server.onMessage(first, publish("New/Topic", pubUid = 7))

        assertEquals(emptyList(), events, "second connection still owns the old topic")
        server.onMessage(second, unpublish(pubUid = 8))
        assertEquals(listOf(NT4EventType.TOPIC_UNPUBLISHED), events)
    }

    private fun newServer() = NT4Server(
        InetSocketAddress("127.0.0.1", 0),
        Draft_6455(
            Collections.emptyList(),
            listOf(Protocol("v4.1.networktables.first.wpi.edu"))
        )
    )

    private fun publish(topic: String, pubUid: Int): String =
        """{"method":"publish","params":{"name":"$topic","pubuid":$pubUid,"type":"double"}}"""

    private fun unpublish(pubUid: Int): String =
        """{"method":"unpublish","params":{"pubuid":$pubUid}}"""

    private fun webSocketProxy(): WebSocket = proxy { method -> defaultValue(method.returnType) }

    private fun handshakeProxy(): ClientHandshake = proxy { method -> defaultValue(method.returnType) }

    private inline fun <reified T> proxy(crossinline handler: (java.lang.reflect.Method) -> Any?): T {
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.firstOrNull()
                "toString" -> "NT4PublisherLifecycleTestProxy"
                else -> handler(method)
            }
        } as T
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
