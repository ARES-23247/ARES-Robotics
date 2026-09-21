package com.ares.analytics.service.project

import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.TelemetryStore
import com.ares.analytics.service.nt4.Nt4OutboundPublisher
import com.ares.analytics.service.tuning.TuningTransport
import com.ares.analytics.viewmodel.SysIdState
import com.ares.analytics.viewmodel.sysid.SysIdSignalGenerator
import com.areslib.control.assist.SysIdMechanism
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningValue
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.io.File
import java.util.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Capture the real Studio writer's bytes for replay through the generated consumer's NT4 parser. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun writeConsumerTuningWireFixtures(project: File, declaration: TuningParameterDeclaration) {
    fun saveFrames(name: String, outgoing: Channel<Frame>, expectedBinaries: Int, expectedTexts: Int = 0) {
        var binaries = 0
        var texts = 0
        val frames = buildList {
            while (true) {
                val frame = outgoing.tryReceive().getOrNull() ?: break
                val type = when (frame) {
                    is Frame.Text -> { texts++; "T" }
                    is Frame.Binary -> { binaries++; "B" }
                    else -> error("Unexpected frame: $frame")
                }
                add(type + "|" + Base64.getEncoder().encodeToString(frame.data))
            }
        }
        check(binaries == expectedBinaries) { "$name: expected $expectedBinaries binary frames, got $binaries" }
        check(texts == expectedTexts) { "$name: expected $expectedTexts registration frames, got $texts" }
        File(project, "simulator/src/test/resources/tuning-wire/$name.frames").apply {
            parentFile.mkdirs()
            writeText(frames.joinToString("\n", postfix = "\n"))
        }
    }

    // Sequence 1: Live tuning & Studio calibration control wire sequence
    run {
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

        val client = Mockito.mock(Nt4ClientService::class.java)
        val store = TelemetryStore()
        Mockito.`when`(client.isConnected).thenReturn(MutableStateFlow(true))
        Mockito.`when`(client.isReplayActive).thenReturn(MutableStateFlow(false))
        Mockito.`when`(client.telemetryStore).thenReturn(store)
        Mockito.`when`(client.tuningConnectionId).thenAnswer { publisher.tuningConnectionId }

        val transport = object : com.ares.analytics.viewmodel.sysid.CalibrationCommandTransport {
            override suspend fun publishString(pubuid: Int, value: String): Boolean =
                publisher.publishInputString(pubuid, value)
            override suspend fun publishDouble(pubuid: Int, value: Double): Boolean =
                publisher.publishInputDouble(pubuid, value)
        }

        val scheduler = TestCoroutineScheduler()
        val testScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        try {
            publisher.attach(session)
            publisher.acceptTimeSyncReply(10_000L, 1_000L)
            val connection = requireNotNull(publisher.tuningConnectionId)

            val state = MutableStateFlow(SysIdState(
                isRobotConnected = true,
                calibrationModeEnabled = true,
                requiresNetworkArm = true,
                capabilitiesKnown = true,
                supportedMechanisms = setOf(SysIdMechanism.LINEAR)
            ))
            val generator = SysIdSignalGenerator(client, state, testScope, calibrationTransport = transport)

            // 1. unarmed: dynamic registration + binary request
            check(publisher.publishTuningRequest(
                TuningTransport.requested(declaration), TuningTransport.requestNonce(declaration),
                TuningValue(doubleValue = 3.5), 1L, connection,
            ))
            saveFrames("unarmed", outgoing, expectedBinaries = 1, expectedTexts = 2)

            // 2. Register the production fixed topics, then publish STOP, token and lease.
            session.send(Frame.Text(publisher.fixedPublishMessage()))
            generator.arm()
            scheduler.runCurrent()
            saveFrames("wire-arm", outgoing, expectedBinaries = 3, expectedTexts = 1)

            // 3. lease renewals: generator renewal job advancing every 200 ms
            for (seq in 2..8) {
                scheduler.advanceTimeBy(200)
                scheduler.runCurrent()
                saveFrames("wire-lease-$seq", outgoing, expectedBinaries = 1)
            }

            // 4. apply: valid heading request under active calibration
            check(publisher.publishTuningRequest(
                TuningTransport.requested(declaration), TuningTransport.requestNonce(declaration),
                TuningValue(doubleValue = 3.2), 2L, connection,
            ))
            saveFrames("apply", outgoing, expectedBinaries = 1)

            // 5. replay: same nonce 2L, conflicting value 4.0
            check(publisher.publishTuningRequest(
                TuningTransport.requested(declaration), TuningTransport.requestNonce(declaration),
                TuningValue(doubleValue = 4.0), 2L, connection,
            ))
            saveFrames("replay", outgoing, expectedBinaries = 1)

            // 6. invalid: fresh nonce 3L, value -5.0 below declared minimum
            check(publisher.publishTuningRequest(
                TuningTransport.requested(declaration), TuningTransport.requestNonce(declaration),
                TuningValue(doubleValue = -5.0), 3L, connection,
            ))
            saveFrames("invalid", outgoing, expectedBinaries = 1)

            // 7. wire-disarm: generator.disarm() publishes STOP and revokes token to ""
            generator.disarm("Studio operator disarmed")
            saveFrames("wire-disarm", outgoing, expectedBinaries = 2)
            scheduler.advanceTimeBy(1_000)
            scheduler.runCurrent()
            check(!generator.hasActiveArmLease())
            check(testScope.coroutineContext.job.children.none { it.isActive })
            check(outgoing.tryReceive().isFailure) { "Disarmed Studio must stop renewing the lease" }

            // 8. disarmed: fresh nonce 4L, value 3.6 under disarmed session
            check(publisher.publishTuningRequest(
                TuningTransport.requested(declaration), TuningTransport.requestNonce(declaration),
                TuningValue(doubleValue = 3.6), 4L, connection,
            ))
            saveFrames("disarmed", outgoing, expectedBinaries = 1)

            // 9. wire-rearm: generator.arm() publishes fresh token, advancing lease (9.0), STOP
            generator.arm()
            scheduler.runCurrent()
            saveFrames("wire-rearm", outgoing, expectedBinaries = 3)

            // 9b. lease renewals during rearm session (leases 10.0 and 11.0)
            for (seq in 10..11) {
                scheduler.advanceTimeBy(200)
                scheduler.runCurrent()
                saveFrames("wire-lease-$seq", outgoing, expectedBinaries = 1)
            }

            // 10. A different gain proves the recovered transaction reaches the consumer.
            check(publisher.publishTuningRequest(
                TuningTransport.requested(declaration), TuningTransport.requestNonce(declaration),
                TuningValue(doubleValue = 2.6), 5L, connection,
            ))
            saveFrames("wire-rearm-apply", outgoing, expectedBinaries = 1)

            // 11. wire-late: fresh nonce 6L, value 5.0 after lifecycle stop
            check(publisher.publishTuningRequest(
                TuningTransport.requested(declaration), TuningTransport.requestNonce(declaration),
                TuningValue(doubleValue = 5.0), 6L, connection,
            ))
            saveFrames("wire-late", outgoing, expectedBinaries = 1)

            generator.disarm("Session cleanup")
            while (outgoing.tryReceive().isSuccess) { /* drain */ }
        } finally {
            // Even capture/assertion failures must retire the owned renewal job.
            try {
                testScope.cancel()
                scheduler.runCurrent()
                check(testScope.coroutineContext.job.isCompleted)
            } finally {
                publisher.detach(session)
                outgoing.close()
            }
        }
    }

    // Sequence 2: Lease recovery sequence
    run {
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
            val requests = listOf(
                Triple("expired", 3.6, 2L),
                Triple("rearm-apply", 3.2, 4L),
                Triple("lease-late", 5.0, 5L),
            )
            for ((index, req) in requests.withIndex()) {
                val (name, value, nonce) = req
                check(publisher.publishTuningRequest(
                    TuningTransport.requested(declaration), TuningTransport.requestNonce(declaration),
                    TuningValue(doubleValue = value), nonce, connection,
                ))
                saveFrames(name, outgoing, expectedBinaries = 1, expectedTexts = if (index == 0) 2 else 0)
            }
        } finally {
            publisher.detach(session)
            outgoing.close()
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> anyWireFrame(): T { ArgumentMatchers.any<T>(); return null as T }
