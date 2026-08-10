package com.areslib.networktables

import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.protocols.Protocol
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.msgpack.core.MessagePack
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NT4NetworkingHardeningTest {

    @AfterEach
    fun resetTopics() {
        NT4Server.resetSharedState()
    }

    @Test
    fun oversizedArrayIsTruncatedWithoutDesynchronizingFollowingFrame() {
        val output = ByteArrayOutputStream()
        val packer = MessagePack.newDefaultPacker(output)
        packer.packArrayHeader(4)
        packer.packLong(10L)
        packer.packLong(100L)
        packer.packInt(17)
        packer.packArrayHeader(300)
        for (i in 0 until 300) packer.packInt(i)

        packer.packArrayHeader(4)
        packer.packLong(11L)
        packer.packLong(101L)
        packer.packInt(1)
        packer.packDouble(42.5)
        packer.close()

        val messages = NT4WireProtocol.unpackMessageFrames(output.toByteArray())

        assertEquals(2, messages.size)
        val retained = messages[0].value as List<*>
        assertEquals(256, retained.size)
        assertEquals(255L, retained.last())
        assertEquals(11L, messages[1].topicId)
        assertEquals(42.5, messages[1].value)
    }

    @Test
    fun congestedClientRetriesLatestTopicValueWhenBufferClears() {
        NT4Server.resetSharedState()
        val server = NT4Server(
            InetSocketAddress("127.0.0.1", 0),
            Draft_6455(
                Collections.emptyList(),
                listOf(Protocol("v4.1.networktables.first.wpi.edu"))
            )
        )
        val buffered = AtomicBoolean(true)
        val binaryMessages = mutableListOf<ByteArray>()
        val connection = proxy<WebSocket> { method, args ->
            when (method.name) {
                "hasBufferedData" -> buffered.get()
                "send" -> {
                    val message = args?.firstOrNull()
                    if (message is ByteBuffer) {
                        val copy = ByteArray(message.remaining())
                        message.duplicate().get(copy)
                        binaryMessages.add(copy)
                    }
                    null
                }
                else -> defaultValue(method.returnType)
            }
        }
        val handshake = proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) }

        server.onOpen(connection, handshake)
        server.onMessage(
            connection,
            subscribe(1, listOf("/Retry/"), prefix = true)
        )

        server.putTopic("Retry/Value", 1.0)
        server.flush()
        server.putTopic("Retry/Value", 2.0)
        server.flush()
        assertTrue(binaryMessages.isEmpty(), "A congested socket must not be sent another frame")

        buffered.set(false)
        server.flush()

        assertEquals(1, binaryMessages.size)
        val decoded = server.decodeNT4Messages(ByteBuffer.wrap(binaryMessages.single()))
        assertEquals(1, decoded.size)
        assertEquals(2.0, decoded.single().dataValue)

        server.flush()
        assertEquals(1, binaryMessages.size, "A successfully retried value must leave the pending set")
        assertFalse(buffered.get())
    }

    @Test
    fun concurrentPublicationAndDirtyDrainingDoesNotLoseTopics() {
        NT4Server.resetSharedState()
        val server = NT4Server(
            InetSocketAddress("127.0.0.1", 0),
            Draft_6455(
                Collections.emptyList(),
                listOf(Protocol("v4.1.networktables.first.wpi.edu"))
            )
        )
        val drainMethod = NT4Server::class.java.getDeclaredMethod("drainDirtyEntries").apply {
            isAccessible = true
        }
        val producerCount = 4
        val topicsPerProducer = 500
        val remaining = AtomicInteger(producerCount)
        val start = CountDownLatch(1)
        val observedTopics = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val pool = Executors.newFixedThreadPool(producerCount + 1)

        val producers = (0 until producerCount).map { producer ->
            pool.submit {
                start.await()
                for (i in 0 until topicsPerProducer) {
                    server.putTopic("Race/$producer/$i", i.toDouble())
                }
                remaining.decrementAndGet()
            }
        }
        val drainer = pool.submit {
            start.await()
            while (remaining.get() > 0) {
                observedTopics.addAll(drain(drainMethod, server).map { it.topic })
                Thread.yield()
            }
        }

        start.countDown()
        producers.forEach { it.get() }
        drainer.get()
        observedTopics.addAll(drain(drainMethod, server).map { it.topic })
        pool.shutdown()

        assertEquals(producerCount * topicsPerProducer, observedTopics.size)
    }

    @Test
    fun publisherIdsAreIsolatedPerConnectionAndRequirePublishOwnership() {
        val server = newServer()
        val connectionA = webSocketProxy()
        val connectionB = webSocketProxy()
        val handshake = proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) }
        server.onOpen(connectionA, handshake)
        server.onOpen(connectionB, handshake)

        server.onMessage(connectionA, publish("Client/A", 2000, "double"))
        server.onMessage(connectionB, publish("Client/B", 2000, "double"))
        server.onMessage(connectionA, server.encodeNT4Message(1L, 2000L, 0L, 1, 1.25))
        server.onMessage(connectionB, server.encodeNT4Message(2L, 2000L, 0L, 1, 2.5))

        assertEquals(1.25, server.getTopicEntry("Client/A")?.value?.getAsObject())
        assertEquals(2.5, server.getTopicEntry("Client/B")?.value?.getAsObject())

        server.onMessage(connectionA, """{"method":"unpublish","params":{"pubuid":2000}}""")
        server.onMessage(connectionA, server.encodeNT4Message(3L, 2000L, 0L, 1, 9.0))
        assertEquals(null, server.getTopicEntry("Client/A"))

        server.onClose(connectionA, 1000, "test", false)
        server.onMessage(connectionB, server.encodeNT4Message(4L, 2000L, 0L, 1, 3.5))
        assertEquals(3.5, server.getTopicEntry("Client/B")?.value?.getAsObject())

        val serverOwned = server.putTopic("Server/Owned", 4.0)
        server.onMessage(connectionB, server.encodeNT4Message(5L, serverOwned.id.toLong(), 0L, 1, 99.0))
        assertEquals(4.0, server.getTopicEntry("Server/Owned")?.value?.getAsObject())
    }

    @Test
    fun unsubscribeStopsExactSubscriptionWithoutAffectingOtherClients() {
        val server = newServer()
        val firstMessages = mutableListOf<ByteArray>()
        val secondMessages = mutableListOf<ByteArray>()
        val first = webSocketProxy(firstMessages)
        val second = webSocketProxy(secondMessages)
        val handshake = proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) }
        server.onOpen(first, handshake)
        server.onOpen(second, handshake)
        server.onMessage(first, subscribe(7, listOf("/Exact"), prefix = false))
        server.onMessage(second, subscribe(8, listOf("/Exact"), prefix = false))

        server.putTopic("Exact/Child", 1.0)
        server.flush()
        assertTrue(firstMessages.isEmpty())
        assertTrue(secondMessages.isEmpty())

        server.putTopic("Exact", 2.0)
        server.flush()
        assertEquals(1, firstMessages.size)
        assertEquals(1, secondMessages.size)

        server.onMessage(first, """{"method":"unsubscribe","params":{"subuid":7}}""")
        server.putTopic("Exact", 3.0)
        server.flush()
        assertEquals(1, firstMessages.size)
        assertEquals(2, secondMessages.size)
    }

    @Test
    fun declaredIntegerAndFloatTypesArePreservedInAnnouncements() {
        val connection = webSocketProxy()
        val server = newServer()
        server.onOpen(connection, proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) })

        server.onMessage(connection, publish("Typed/Int", 1, "int"))
        server.onMessage(connection, publish("Typed/Ints", 2, "int[]"))
        server.onMessage(connection, publish("Typed/Float", 3, "float"))
        server.onMessage(connection, publish("Typed/Floats", 4, "float[]"))

        assertEquals("int", server.getTopicEntry("Typed/Int")?.value?.typeString)
        assertEquals("int[]", server.getTopicEntry("Typed/Ints")?.value?.typeString)
        assertEquals("float", server.getTopicEntry("Typed/Float")?.value?.typeString)
        assertEquals("float[]", server.getTopicEntry("Typed/Floats")?.value?.typeString)
    }

    @Test
    fun leadingSlashAliasesCannotSplitOrRetypeATopic() {
        val server = newServer()
        val first = server.putTopic("/Canonical/Value", 1.0)
        val same = server.putTopic("///Canonical/Value", 2.0)

        assertTrue(first === same)
        assertEquals("double", same.value.typeString)
        assertEquals(2.0, same.value.getAsObject())
        assertTrue(server.getTopicEntry("////Canonical/Value") === first)

        val mismatched = server.putTopic("Canonical/Value", 5L)
        assertTrue(mismatched === first)
        assertEquals("double", first.value.typeString)
        assertEquals(2.0, first.value.getAsObject())

        val connection = webSocketProxy()
        server.onOpen(connection, proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) })
        server.onMessage(connection, publish("/Canonical/Value", 42, "int"))
        server.onMessage(connection, server.encodeNT4Message(1L, 42L, 0L, 2, 99L))

        assertEquals("double", first.value.typeString)
        assertEquals(2.0, first.value.getAsObject())
    }

    @Test
    fun decoderRejectsOversizedContainersBeforeAllocation() {
        val server = newServer()
        val output = ByteArrayOutputStream()
        MessagePack.newDefaultPacker(output).use { packer ->
            packer.packArrayHeader(4)
            packer.packLong(10L)
            packer.packLong(100L)
            packer.packInt(17)
            packer.packArrayHeader(NT4Server.MAX_ARRAY_ELEMENTS + 1)
        }

        assertThrows(java.io.IOException::class.java) {
            server.decodeNT4Messages(ByteBuffer.wrap(output.toByteArray()))
        }

    }

    @Test
    fun analyticsWireDecoderRejectsWholeFrameWhenLaterTupleIsMalformed() {
        val output = ByteArrayOutputStream()
        MessagePack.newDefaultPacker(output).use { packer ->
            packer.packArrayHeader(4)
            packer.packLong(1L)
            packer.packLong(10L)
            packer.packInt(1)
            packer.packDouble(2.0)
            packer.packArrayHeader(3)
            packer.packLong(2L)
            packer.packLong(11L)
            packer.packInt(1)
        }

        assertTrue(NT4WireProtocol.unpackMessageFrames(output.toByteArray()).isEmpty())
    }

    @Test
    fun analyticsWireDecoderBoundsDeclaredStringBeforeAllocation() {
        val output = ByteArrayOutputStream()
        MessagePack.newDefaultPacker(output).use { packer ->
            packer.packArrayHeader(4)
            packer.packLong(1L)
            packer.packLong(10L)
            packer.packInt(4)
            packer.packRawStringHeader(NT4WireProtocol.MAX_STRING_BYTES + 1)
        }

        assertTrue(NT4WireProtocol.unpackMessageFrames(output.toByteArray()).isEmpty())
    }

    @Test
    fun standardFlatUpdateTupleIsAcceptedByBothDecoders() {
        val output = ByteArrayOutputStream()
        MessagePack.newDefaultPacker(output).use { packer ->
            packer.packArrayHeader(4)
            packer.packLong(7L)
            packer.packLong(123_000L)
            packer.packInt(1)
            packer.packDouble(4.5)
        }
        val bytes = output.toByteArray()

        assertEquals(4.5, NT4WireProtocol.unpackMessageFrames(bytes).single().value)
        assertEquals(4.5, newServer().decodeNT4Messages(ByteBuffer.wrap(bytes)).single().dataValue)
    }

    @Test
    fun encoderEmitsStandardTupleStreamAndReturnsOwnedBuffers() {
        val server = newServer()
        val firstEntry = NT4Entry(1, "One", NT4Value.DoubleVal(1.0), timestampUs = 10L)
        val secondEntry = NT4Entry(2, "Two", NT4Value.DoubleVal(2.0), timestampUs = 20L)
        val first = server.encodeNT4Messages(99L, listOf(firstEntry))
        val firstSnapshot = ByteArray(first.remaining()).also { first.duplicate().get(it) }

        val second = server.encodeNT4Messages(99L, listOf(secondEntry))
        val afterReuse = ByteArray(first.remaining()).also { first.duplicate().get(it) }

        assertEquals(0x94.toByte(), firstSnapshot.first(), "a standard update starts with its 4-tuple")
        assertTrue(firstSnapshot.contentEquals(afterReuse), "queued frames must not alias the reusable encoder")
        assertEquals(1.0, server.decodeNT4Messages(ByteBuffer.wrap(firstSnapshot)).single().dataValue)
        assertEquals(2.0, server.decodeNT4Messages(second).single().dataValue)
    }

    @Test
    fun announcementsAreScopedEscapedAndTransientTopicsAreUnannounced() {
        val server = newServer()
        val publisherText = mutableListOf<String>()
        val subscriberText = mutableListOf<String>()
        val bystanderText = mutableListOf<String>()
        val publisher = webSocketProxy(textMessages = publisherText)
        val subscriber = webSocketProxy(textMessages = subscriberText)
        val bystander = webSocketProxy(textMessages = bystanderText)
        val handshake = proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) }
        server.onOpen(publisher, handshake)
        server.onOpen(subscriber, handshake)
        server.onOpen(bystander, handshake)
        assertTrue(publisherText.isEmpty() && subscriberText.isEmpty() && bystanderText.isEmpty())

        server.onMessage(subscriber, subscribe(1, listOf("/Scoped/"), prefix = true))
        server.onMessage(publisher, publish("Scoped/Quoted\\\"Topic", 9, "double"))

        assertTrue(publisherText.single().contains("\"pubuid\":9"))
        assertTrue(subscriberText.single().contains("Quoted\\\"Topic"))
        assertFalse(subscriberText.single().contains("pubuid"))
        assertTrue(bystanderText.isEmpty())

        server.onMessage(publisher, """{"method":"unpublish","params":{"pubuid":9}}""")
        assertTrue(subscriberText.last().contains("\"method\":\"unannounce\""))
        assertTrue(publisherText.last().contains("\"method\":\"unannounce\""))
        assertEquals(null, server.getTopicEntry("Scoped/Quoted\"Topic"))
    }

    @Test
    fun firstDefaultValuedUpdateIsPublishedAndOlderUpdatesCannotRollBackState() {
        val server = newServer()
        val publisher = webSocketProxy()
        val subscriberBinary = mutableListOf<ByteArray>()
        val subscriber = webSocketProxy(subscriberBinary)
        val handshake = proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) }
        server.onOpen(publisher, handshake)
        server.onOpen(subscriber, handshake)
        server.onMessage(subscriber, subscribe(1, listOf("/Timestamped"), prefix = false))
        server.onMessage(publisher, publish("Timestamped", 7, "double"))

        server.onMessage(publisher, server.encodeNT4Message(100L, 7L, 0L, 1, 0.0))
        server.flush()
        assertEquals(0.0, server.decodeNT4Messages(ByteBuffer.wrap(subscriberBinary.single())).single().dataValue)

        server.onMessage(publisher, server.encodeNT4Message(90L, 7L, 0L, 1, 5.0))
        server.flush()
        assertEquals(1, subscriberBinary.size)
        assertEquals(0.0, server.getTopicEntry("Timestamped")?.value?.getAsObject())
    }

    private fun newServer(): NT4Server = NT4Server(
        InetSocketAddress("127.0.0.1", 0),
        Draft_6455(
            Collections.emptyList(),
            listOf(Protocol("v4.1.networktables.first.wpi.edu"))
        )
    )

    private fun publish(topic: String, pubUid: Int, type: String): String =
        """{"method":"publish","params":{"name":"$topic","pubuid":$pubUid,"type":"$type"}}"""

    private fun subscribe(subUid: Int, topics: List<String>, prefix: Boolean): String {
        val encodedTopics = topics.joinToString(",") { "\"$it\"" }
        return """{"method":"subscribe","params":{"topics":[$encodedTopics],"subuid":$subUid,"options":{"prefix":$prefix}}}"""
    }

    private fun webSocketProxy(
        binaryMessages: MutableList<ByteArray>? = null,
        textMessages: MutableList<String>? = null
    ): WebSocket {
        return proxy { method, args ->
            when (method.name) {
                "hasBufferedData" -> false
                "send" -> {
                    val message = args?.firstOrNull()
                    if (message is ByteBuffer && binaryMessages != null) {
                        val copy = ByteArray(message.remaining())
                        message.duplicate().get(copy)
                        binaryMessages.add(copy)
                    } else if (message is String && textMessages != null) {
                        textMessages.add(message)
                    }
                    null
                }
                else -> defaultValue(method.returnType)
            }
        }
    }

    private inline fun <reified T> proxy(
        crossinline handler: (java.lang.reflect.Method, Array<out Any?>?) -> Any?
    ): T {
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "NT4NetworkingHardeningTestProxy"
                else -> handler(method, args)
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

    @Suppress("UNCHECKED_CAST")
    private fun drain(method: java.lang.reflect.Method, server: NT4Server): Set<NT4Entry> {
        return method.invoke(server) as Set<NT4Entry>
    }
}
