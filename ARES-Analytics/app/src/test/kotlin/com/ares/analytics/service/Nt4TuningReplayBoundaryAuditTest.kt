package com.ares.analytics.service

import com.ares.analytics.service.nt4.Nt4OutboundPublisher
import com.ares.analytics.service.tuning.TuningTransport
import com.areslib.networktables.NT4WireProtocol
import com.areslib.tuning.*
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.serialization.json.*
import org.mockito.Mockito.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.*

class Nt4TuningReplayBoundaryAuditTest {
    private val declaration = TuningParameterDeclaration(
        uid = "gain.kP",
        key = "drive.translationKp",
        componentUid = "drive",
        displayName = "Drive Translation kP",
        description = "Proportional gain",
        type = TuningParameterType.DOUBLE,
        defaultValue = TuningValue(doubleValue = 1.0),
        applyPolicy = TuningApplyPolicy.LIVE_SAFE,
    )
    private val requested = TuningTransport.requested(declaration)
    private val nonceKey = TuningTransport.requestNonce(declaration)
    private val value = TuningValue(doubleValue = 2.5)
    private val nonce = 42L

    @Test
    fun `publishTuningRequest fails closed immediately when replay is active at invocation`() = runBlocking {
        val directory = Files.createTempDirectory("tuning-replay-audit-immediate").toFile()
        val database = DatabaseService(File(directory, "test.duckdb").path)
        val client = Nt4ClientService(database)
        val wire = Wire()
        try {
            client.outboundPublisher().attach(wire.session)
            client.outboundPublisher().acceptTimeSyncReply(10_000L, 1_000L)
            val connection = assertNotNull(client.tuningConnectionId)

            client.isReplayActive.value = true

            val published = client.publishTuningRequest(declaration, value, nonce, connection)
            assertFalse(published, "Publication must fail closed when replay is active")
            assertTrue(wire.drain().isEmpty(), "No frames must be enqueued or sent to wire")
            assertNull(client.telemetryStore.latest(requested), "Local telemetry for requested topic must not be recorded")
            assertNull(client.telemetryStore.latest(nonceKey), "Local telemetry for nonce topic must not be recorded")
        } finally {
            try {
                client.outboundPublisher().detach(wire.session)
            } finally {
                wire.close()
                val disposed = withTimeout(5_000) { client.disposeAndJoin() }
                assertTrue(disposed, "Nt4ClientService must dispose cleanly")
                database.close()
                assertTrue(directory.deleteRecursively(), "Temp directory must be deleted")
            }
        }
    }

    @Test
    fun `suspension during dynamic publisher registration fails closed when replay starts`() = runBlocking {
        val directory = Files.createTempDirectory("tuning-replay-audit-suspend-replay").toFile()
        val database = DatabaseService(File(directory, "test.duckdb").path)
        val client = Nt4ClientService(database)
        val gateEntered = CompletableDeferred<Unit>()
        val gateRelease = CompletableDeferred<Unit>()
        val wire = Wire(
            onFirstTextSend = {
                gateEntered.complete(Unit)
                gateRelease.await()
            }
        )
        try {
            client.outboundPublisher().attach(wire.session)
            client.outboundPublisher().acceptTimeSyncReply(10_000L, 1_000L)
            val connection = assertNotNull(client.tuningConnectionId)

            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                client.publishTuningRequest(declaration, value, nonce, connection)
            }
            try {
                gateEntered.await()
                assertFalse(pending.isCompleted, "Must be suspended awaiting publisher registration delivery")

                // Replay starts while registration is in-flight, without dropping the socket
                client.isReplayActive.value = true

                // Release gate: on baseline both registrations complete and binary is enqueued;
                // on fix, it fails closed before sending binary
                gateRelease.complete(Unit)

                val published = withTimeout(5_000) { pending.await() }
                assertFalse(published, "Publication must fail closed when replay starts during suspension")
                assertTrue(wire.drain().isEmpty(), "Binary value and nonce frame must not be enqueued to wire")
                assertNull(client.telemetryStore.latest(requested), "Stale requested telemetry must be suppressed")
                assertNull(client.telemetryStore.latest(nonceKey), "Stale nonce telemetry must be suppressed")
            } finally {
                pending.cancelAndJoin()
            }
        } finally {
            try {
                client.outboundPublisher().detach(wire.session)
            } finally {
                wire.close()
                val disposed = withTimeout(5_000) { client.disposeAndJoin() }
                assertTrue(disposed, "Nt4ClientService must dispose cleanly")
                database.close()
                assertTrue(directory.deleteRecursively(), "Temp directory must be deleted")
            }
        }
    }

    @Test
    fun `suspension during dynamic publisher registration fails closed when target epoch advances`() = runBlocking {
        val directory = Files.createTempDirectory("tuning-replay-audit-suspend-epoch").toFile()
        val database = DatabaseService(File(directory, "test.duckdb").path)
        val client = Nt4ClientService(database)
        val gateEntered = CompletableDeferred<Unit>()
        val gateRelease = CompletableDeferred<Unit>()
        val wire = Wire(
            onFirstTextSend = {
                gateEntered.complete(Unit)
                gateRelease.await()
            }
        )
        try {
            client.outboundPublisher().attach(wire.session)
            client.outboundPublisher().acceptTimeSyncReply(10_000L, 1_000L)
            val connection = assertNotNull(client.tuningConnectionId)

            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                client.publishTuningRequest(declaration, value, nonce, connection)
            }
            try {
                gateEntered.await()
                assertFalse(pending.isCompleted, "Must be suspended awaiting publisher registration delivery")

                // Target state reset advances targetEpoch without changing the socket generation
                val priorEpoch = client.telemetryStore.currentTargetEpoch()
                client.clearLiveTargetState()
                val newEpoch = client.telemetryStore.currentTargetEpoch()
                assertNotEquals(priorEpoch, newEpoch, "Target epoch must advance")
                assertEquals(connection, client.tuningConnectionId, "Socket generation must remain unchanged")

                gateRelease.complete(Unit)

                val published = withTimeout(5_000) { pending.await() }
                assertFalse(published, "Publication must fail closed when target epoch changes during suspension")
                assertTrue(wire.drain().isEmpty(), "Binary frame must not reach wire on stale target epoch")
                assertNull(client.telemetryStore.latest(requested), "Stale requested telemetry must be suppressed")
                assertNull(client.telemetryStore.latest(nonceKey), "Stale nonce telemetry must be suppressed")
            } finally {
                pending.cancelAndJoin()
            }
        } finally {
            try {
                client.outboundPublisher().detach(wire.session)
            } finally {
                wire.close()
                val disposed = withTimeout(5_000) { client.disposeAndJoin() }
                assertTrue(disposed, "Nt4ClientService must dispose cleanly")
                database.close()
                assertTrue(directory.deleteRecursively(), "Temp directory must be deleted")
            }
        }
    }

    @Test
    fun `live publication without replay or epoch change sends atomic wire frame and commits telemetry`() = runBlocking {
        val directory = Files.createTempDirectory("tuning-replay-audit-success").toFile()
        val database = DatabaseService(File(directory, "test.duckdb").path)
        val client = Nt4ClientService(database)
        val wire = Wire()
        try {
            client.outboundPublisher().attach(wire.session)
            client.outboundPublisher().acceptTimeSyncReply(10_000L, 1_000L)
            val connection = assertNotNull(client.tuningConnectionId)

            val published = client.publishTuningRequest(declaration, value, nonce, connection)
            assertTrue(published, "Publication must succeed under live conditions")

            val binaryFrames = wire.drain()
            assertEquals(1, binaryFrames.size, "Exactly one combined binary frame must be enqueued")
            val unpacked = NT4WireProtocol.unpackMessageFrames(binaryFrames.single().data)
            assertEquals(2, unpacked.size, "Frame must contain atomic value and commit nonce messages")
            assertEquals(wire.ids.getValue(requested), unpacked[0].topicId)
            assertEquals(1, unpacked[0].typeId)
            assertEquals(2.5, unpacked[0].value)
            assertEquals(wire.ids.getValue(nonceKey), unpacked[1].topicId)
            assertEquals(1, unpacked[1].typeId)
            assertEquals(nonce.toDouble(), unpacked[1].value)

            assertEquals(2.5, client.telemetryStore.latest(requested)?.value)
            assertEquals(nonce.toDouble(), client.telemetryStore.latest(nonceKey)?.value)
        } finally {
            try {
                client.outboundPublisher().detach(wire.session)
            } finally {
                wire.close()
                val disposed = withTimeout(5_000) { client.disposeAndJoin() }
                assertTrue(disposed, "Nt4ClientService must dispose cleanly")
                database.close()
                assertTrue(directory.deleteRecursively(), "Temp directory must be deleted")
            }
        }
    }

    @Test
    fun `post-enqueue state transition preserves enqueued return true but suppresses stale local telemetry`() = runBlocking {
        val directory = Files.createTempDirectory("tuning-replay-audit-post-enqueue").toFile()
        val database = DatabaseService(File(directory, "test.duckdb").path)
        val client = Nt4ClientService(database)
        val wire = Wire(
            onTrySendBinary = {
                // State transition occurs precisely as the binary frame is committed to outgoing
                client.isReplayActive.value = true
            }
        )
        try {
            client.outboundPublisher().attach(wire.session)
            client.outboundPublisher().acceptTimeSyncReply(10_000L, 1_000L)
            val connection = assertNotNull(client.tuningConnectionId)

            val published = client.publishTuningRequest(declaration, value, nonce, connection)
            assertTrue(published, "True indicates successful wire enqueue even when local observation writes are skipped")

            val binaryFrames = wire.drain()
            assertEquals(1, binaryFrames.size, "Wire frame was already enqueued to session")

            // Local telemetry writes must be suppressed because context became stale
            assertNull(client.telemetryStore.latest(requested), "Local requested write must be suppressed")
            assertNull(client.telemetryStore.latest(nonceKey), "Local nonce write must be suppressed")
        } finally {
            try {
                client.outboundPublisher().detach(wire.session)
            } finally {
                wire.close()
                val disposed = withTimeout(5_000) { client.disposeAndJoin() }
                assertTrue(disposed, "Nt4ClientService must dispose cleanly")
                database.close()
                assertTrue(directory.deleteRecursively(), "Temp directory must be deleted")
            }
        }
    }

    private fun Nt4ClientService.outboundPublisher(): Nt4OutboundPublisher {
        val field = Nt4ClientService::class.java.getDeclaredField("outboundPublisher")
        field.isAccessible = true
        return field.get(this) as Nt4OutboundPublisher
    }

    private class Wire(
        val channel: Channel<Frame> = Channel(Channel.UNLIMITED),
        val onFirstTextSend: (suspend (Frame.Text) -> Unit)? = null,
        val onTrySendBinary: ((Frame.Binary) -> Unit)? = null,
    ) {
        val session = mock(DefaultClientWebSocketSession::class.java)
        val ids = mutableMapOf<String, Long>()
        private val firstTextIntercepted = AtomicBoolean(false)

        init {
            val interceptingOutgoing = InterceptingSendChannel(channel, onTrySendBinary)
            doReturn(interceptingOutgoing).`when`(session).outgoing
            runBlocking {
                doAnswer {
                    val frame = it.getArgument<Frame>(0)
                    @Suppress("UNCHECKED_CAST")
                    val continuation = it.rawArguments.last() as Continuation<Unit>
                    val send: suspend () -> Unit = {
                        if (frame is Frame.Text && firstTextIntercepted.compareAndSet(false, true)) {
                            onFirstTextSend?.invoke(frame)
                        }
                        session.outgoing.send(frame)
                    }
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

        fun close() {
            channel.close()
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> anyValue(): T {
            org.mockito.ArgumentMatchers.any<T>()
            return null as T
        }
    }

    private class InterceptingSendChannel(
        private val delegate: Channel<Frame>,
        private val onTrySendBinary: ((Frame.Binary) -> Unit)?,
    ) : Channel<Frame> by delegate {
        override fun trySend(element: Frame): ChannelResult<Unit> {
            if (element is Frame.Binary) {
                onTrySendBinary?.invoke(element)
            }
            return delegate.trySend(element)
        }
    }
}
