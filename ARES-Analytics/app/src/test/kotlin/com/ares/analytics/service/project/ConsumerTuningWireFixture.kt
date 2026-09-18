package com.ares.analytics.service.project

import com.ares.analytics.service.nt4.Nt4OutboundPublisher
import com.ares.analytics.service.tuning.TuningTransport
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningValue
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.Channel
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.io.File
import java.util.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Capture the real Studio writer's bytes for replay through the generated consumer's NT4 parser. */
internal suspend fun writeConsumerTuningWireFixtures(project: File, declaration: TuningParameterDeclaration) {
    val publisher = Nt4OutboundPublisher { 1_000L }
    val session = Mockito.mock(DefaultClientWebSocketSession::class.java)
    val outgoing = Channel<Frame>(Channel.UNLIMITED)
    Mockito.doReturn(outgoing).`when`(session).outgoing
    Mockito.doAnswer {
        val frame = it.getArgument<Frame>(0)
        @Suppress("UNCHECKED_CAST")
        val continuation = it.rawArguments.last() as Continuation<Unit>
        val send: suspend () -> Unit = { outgoing.send(frame) }
        send.startCoroutineUninterceptedOrReturn(continuation)
    }.`when`(session).send(anyWireFrame())
    try {
        publisher.attach(session)
        publisher.acceptTimeSyncReply(10_000L, 1_000L)
        val connection = requireNotNull(publisher.tuningConnectionId)
        val cases = listOf(
            Triple("unarmed", 3.5, 1L), Triple("apply", 3.2, 2L),
            Triple("replay", 4.0, 2L), Triple("invalid", -5.0, 3L), Triple("late", 5.0, 5L),
        )
        for ((name, value, nonce) in cases) {
            check(publisher.publishTuningRequest(
                TuningTransport.requested(declaration), TuningTransport.requestNonce(declaration),
                TuningValue(doubleValue = value), nonce, connection,
            ))
            var binaries = 0
            val frames = buildList {
                while (true) {
                    val frame = outgoing.tryReceive().getOrNull() ?: break
                    val type = when (frame) {
                        is Frame.Text -> "T"
                        is Frame.Binary -> { binaries++; "B" }
                        else -> error("Unexpected tuning frame: $frame")
                    }
                    add(type + "|" + Base64.getEncoder().encodeToString(frame.data))
                }
            }
            check(binaries == 1) { "Value and nonce must share one production binary frame" }
            File(project, "simulator/src/test/resources/tuning-wire/$name.frames").apply {
                parentFile.mkdirs()
                writeText(frames.joinToString("\n", postfix = "\n"))
            }
        }
    } finally {
        publisher.detach(session)
        outgoing.close()
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> anyWireFrame(): T { ArgumentMatchers.any<T>(); return null as T }
