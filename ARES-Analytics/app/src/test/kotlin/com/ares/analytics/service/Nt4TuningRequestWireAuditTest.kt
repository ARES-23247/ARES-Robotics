package com.ares.analytics.service

import com.ares.analytics.service.nt4.Nt4OutboundPublisher
import com.ares.analytics.service.tuning.TuningTransport
import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import com.areslib.networktables.NT4WireProtocol
import com.areslib.tuning.*
import com.areslib.telemetry.schema.TuningAcknowledgement
import com.areslib.telemetry.schema.TuningAcknowledgementCodec
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.mockito.Mockito.*
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.*

class Nt4TuningRequestWireAuditTest {
    private val root = "Tuning/Parameters/gain.uid"
    private val requested = "$root/Requested"
    private val nonceKey = "$root/RequestNonce"
    private val value = TuningValue(doubleValue = 2.0)

    @Test fun `typed value and commit nonce occupy one correctly ordered binary frame`() = runBlocking {
        val publisher = Nt4OutboundPublisher { 1_000L }
        val wire = Wire()
        try {
            publisher.attach(wire.session); publisher.acceptTimeSyncReply(10_000L, 1_000L)
            val connection = assertNotNull(publisher.tuningConnectionId)
            val values = listOf(
                TuningValue(doubleValue = Math.PI) to (1 to Math.PI),
                TuningValue(doubleValue = -0.0) to (1 to -0.0),
                TuningValue(intValue = Int.MIN_VALUE) to (1 to Int.MIN_VALUE.toDouble()),
                TuningValue(booleanValue = true) to (0 to true),
                TuningValue(booleanValue = false) to (0 to false),
                TuningValue(textValue = "mode\n\u03bc") to (4 to "mode\n\u03bc"),
                TuningValue(textValue = "") to (4 to ""),
            )
            values.forEachIndexed { index, (typed, expected) ->
                val path = "Tuning/Parameters/p$index"
                val nonce = if (index == 0) 9_007_199_254_740_991L else index.toLong()
                assertTrue(publisher.publishTuningRequest("$path/Requested", "$path/RequestNonce", typed, nonce, connection))
                val frames = wire.drain()
                val tuple = NT4WireProtocol.unpackMessageFrames(frames.single().data)
                assertEquals(2, tuple.size)
                assertEquals(wire.ids.getValue("$path/Requested"), tuple[0].topicId)
                assertEquals(wire.ids.getValue("$path/RequestNonce"), tuple[1].topicId)
                assertEquals(expected.first, tuple[0].typeId)
                assertEquals(expected.second, tuple[0].value)
                assertEquals(1, tuple[1].typeId)
                assertEquals(nonce.toDouble(), tuple[1].value)
                assertEquals(listOf(0L, 0L), tuple.map { it.timestampUs })
            }
        } finally { publisher.detach(wire.session); wire.close() }
    }

    @Test fun `a full outgoing queue accepts neither value nor nonce and a later retry stays paired`() = runBlocking {
        val publisher = Nt4OutboundPublisher { 1_000L }
        val wire = Wire()
        val full = Channel<Frame>(1)
        try {
            publisher.attach(wire.session); publisher.acceptTimeSyncReply(10_000L, 1_000L)
            val connection = assertNotNull(publisher.tuningConnectionId)
            assertTrue(publisher.publishTuningRequest(requested, nonceKey, value, 1, connection)); wire.drain()
            val marker = Frame.Text("occupied")
            full.send(marker)
            doReturn(full).`when`(wire.session).outgoing
            assertFalse(publisher.publishTuningRequest(requested, nonceKey, TuningValue(doubleValue = 3.0), 2, connection))
            assertSame(marker, full.receive())
            assertTrue(full.tryReceive().isFailure)
            assertTrue(publisher.publishTuningRequest(requested, nonceKey, TuningValue(doubleValue = 4.0), 3, connection))
            val frame = assertIs<Frame.Binary>(full.receive())
            assertEquals(listOf(4.0, 3.0), NT4WireProtocol.unpackMessageFrames(frame.data).map { it.value })
            assertTrue(full.tryReceive().isFailure)
        } finally { publisher.detach(wire.session); full.close(); wire.close() }
    }

    @Test fun `handshake and connection identity gate every tuning frame`() = runBlocking {
        val publisher = Nt4OutboundPublisher { 1_000L }
        val old = Wire(); val next = Wire()
        try {
            assertNull(publisher.tuningConnectionId)
            assertFalse(publisher.publishTuningRequest(requested, nonceKey, value, 1, 1))
            publisher.attach(old.session)
            assertNull(publisher.tuningConnectionId)
            assertFalse(publisher.publishTuningRequest(requested, nonceKey, value, 1, 1))
            assertTrue(old.drain().isEmpty())
            publisher.acceptTimeSyncReply(10_000L, 1_000L)
            val prior = assertNotNull(publisher.tuningConnectionId)
            publisher.detach(old.session)
            assertNull(publisher.tuningConnectionId)
            publisher.attach(next.session); publisher.acceptTimeSyncReply(20_000L, 1_000L)
            val current = assertNotNull(publisher.tuningConnectionId)
            assertNotEquals(prior, current)
            assertFalse(publisher.publishTuningRequest(requested, nonceKey, value, 2, prior))
            assertTrue(next.drain().isEmpty())
            assertTrue(publisher.publishTuningRequest(requested, nonceKey, value, 2, current))
            assertEquals(1, next.drain().size)
        } finally { publisher.detach(next.session); old.close(); next.close() }
    }

    @Test fun `reconnection during publisher registration cannot migrate the pending value and nonce`() = runBlocking {
        val publisher = Nt4OutboundPublisher { 1_000L }
        val old = Wire(Channel.RENDEZVOUS); val next = Wire()
        try {
            publisher.attach(old.session); publisher.acceptTimeSyncReply(10_000L, 1_000L)
            val prior = assertNotNull(publisher.tuningConnectionId)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { publisher.publishTuningRequest(requested, nonceKey, value, 1, prior) }
            try {
                assertFalse(pending.isCompleted)
                publisher.detach(old.session)
                publisher.attach(next.session); publisher.acceptTimeSyncReply(20_000L, 1_000L)
                assertIs<Frame.Text>(old.channel.receive())
                assertFalse(withTimeout(5_000) { pending.await() })
                assertTrue(next.drain().isEmpty())
                assertTrue(old.channel.tryReceive().isFailure)
            } finally { pending.cancelAndJoin() }
        } finally { publisher.detach(next.session); old.close(); next.close() }
    }

    @Test fun `invalid nonce shape text size and topic pair are rejected before a value frame`() = runBlocking {
        val publisher = Nt4OutboundPublisher { 1_000L }
        val wire = Wire()
        try {
            publisher.attach(wire.session); publisher.acceptTimeSyncReply(10_000L, 1_000L)
            val connection = assertNotNull(publisher.tuningConnectionId)
            for (bad in listOf(TuningValue(), TuningValue(doubleValue = Double.NaN),
                TuningValue(doubleValue = Double.POSITIVE_INFINITY), TuningValue(doubleValue = 1.0, booleanValue = true),
                TuningValue(textValue = "\u03bc".repeat(40_000)))) {
                assertFailsWith<IllegalArgumentException> { publisher.publishTuningRequest(requested, nonceKey, bad, 1, connection) }
            }
            for (nonce in listOf(-1L, 9_007_199_254_740_992L, Long.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> { publisher.publishTuningRequest(requested, nonceKey, value, nonce, connection) }
            }
            assertFailsWith<IllegalArgumentException> { publisher.publishTuningRequest(requested, "$root/OtherNonce", value, 1, connection) }
            assertTrue(wire.drain().isEmpty())
            assertTrue(wire.ids.isEmpty())
        } finally { publisher.detach(wire.session); wire.close() }
    }

    @Test fun `existing publisher type conflicts cannot emit a commit nonce`() = runBlocking {
        val publisher = Nt4OutboundPublisher { 1_000L }
        val wire = Wire()
        try {
            publisher.attach(wire.session); publisher.acceptTimeSyncReply(10_000L, 1_000L)
            val connection = assertNotNull(publisher.tuningConnectionId)
            assertTrue(publisher.publishTuningRequest(requested, nonceKey, value, 1, connection)); wire.drain()
            assertFailsWith<IllegalArgumentException> { publisher.publishTuningRequest(requested, nonceKey, TuningValue(booleanValue = true), 2, connection) }
            assertTrue(wire.drain().isEmpty())
        } finally { publisher.detach(wire.session); wire.close() }
    }

    @Test fun `public tuning publisher reaches an owned loopback server with ordered typed values`() = runBlocking {
        assertNull(NT4Server.getInstance(), "This fixture cannot replace another test's active server")
        val directory = Files.createTempDirectory("tuning-wire-audit").toFile()
        val database = DatabaseService(File(directory, "test.duckdb").path)
        val client = Nt4ClientService(database)
        val server = NT4Instance.defaultInstance.startServer("127.0.0.1", 0)
        try {
            val port = withTimeout(5_000) {
                while (server.port <= 0) delay(10)
                server.port
            }
            client.start("127.0.0.1", "team", "season", "robot", port)
            val connection = withTimeout(5_000) {
                while (client.tuningConnectionId == null) delay(10)
                requireNotNull(client.tuningConnectionId)
            }
            val values = listOf(TuningValue(doubleValue = Math.PI), TuningValue(intValue = Int.MIN_VALUE),
                TuningValue(booleanValue = true), TuningValue(textValue = "\u03bc mode"), TuningValue(textValue = "coast"))
            val types = listOf(TuningParameterType.DOUBLE, TuningParameterType.INT, TuningParameterType.BOOLEAN,
                TuningParameterType.TEXT, TuningParameterType.ENUM)
            for ((index, typed) in values.withIndex()) {
                val declaration = TuningParameterDeclaration("p$index.uid", "test.p$index", "component.main", "Value $index", "Test",
                    types[index], defaultValue = typed, enumOptions = if (types[index] == TuningParameterType.ENUM) listOf("coast") else emptyList(),
                    applyPolicy = TuningApplyPolicy.LIVE_SAFE)
                val nonce = index + 1L
                val wrongType = if (declaration.type == TuningParameterType.BOOLEAN) TuningValue(doubleValue = 1.0) else TuningValue(booleanValue = true)
                assertFailsWith<IllegalArgumentException> { client.publishTuningRequest(declaration, wrongType, nonce, connection) }
                assertEquals(-1.0, NT4Server.getDouble(TuningTransport.requestNonce(declaration), -1.0))
                assertTrue(client.publishTuningRequest(declaration, typed, nonce, connection))
                withTimeout(5_000) { while (NT4Server.getDouble(TuningTransport.requestNonce(declaration), -1.0) != nonce.toDouble()) delay(10) }
                val key = TuningTransport.requested(declaration)
                when (declaration.type) {
                    TuningParameterType.DOUBLE -> assertEquals(typed.doubleValue, NT4Server.getDouble(key, Double.NaN))
                    TuningParameterType.INT -> assertEquals(typed.intValue!!.toDouble(), NT4Server.getDouble(key, Double.NaN))
                    TuningParameterType.BOOLEAN -> assertTrue(NT4Server.getBoolean(key, false))
                    else -> assertEquals(typed.textValue, NT4Server.getString(key, "missing"))
                }
                NT4Server.publishTopic(TuningTransport.lastResult(declaration), "APPLIED")
                NT4Server.publishTopic(TuningTransport.processedNonce(declaration), nonce.toDouble())
                val acknowledgement = TuningAcknowledgement(nonce, "APPLIED")
                NT4Server.publishTopic(TuningTransport.acknowledgement(declaration), TuningAcknowledgementCodec.encode(acknowledgement))
                server.flush()
                withTimeout(5_000) {
                    while (TuningAcknowledgementCodec.decode(client.latestValues[TuningTransport.acknowledgement(declaration)]?.stringValue) != acknowledgement) {
                        server.flush(); delay(10)
                    }
                }
                assertEquals(acknowledgement, TuningAcknowledgementCodec.decode(client.latestValues[TuningTransport.acknowledgement(declaration)]?.stringValue))
            }
        } finally {
            try { withTimeout(5_000) { assertTrue(client.stop()) } }
            finally { try { server.stop() } finally { database.close(); assertTrue(directory.deleteRecursively()) } }
        }
    }

    private class Wire(capacity: Int = Channel.UNLIMITED) {
        val channel = Channel<Frame>(capacity)
        val session = mock(DefaultClientWebSocketSession::class.java)
        val ids = mutableMapOf<String, Long>()
        init {
            doReturn(channel).`when`(session).outgoing
            runBlocking {
                doAnswer {
                    val frame = it.getArgument<Frame>(0)
                    @Suppress("UNCHECKED_CAST")
                    val continuation = it.rawArguments.last() as Continuation<Unit>
                    val send: suspend () -> Unit = { session.outgoing.send(frame) }
                    send.startCoroutineUninterceptedOrReturn(continuation)
                }.`when`(session).send(anyValue())
            }
        }
        fun drain(): List<Frame.Binary> = buildList {
            while (true) {
                when (val frame = channel.tryReceive().getOrNull() ?: break) {
                    is Frame.Binary -> add(frame)
                    is Frame.Text -> {
                        val messages = Json.parseToJsonElement(String(frame.data, Charsets.UTF_8)).jsonArray
                        for (message in messages) {
                            val params = message.jsonObject.getValue("params").jsonObject
                            ids[params.getValue("name").jsonPrimitive.content] = params.getValue("pubuid").jsonPrimitive.long
                        }
                    }
                    else -> error("Unexpected wire frame")
                }
            }
        }
        fun close() { channel.close() }

        @Suppress("UNCHECKED_CAST")
        private fun <T> anyValue(): T { org.mockito.ArgumentMatchers.any<T>(); return null as T }
    }
}
