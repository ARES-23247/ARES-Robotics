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
        com.areslib.util.RobotClock.useSystemTime()
    }

    @Test
    fun boundedArrayIsFullyDecodedWithoutDesynchronizingFollowingFrame() {
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
        assertEquals(300, retained.size)
        assertEquals(299L, retained.last())
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
    fun sustainedConnectedFlushesReuseOneOwnedBufferAfterWarmup() {
        val server = newServer()
        val buffered = AtomicBoolean(false)
        val sentBuffers = mutableListOf<ByteBuffer>()
        val sentArrays = mutableListOf<ByteArray>()
        val sentLengths = mutableListOf<Int>()
        val connection = proxy<WebSocket> { method, args ->
            when (method.name) {
                "hasBufferedData" -> buffered.get()
                "send" -> {
                    val message = args?.firstOrNull()
                    if (message is ByteBuffer) {
                        sentBuffers.add(message)
                        sentArrays.add(message.array())
                        sentLengths.add(message.remaining())
                        buffered.set(true)
                    }
                    null
                }
                else -> defaultValue(method.returnType)
            }
        }
        val handshake = proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) }
        server.onOpen(connection, handshake)
        server.onMessage(connection, subscribe(1, listOf("/Reuse/"), prefix = true))

        server.putTopic("Reuse/Value", 1.0)
        server.flush()
        assertEquals(1, sentBuffers.size)
        val backingArray = sentArrays.single()
        val sendBuffer = sentBuffers.single()
        val firstSnapshot = backingArray.copyOfRange(0, sentLengths.single())
        val allocationsAfterWarmup = server.ownedSendBufferAllocationCount()

        // A queued frame retains exclusive ownership: no encoding or mutation occurs while the
        // connection reports buffered transport data.
        server.putTopic("Reuse/Value", 2.0)
        server.flush()
        assertEquals(1, sentBuffers.size)
        assertTrue(firstSnapshot.contentEquals(backingArray.copyOfRange(0, firstSnapshot.size)))

        buffered.set(false)
        server.flush()
        repeat(100) { iteration ->
            buffered.set(false)
            server.putTopic("Reuse/Value", iteration + 3.0)
            server.flush()
        }

        assertEquals(102, sentBuffers.size)
        assertEquals(
            allocationsAfterWarmup,
            server.ownedSendBufferAllocationCount(),
            "steady-state dirty flushes must not allocate another owned send buffer"
        )
        assertTrue(sentArrays.all { it === backingArray }, "every drained send must reuse the same backing array")
        assertTrue(sentBuffers.all { it === sendBuffer }, "every drained send must reuse the same ByteBuffer view")
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
        server.onMessage(connectionA, server.encodeNT4Message(0L, 2000L, 0L, 1, 1.25))
        server.onMessage(connectionB, server.encodeNT4Message(0L, 2000L, 0L, 1, 2.5))

        assertEquals(1.25, server.getTopicEntry("Client/A")?.value?.getAsObject())
        assertEquals(2.5, server.getTopicEntry("Client/B")?.value?.getAsObject())

        server.onMessage(connectionA, """{"method":"unpublish","params":{"pubuid":2000}}""")
        server.onMessage(connectionA, server.encodeNT4Message(0L, 2000L, 0L, 1, 9.0))
        assertEquals(null, server.getTopicEntry("Client/A"))

        server.onClose(connectionA, 1000, "test", false)
        server.onMessage(connectionB, server.encodeNT4Message(0L, 2000L, 0L, 1, 3.5))
        assertEquals(3.5, server.getTopicEntry("Client/B")?.value?.getAsObject())

        val serverOwned = server.putTopic("Server/Owned", 4.0)
        server.onMessage(connectionB, publish("Server/Owned", 3000, "double"))
        server.onMessage(connectionB, server.encodeNT4Message(0L, 3000L, 0L, 1, 99.0))
        assertEquals(4.0, server.getTopicEntry("Server/Owned")?.value?.getAsObject())

        // A server write also revokes mutation through a client publisher that existed first.
        server.putTopic("Client/B", 7.0)
        server.onMessage(connectionB, server.encodeNT4Message(0L, 2000L, 0L, 1, 8.0))
        assertEquals(7.0, server.getTopicEntry("Client/B")?.value?.getAsObject())
    }

    @Test
    fun clientTimestampsMustBeZeroOrWithinServerReceiptBounds() {
        com.areslib.util.RobotClock.useMockTime(10_000L)
        try {
            val server = newServer()
            val connection = webSocketProxy()
            val handshake = proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) }
            server.onOpen(connection, handshake)
            server.onMessage(connection, publish("Client/Time", 77, "double"))

            server.onMessage(connection, server.encodeNT4Message(10_000_000L, 77L, 0L, 1, 1.0))
            assertEquals(1.0, server.getTopicEntry("Client/Time")?.value?.getAsObject())

            server.onMessage(connection, server.encodeNT4Message(70_000_001L, 77L, 0L, 1, 2.0))
            assertEquals(1.0, server.getTopicEntry("Client/Time")?.value?.getAsObject())

            server.onMessage(connection, server.encodeNT4Message(0L, 77L, 0L, 1, 3.0))
            assertEquals(3.0, server.getTopicEntry("Client/Time")?.value?.getAsObject())
        } finally {
            com.areslib.util.RobotClock.useSystemTime()
        }
    }

    @Test
    fun serverClaimOverwritesClientFutureTimestampAndRevokesPublisher() {
        com.areslib.util.RobotClock.useMockTime(10_000L)
        val server = newServer()
        val connection = webSocketProxy()
        server.onOpen(connection, proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) })
        server.onMessage(connection, publish("Claim/Future", 91, "double"))
        server.onMessage(connection, server.encodeNT4Message(70_000_000L, 91L, 0L, 1, 99.0))

        val claimed = server.putTopic("Claim/Future", 4.0)
        server.onMessage(connection, server.encodeNT4Message(0L, 91L, 0L, 1, 123.0))

        assertEquals(4.0, claimed.value.getAsObject())
        assertEquals(10_000_000L, claimed.timestampUs)
        assertTrue(server.getTopicEntry("Claim/Future") === claimed)
    }

    @Test
    fun serverClaimReplacesWrongTypeClientSquatWithFreshAnnouncementIdentity() {
        val server = newServer()
        val connection = webSocketProxy()
        server.onOpen(connection, proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) })
        server.onMessage(connection, publish("Claim/Typed", 92, "string"))
        server.onMessage(connection, server.encodeNT4Message(0L, 92L, 0L, 4, "squat"))
        val clientEntry = requireNotNull(server.getTopicEntry("Claim/Typed"))

        val claimed = server.putTopic("Claim/Typed", 8.5)
        server.onMessage(connection, server.encodeNT4Message(0L, 92L, 0L, 4, "resurrect"))

        assertFalse(claimed === clientEntry)
        assertTrue(claimed.id != clientEntry.id)
        assertEquals("double", claimed.value.typeString)
        assertEquals(8.5, claimed.value.getAsObject())
    }

    @Test
    fun concurrentClientUpdatesCannotWinServerOwnershipClaim() {
        com.areslib.util.RobotClock.useMockTime(20_000L)
        val server = newServer()
        val connection = webSocketProxy()
        server.onOpen(connection, proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) })
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val client = pool.submit {
            start.await()
            repeat(500) { value ->
                server.onMessage(connection, publish("Claim/Race", 93, "double"))
                server.onMessage(
                    connection,
                    server.encodeNT4Message(80_000_000L, 93L, 0L, 1, value.toDouble())
                )
            }
        }
        val owner = pool.submit {
            start.await()
            repeat(500) { server.putTopic("Claim/Race", -it.toDouble()) }
        }

        start.countDown()
        client.get()
        owner.get()
        val authoritative = server.putTopic("Claim/Race", 42.0)
        pool.shutdown()

        assertEquals(42.0, authoritative.value.getAsObject())
        assertEquals("double", authoritative.value.typeString)
        assertTrue(server.getTopicEntry("Claim/Race") === authoritative)
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
        assertFalse(mismatched === first)
        assertEquals("int", mismatched.value.typeString)
        assertEquals(5L, mismatched.value.getAsObject())

        val connection = webSocketProxy()
        server.onOpen(connection, proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) })
        server.onMessage(connection, publish("/Canonical/Value", 42, "int"))
        server.onMessage(connection, server.encodeNT4Message(1L, 42L, 0L, 2, 99L))

        assertEquals("int", mismatched.value.typeString)
        assertEquals(5L, mismatched.value.getAsObject())
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
    fun analyticsWireDecoderBoundsFrameCountArraysBlobsAndNestingBeforeAllocation() {
        fun tupleWith(writeValue: (org.msgpack.core.MessagePacker) -> Unit): ByteArray {
            val output = ByteArrayOutputStream()
            MessagePack.newDefaultPacker(output).use { packer ->
                packer.packArrayHeader(4)
                packer.packLong(1L)
                packer.packLong(10L)
                packer.packInt(5)
                writeValue(packer)
            }
            return output.toByteArray()
        }

        val oversizedArray = tupleWith { it.packArrayHeader(NT4WireProtocol.MAX_ARRAY_ELEMENTS + 1) }
        val oversizedBlob = tupleWith { it.packBinaryHeader(NT4WireProtocol.MAX_BINARY_BYTES + 1) }
        val excessiveNesting = tupleWith { packer ->
            repeat(NT4WireProtocol.MAX_VALUE_NESTING_DEPTH + 2) { packer.packArrayHeader(1) }
            packer.packNil()
        }
        assertTrue(NT4WireProtocol.unpackMessageFrames(oversizedArray).isEmpty())
        assertTrue(NT4WireProtocol.unpackMessageFrames(oversizedBlob).isEmpty())
        assertTrue(NT4WireProtocol.unpackMessageFrames(excessiveNesting).isEmpty())

        val tooManyMessages = ByteArrayOutputStream()
        MessagePack.newDefaultPacker(tooManyMessages).use { packer ->
            repeat(NT4WireProtocol.MAX_MESSAGES_PER_FRAME + 1) {
                packer.packArrayHeader(4)
                packer.packLong(it.toLong())
                packer.packLong(10L)
                packer.packInt(0)
                packer.packNil()
            }
        }
        assertTrue(NT4WireProtocol.unpackMessageFrames(tooManyMessages.toByteArray()).isEmpty())
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
        com.areslib.util.RobotClock.useMockTime(10_000L)
        val server = newServer()
        val publisher = webSocketProxy()
        val subscriberBinary = mutableListOf<ByteArray>()
        val subscriber = webSocketProxy(subscriberBinary)
        val handshake = proxy<ClientHandshake> { method, _ -> defaultValue(method.returnType) }
        server.onOpen(publisher, handshake)
        server.onOpen(subscriber, handshake)
        server.onMessage(subscriber, subscribe(1, listOf("/Timestamped"), prefix = false))
        server.onMessage(publisher, publish("Timestamped", 7, "double"))

        server.onMessage(publisher, server.encodeNT4Message(10_000_100L, 7L, 0L, 1, 0.0))
        server.flush()
        assertEquals(0.0, server.decodeNT4Messages(ByteBuffer.wrap(subscriberBinary.single())).single().dataValue)

        server.onMessage(publisher, server.encodeNT4Message(10_000_090L, 7L, 0L, 1, 5.0))
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
