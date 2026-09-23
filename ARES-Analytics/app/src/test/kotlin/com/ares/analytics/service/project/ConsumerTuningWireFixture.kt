package com.ares.analytics.service.project

import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.TelemetryStore
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.AutoTunerService
import com.ares.analytics.service.TuningApplyState
import com.ares.analytics.service.nt4.Nt4OutboundPublisher
import com.ares.analytics.service.tuning.TuningTransport
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.viewmodel.CalibrationArmPhase
import com.ares.analytics.viewmodel.SysIdIntent
import com.ares.analytics.viewmodel.SysIdState
import com.ares.analytics.viewmodel.SysIdViewModel
import com.ares.analytics.viewmodel.TuningIntent
import com.ares.analytics.viewmodel.TuningViewModel
import com.ares.analytics.viewmodel.sysid.CalibrationCommandTransport
import com.ares.analytics.viewmodel.sysid.SysIdSignalGenerator
import com.areslib.control.assist.SysIdMechanism
import com.areslib.tuning.*
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.io.File
import java.util.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.*

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

internal fun consumerTuningHelpers(): String = """
    private val wireCaptured = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun subscribeWire(server: NT4Server, conn: org.java_websocket.WebSocket) {
        val json = String(java.util.Base64.getDecoder().decode("W3sibWV0aG9kIjoic3Vic2NyaWJlIiwicGFyYW1zIjp7InRvcGljcyI6WyJTeXNJZC8iLCJUdW5pbmcvUGFyYW1ldGVycy9mdGMuZHJpdmUuaGVhZGluZy5rcC8iXSwic3VidWlkIjoxLCJvcHRpb25zIjp7InByZWZpeCI6dHJ1ZX19fV0="), Charsets.UTF_8)
        server.onMessage(conn, json)
    }

    private fun assertTuningResult(nonce: Long, result: String, gain: Double) {
        val root = "Tuning/Parameters/ftc.drive.heading.kp"
        val ack = TuningAcknowledgementCodec.decode(NT4Server.getString(root + "/Acknowledgement", ""))
        assertNotNull(ack)
        assertEquals(nonce, ack!!.nonce)
        assertEquals(result, ack.result)
        assertEquals(result, NT4Server.getString(root + "/LastResult", ""))
        assertEquals(nonce.toDouble(), NT4Server.getDouble(root + "/ProcessedNonce", -1.0), 1e-9)
        assertEquals(gain, NT4Server.getDouble(root + "/Current", -1.0), 1e-9)
        println("TUNING_ACK " + TuningAcknowledgementCodec.encode(ack) + " current=" + NT4Server.getDouble(root + "/Current", -1.0))
    }

    private fun canonicalTuningSnapshot(project: File): Map<String, String> {
        val root = File(project, ".ares")
        return root.walkTopDown().filter { it.isFile && !it.relativeTo(root).invariantSeparatorsPath.startsWith("local/") }
            .associate { it.relativeTo(root).invariantSeparatorsPath to java.util.Base64.getEncoder().encodeToString(it.readBytes()) }
    }

    private fun deliverStudioTuningFrames(server: NT4Server, connection: org.java_websocket.WebSocket, case: String) {
        val resource = requireNotNull(javaClass.getResourceAsStream("/tuning-wire/" + case + ".frames"))
        resource.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val bytes = java.util.Base64.getDecoder().decode(line.substring(2))
                when (line.substring(0, 2)) {
                    "T|" -> server.onMessage(connection, String(bytes, Charsets.UTF_8))
                    "B|" -> server.onMessage(connection, java.nio.ByteBuffer.wrap(bytes))
                    else -> error("Unexpected captured frame")
                }
            }
        }
    }

    private fun saveReplyFrames(server: NT4Server, conn: org.java_websocket.WebSocket, dir: File, name: String) {
        // A real subscription emits a fresh, complete snapshot of the selected topics.
        // Keep announcements/value ordering and snapshot concurrent sends safely.
        wireCaptured.clear()
        subscribeWire(server, conn)
        server.flush()
        dir.mkdirs()
        File(dir, name + ".frames").writeText(wireCaptured.toList().joinToString("\n", postfix = "\n"))
    }

    private fun <T> consumerWireProxy(type: Class<T>): T = type.cast(java.lang.reflect.Proxy.newProxyInstance(
        type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "OwnedConsumerTuningConnection"
                "isOpen" -> true
                "hasBufferedData" -> false
                "send" -> {
                    val arg = args?.firstOrNull()
                    when (arg) {
                        is String -> {
                            val line = "T|" + java.util.Base64.getEncoder().encodeToString(arg.toByteArray(Charsets.UTF_8))
                            wireCaptured.add(line)
                        }
                        is java.nio.ByteBuffer -> {
                            val copy = ByteArray(arg.remaining())
                            arg.duplicate().get(copy)
                            val line = "B|" + java.util.Base64.getEncoder().encodeToString(copy)
                            wireCaptured.add(line)
                        }
                        is ByteArray -> {
                            val line = "B|" + java.util.Base64.getEncoder().encodeToString(arg)
                            wireCaptured.add(line)
                        }
                    }
                    null
                }
                else -> null
            }
        })
""".trimIndent()

internal fun replayNt4Frames(
    client: Nt4ClientService,
    lines: List<String>,
    teamId: String = "test-team",
    seasonId: String = "test-season",
    robotId: String = "test-robot"
) {
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val type = trimmed.substring(0, 1)
        val data = Base64.getDecoder().decode(trimmed.substring(2))
        when (type) {
            "T" -> {
                val text = String(data, Charsets.UTF_8)
                runBlocking { client.handleIncomingText(text, teamId, seasonId, robotId) }
            }
            "B" -> {
                runBlocking { client.handleIncomingBinary(data, teamId, seasonId, robotId) }
            }
            else -> error("Unknown frame type: $type")
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun verifyStudioRobotFeedbackReturnPath(
    project: File,
    replyDir: File
) {
    fun replyFramesProvider(name: String) = File(replyDir, name + ".frames").also {
        check(it.isFile) { "Missing current generated-robot reply: " + it }
    }.readLines()
    val evidence = File(replyDir, "controller-evidence.txt").readLines().associate {
        val pair = it.split("=", limit = 2); pair[0] to pair[1].toDouble()
    }
    val liveGain = 2.6
    val root = "Tuning/Parameters/ftc.drive.heading.kp"
    val canonicalBefore = consumerCanonicalSnapshot(project)
    var ownedClient: Nt4ClientService? = null
    val testScheduler = TestCoroutineScheduler()
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val testScope = CoroutineScope(SupervisorJob() + testDispatcher)

    try {
        val db = Mockito.mock(DatabaseService::class.java)
        val realClient = Nt4ClientService(db)
        val client = Mockito.spy(realClient)
        ownedClient = client
        val connectedFlow = MutableStateFlow(true)
        Mockito.doReturn(connectedFlow).`when`(client).isConnected
        Mockito.doReturn(1L).`when`(client).tuningConnectionId
        Mockito.doReturn(1L).`when`(client).controlConnectionEpoch

        val transport = object : CalibrationCommandTransport {
            val publishedCommands = mutableListOf<String>()
            val publishedTokens = mutableListOf<String>()
            val publishedLeases = mutableListOf<Double>()
            override suspend fun publishString(pubuid: Int, value: String): Boolean {
                if (pubuid == 1015) publishedCommands.add(value)
                if (pubuid == 1016) publishedTokens.add(value)
                return true
            }
            override suspend fun publishDouble(pubuid: Int, value: Double): Boolean {
                if (pubuid == 1017) publishedLeases.add(value)
                return true
            }
        }
        val autoTuner = Mockito.mock(AutoTunerService::class.java)
        Mockito.`when`(autoTuner.applyState).thenReturn(MutableStateFlow(TuningApplyState()))
        val sysIdVm = SysIdViewModel(
            autoTunerService = autoTuner,
            nt4ClientService = client,
            scope = testScope,
            calibrationTransport = transport,
            previewDispatcher = testDispatcher
        )
        testScheduler.runCurrent()

        runBlocking {
            client.telemetryStore.accept(
                TelemetryFrame(timestampMs = 1_000L, sessionId = "live-telemetry", key = "SysId/ModeEnabled", value = 1.0)
            )
            client.telemetryStore.accept(
                TelemetryFrame(timestampMs = 1_000L, sessionId = "live-telemetry", key = "SysId/SupportedMechanisms", value = 0.0, stringValue = "LINEAR")
            )
        }
        testScheduler.runCurrent()

        // =========================================================================
        // Step 1: Withhold robot arm acknowledgement after Studio's explicit arm request.
        // Confirm Studio does NOT present confirmed ARMED merely because publication succeeded.
        // =========================================================================
        sysIdVm.onIntent(SysIdIntent.ArmCalibration)
        testScheduler.runCurrent()
        assertEquals(CalibrationArmPhase.ARMING, sysIdVm.state.value.armPhase,
            "Studio must remain in ARMING while robot arm reply is withheld")
        assertFalse(sysIdVm.state.value.robotCalibrationArmed,
            "robotCalibrationArmed must be false while reply is withheld")
        assertEquals("STOP", transport.publishedCommands.last())
        assertTrue(transport.publishedTokens.last().startsWith("ares-"))
        assertEquals(1.0, transport.publishedLeases.last())

        // =========================================================================
        // Step 2: Feed wire-arm-reply into Studio's production inbound decoding path.
        // Confirm Studio reaches ARMED and robotCalibrationArmed = true.
        // =========================================================================
        val armReplyFrames = replyFramesProvider("wire-arm-reply")
        check(armReplyFrames.isNotEmpty()) { "wire-arm-reply frames must not be empty" }
        replayNt4Frames(client, armReplyFrames)
        testScheduler.runCurrent()
        assertEquals(CalibrationArmPhase.ARMED, sysIdVm.state.value.armPhase,
            "Studio must transition to ARMED after receiving robot arm acknowledgement")
        assertTrue(sysIdVm.state.value.robotCalibrationArmed,
            "robotCalibrationArmed must be true after receiving robot arm acknowledgement")

        // =========================================================================
        // Step 3: Issue one valid heading-gain change through public Studio tuning action.
        // Show request remains pending until matching robot result arrives, then agrees
        // with robot Redux state, Current, and independent controller output expectation.
        // =========================================================================
        // This actual robot snapshot contains processed nonce 4, Current 3.2 and the fresh arm.
        // No local injection may choose the next request's nonce.
        replayNt4Frames(client, replyFramesProvider("wire-ready-reply"))
        testScheduler.runCurrent()
        assertEquals(4.0, client.latestValues[root + "/ProcessedNonce"]?.value)
        assertEquals(3.2, client.latestValues[root + "/Current"]?.value)

        val requestFrame = File(project, "simulator/src/test/resources/tuning-wire/wire-rearm-apply.frames")
            .readLines().single { it.startsWith("B|") }
        val consumedRequest = com.areslib.networktables.NT4WireProtocol.unpackMessageFrames(
            Base64.getDecoder().decode(requestFrame.substring(2)))
        assertEquals(listOf(liveGain, 5.0), consumedRequest.map { it.value })
        var publishedTuningValue: Double? = null
        var publishedTuningNonce: Long? = null
        runBlocking {
            Mockito.doAnswer { inv ->
                val v = inv.getArgument<TuningValue>(1)
                val n = inv.getArgument<Long>(2)
                assertEquals("ftc.drive.heading.kp", inv.getArgument<TuningParameterDeclaration>(0).uid)
                assertEquals(consumedRequest[0].value, v.doubleValue)
                assertEquals(consumedRequest[1].value, n.toDouble())
                publishedTuningValue = v.doubleValue
                publishedTuningNonce = n
                true
            }.`when`(client).publishTuningRequest(anyWireFrame(), anyWireFrame(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong())
        }

        val tuningVm = TuningViewModel(client, testScope, loadDispatcher = testDispatcher, workDispatcher = testDispatcher)
        tuningVm.onIntent(TuningIntent.LoadConstants(project.path))
        testScheduler.runCurrent()
        assertNotNull(tuningVm.state.value.selectedProfile, "Profile must be loaded")
        val headingKey = tuningVm.state.value.catalog.single { it.uid == "ftc.drive.heading.kp" }.key

        // Stage the new live gain
        tuningVm.onIntent(TuningIntent.UpdateTypedConstant(headingKey, TuningValue(doubleValue = liveGain)))
        testScheduler.runCurrent()
        assertEquals(liveGain, tuningVm.state.value.proposals[headingKey]?.doubleValue)

        // Push to robot
        tuningVm.onIntent(TuningIntent.PushToRobot(headingKey))
        testScheduler.runCurrent()
        assertEquals(liveGain, publishedTuningValue)
        assertEquals(5L, publishedTuningNonce)
        assertTrue(tuningVm.state.value.saveStatus.startsWith("Waiting for "),
            "Request must remain pending until matching robot result arrives, was: ${tuningVm.state.value.saveStatus}")
        assertFalse(tuningVm.state.value.saveStatus.contains("applied experimentally"),
            "Status must not report applied before robot reply is processed")

        // An older real acknowledgement must not complete this newer request.
        replayNt4Frames(client, replyFramesProvider("wire-ready-reply"))
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertTrue(tuningVm.state.value.saveStatus.startsWith("Waiting for "))

        // Feed wire-rearm-apply-reply through production inbound path
        val applyReplyFrames = replyFramesProvider("wire-rearm-apply-reply")
        check(applyReplyFrames.isNotEmpty()) { "wire-rearm-apply-reply frames must not be empty" }
        replayNt4Frames(client, applyReplyFrames)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()

        assertTrue(tuningVm.state.value.saveStatus.contains("applied experimentally"),
            "Studio saveStatus must confirm experimental application, was: ${tuningVm.state.value.saveStatus}")
        assertNull(tuningVm.state.value.errorMessage, "There must be no error message")

        // Verify Current in Studio agrees with 2.6
        val currentEntry = client.latestValues["Tuning/Parameters/ftc.drive.heading.kp/Current"]
        assertNotNull(currentEntry, "Current telemetry entry must be present")
        assertEquals(liveGain, currentEntry.value, 1e-9, "Studio Current must agree with liveGain")

        // Compare actual generated-robot observations, not arithmetic against another constant.
        assertEquals(liveGain, evidence.getValue("gain"), 1e-9)
        assertEquals(-0.10, evidence.getValue("heading"), 1e-3)
        assertEquals(0.26, evidence.getValue("omega"), 1e-3)
        assertEquals(canonicalBefore, consumerCanonicalSnapshot(project))

        // =========================================================================
        // Step 4: Disarm through Studio, carry generated robot's feedback back,
        // verify local authorization is revoked, renewal stops, and stale feedback is rejected.
        // =========================================================================
        sysIdVm.onIntent(SysIdIntent.DisarmCalibration("Studio operator disarmed"))
        testScheduler.runCurrent()
        assertEquals(CalibrationArmPhase.DISARMED, sysIdVm.state.value.armPhase)
        assertFalse(sysIdVm.state.value.robotCalibrationArmed)

        // Feed wire-disarm-reply into Studio's production inbound path
        val disarmReplyFrames = replyFramesProvider("wire-final-disarm-reply")
        check(disarmReplyFrames.isNotEmpty()) { "wire-disarm-reply frames must not be empty" }
        replayNt4Frames(client, disarmReplyFrames)
        testScheduler.runCurrent()
        assertEquals(CalibrationArmPhase.DISARMED, sysIdVm.state.value.armPhase)
        assertFalse(sysIdVm.state.value.robotCalibrationArmed)

        // Verify renewal has stopped
        val leasesCountBefore = transport.publishedLeases.size
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(leasesCountBefore, transport.publishedLeases.size, "Lease renewal must remain stopped after disarming")

        // Stale feedback rejection: replaying arm reply while disarmed must not rearm
        replayNt4Frames(client, armReplyFrames)
        testScheduler.runCurrent()
        assertEquals(CalibrationArmPhase.DISARMED, sysIdVm.state.value.armPhase,
            "Replaying arm reply while disarmed must not rearm Studio")
        assertFalse(sysIdVm.state.value.robotCalibrationArmed,
            "robotCalibrationArmed must remain false after stale arm feedback")

    } finally {
        try {
            testScope.cancel()
            testScheduler.runCurrent()
            check(testScope.coroutineContext.job.isCompleted) { "Test scope must complete cleanly" }
        } finally {
            runBlocking {
                kotlinx.coroutines.withTimeout(5_000) {
                    ownedClient?.let { check(it.disposeAndJoin()) { "Owned client did not flush cleanly" } }
                }
            }
        }
    }
}
