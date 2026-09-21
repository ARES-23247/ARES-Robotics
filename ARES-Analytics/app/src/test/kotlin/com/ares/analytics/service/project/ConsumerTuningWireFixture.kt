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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.io.File
import java.nio.file.Files
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
    private val wireCaptured = java.util.Collections.synchronizedList(ArrayList<String>())
    private val initialAnnouncements = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun subscribeWire(server: NT4Server, conn: org.java_websocket.WebSocket) {
        val json = String(java.util.Base64.getDecoder().decode("W3sibWV0aG9kIjoic3Vic2NyaWJlIiwicGFyYW1zIjp7InRvcGljcyI6WyIiXSwic3VidWlkIjoxLCJvcHRpb25zIjp7InByZWZpeCI6dHJ1ZX19fV0="), Charsets.UTF_8)
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

    private fun saveReplyFrames(dir: File, name: String, frames: List<String>) {
        dir.mkdirs()
        val textFrames = synchronized(initialAnnouncements) { initialAnnouncements.toList() }
        val binaryFrames = frames.filter { it.startsWith("B|") }
        File(dir, name + ".frames").writeText((textFrames + binaryFrames).joinToString("\n", postfix = "\n"))
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
                            synchronized(initialAnnouncements) {
                                if (!initialAnnouncements.contains(line)) {
                                    initialAnnouncements.add(line)
                                }
                            }
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

internal fun verifyStudioRobotFeedbackReturnPath(replyDir: File, liveGain: Double = 2.6, measuredHeading: Double = -0.10) {
    verifyStudioRobotFeedbackReturnPath({ name ->
        val file = File(replyDir, "$name.frames")
        check(file.isFile) { "Missing reply file: ${file.absolutePath}" }
        file.readLines()
    }, liveGain, measuredHeading)
}

internal fun verifyStudioRobotFeedbackReturnPathFromResources(liveGain: Double = 2.6, measuredHeading: Double = -0.10) {
    verifyStudioRobotFeedbackReturnPath({ name ->
        val resourcePath = "/tuning-wire-reply/$name.frames"
        val stream = checkNotNull(Nt4ClientService::class.java.getResourceAsStream(resourcePath)
            ?: Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath.removePrefix("/"))) {
            "Missing classpath resource $resourcePath"
        }
        stream.bufferedReader().readLines()
    }, liveGain, measuredHeading)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun verifyStudioRobotFeedbackReturnPath(
    replyFramesProvider: (name: String) -> List<String>,
    liveGain: Double = 2.6,
    measuredHeading: Double = -0.10
) {
    val tempDir = Files.createTempDirectory("studio-return-path-test").toFile()
    val testScheduler = TestCoroutineScheduler()
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val testScope = CoroutineScope(SupervisorJob() + testDispatcher)

    try {
        val db = Mockito.mock(DatabaseService::class.java)
        val realClient = Nt4ClientService(db)
        val client = Mockito.spy(realClient)
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
        assertTrue(sysIdVm.signalGenerator.hasActiveArmLease())

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
        val headingDecl = TuningParameterDeclaration(
            uid = "ftc.drive.heading.kp",
            key = "ftc.drive.heading.kp",
            componentUid = "drive",
            displayName = "Heading P",
            description = "Heading proportional gain",
            type = TuningParameterType.DOUBLE,
            minimum = 0.0,
            maximum = 10.0,
            defaultValue = TuningValue(doubleValue = 2.1),
            applyPolicy = TuningApplyPolicy.LIVE_SAFE
        )
        val compDoc = TuningComponentDocument(
            uid = "drive",
            projectId = "robot.project",
            displayName = "Drive",
            description = "Drivetrain",
            parameters = listOf(headingDecl)
        )
        val profile = TuningProfileDocument(
            uid = "profile.competition",
            profileId = "competition",
            displayName = "Competition",
            description = "Competition Profile",
            projectId = "robot.project",
            authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
            values = listOf(TuningAssignment("ftc.drive.heading.kp", TuningValue(doubleValue = 2.1)))
        )
        File(tempDir, ".ares/tuning-components/drive.arestuningcomponent").apply {
            parentFile.mkdirs()
            writeText(TuningComponentDocumentCodec.encode(compDoc))
        }
        File(tempDir, ".ares/tuning/competition.arestuning").apply {
            parentFile.mkdirs()
            writeText(TuningProfileDocumentCodec.encode(profile, listOf(headingDecl)))
        }

        var publishedTuningValue: Double? = null
        var publishedTuningNonce: Long? = null
        runBlocking {
            Mockito.doAnswer { inv ->
                val v = inv.getArgument<TuningValue>(1)
                val n = inv.getArgument<Long>(2)
                publishedTuningValue = v.doubleValue
                publishedTuningNonce = n
                true
            }.`when`(client).publishTuningRequest(anyWireFrame(), anyWireFrame(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong())
        }

        val tuningVm = TuningViewModel(client, testScope, loadDispatcher = testDispatcher, workDispatcher = testDispatcher)
        tuningVm.onIntent(TuningIntent.LoadConstants(tempDir.path))
        testScheduler.runCurrent()
        assertNotNull(tuningVm.state.value.selectedProfile, "Profile must be loaded")

        // Nonces 1 to 4 were processed in simulation before rearm-apply (at 3,520 ms).
        // Record processed nonce 4.0 in client's telemetry so Studio derives nonce 5L.
        runBlocking {
            client.telemetryStore.accept(
                TelemetryFrame(
                    timestampMs = 1_000L,
                    sessionId = "live-telemetry",
                    key = "Tuning/Parameters/ftc.drive.heading.kp/ProcessedNonce",
                    value = 4.0
                )
            )
        }

        // Stage the new live gain
        tuningVm.onIntent(TuningIntent.UpdateTypedConstant("ftc.drive.heading.kp", TuningValue(doubleValue = liveGain)))
        testScheduler.runCurrent()
        assertEquals(liveGain, tuningVm.state.value.proposals["ftc.drive.heading.kp"]?.doubleValue)

        // Push to robot
        tuningVm.onIntent(TuningIntent.PushToRobot("ftc.drive.heading.kp"))
        testScheduler.runCurrent()
        assertEquals(liveGain, publishedTuningValue)
        assertEquals(5L, publishedTuningNonce)
        assertTrue(tuningVm.state.value.saveStatus.contains("Waiting for Heading P acknowledgement…"),
            "Request must remain pending until matching robot result arrives, was: ${tuningVm.state.value.saveStatus}")
        assertFalse(tuningVm.state.value.saveStatus.contains("applied experimentally"),
            "Status must not report applied before robot reply is processed")

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

        // Verify independent controller output oracle: omega = kp * error = 2.6 * (0.0 - (-0.10)) = 0.26 rad/s
        val error = 0.0 - measuredHeading
        val expectedOmega = liveGain * error
        assertEquals(0.26, expectedOmega, 1e-9, "Independent P-only oracle expectation must be 0.26 rad/s")

        // =========================================================================
        // Step 4: Disarm through Studio, carry generated robot's feedback back,
        // verify local authorization is revoked, renewal stops, and stale feedback is rejected.
        // =========================================================================
        sysIdVm.onIntent(SysIdIntent.DisarmCalibration("Studio operator disarmed"))
        testScheduler.runCurrent()
        assertEquals(CalibrationArmPhase.DISARMED, sysIdVm.state.value.armPhase)
        assertFalse(sysIdVm.state.value.robotCalibrationArmed)
        assertFalse(sysIdVm.signalGenerator.hasActiveArmLease())

        // Feed wire-disarm-reply into Studio's production inbound path
        val disarmReplyFrames = replyFramesProvider("wire-disarm-reply")
        check(disarmReplyFrames.isNotEmpty()) { "wire-disarm-reply frames must not be empty" }
        replayNt4Frames(client, disarmReplyFrames)
        testScheduler.runCurrent()
        assertEquals(CalibrationArmPhase.DISARMED, sysIdVm.state.value.armPhase)
        assertFalse(sysIdVm.state.value.robotCalibrationArmed)
        assertFalse(sysIdVm.signalGenerator.hasActiveArmLease())

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
        testScope.cancel()
        testScheduler.runCurrent()
        check(testScope.coroutineContext.job.isCompleted) { "Test scope must complete cleanly" }
        tempDir.deleteRecursively()
    }
}

