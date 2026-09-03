package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.SessionSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Nt4ClientServiceTest class.
 */
class Nt4ClientServiceTest {
    @Test
    fun `packed drive acknowledgement decodes receiver ownership and applied command`() {
        val acknowledgement = decodeDriveInputAcknowledgement(
            doubleArrayOf(1.0, 3.0, 42.0, 17.0, 12.0, 0.5, -0.25, 1.2, 2.0),
            timestampMs = 1_234L,
        )

        requireNotNull(acknowledgement)
        assertEquals(3, acknowledgement.statusCode)
        assertEquals(42L, acknowledgement.acceptedSession)
        assertEquals(17L, acknowledgement.acceptedSequence)
        assertEquals(12L, acknowledgement.leaseAgeMs)
        assertEquals(0.5, acknowledgement.appliedVx)
        assertEquals(-0.25, acknowledgement.appliedVy)
        assertEquals(1.2, acknowledgement.appliedOmega)
        assertEquals(2L, acknowledgement.rejectedFrameCount)
        assertEquals(1_234L, acknowledgement.timestampMs)
    }

    @Test
    fun `packed mecanum frame preserves wheel order and same-tick values`() {
        val frame = decodeMecanumMotorFrame(
            doubleArrayOf(
                0.1, 0.2, 0.3, 0.4,
                100.0, 200.0, 300.0, 400.0,
                1.1, 1.2, 1.3, 1.4,
                9.0,
            ),
            timestampMs = 2_345L,
        )

        requireNotNull(frame)
        assertEquals(0.1, frame.flPower)
        assertEquals(0.4, frame.rrPower)
        assertEquals(300.0, frame.rlVelocity)
        assertEquals(1.2, frame.frCurrentAmps)
        assertEquals(9L, frame.sequence)
        assertEquals(2_345L, frame.timestampMs)
    }

    @Test
    fun `packed control and motor telemetry rejects malformed frames`() {
        assertNull(decodeDriveInputAcknowledgement(DoubleArray(8), 1L))
        assertNull(decodeMecanumMotorFrame(DoubleArray(12), 1L))
        assertNull(
            decodeDriveInputAcknowledgement(
                doubleArrayOf(1.0, 3.5, 1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 0.0),
                1L,
            )
        )
    }

    @Test
    fun `dashboard drive control accepts only loopback target hosts`() {
        assertTrue(isLoopbackDriveControlHost("127.0.0.1"))
        assertTrue(isLoopbackDriveControlHost("localhost"))
        assertTrue(isLoopbackDriveControlHost("[::1]"))
        assertFalse(isLoopbackDriveControlHost("192.168.43.1"))
        assertFalse(isLoopbackDriveControlHost("10.232.47.2"))
        assertFalse(isLoopbackDriveControlHost("robot.local"))
    }

    @Test
    fun `dashboard driver station commands are rejected for physical targets`() {
        assertTrue(isDashboardDriverStationCommandAllowed("127.0.0.1", "ARES/DriverStation/Command"))
        assertTrue(isDashboardDriverStationCommandAllowed("localhost", "/ARES/DriverStation/SelectedOpMode"))
        assertTrue(isDashboardDriverStationCommandAllowed("127.0.0.1", "ARES/Input/selectedAuto"))
        assertFalse(isDashboardDriverStationCommandAllowed("192.168.43.1", "ARES/DriverStation/Command"))
        assertFalse(isDashboardDriverStationCommandAllowed("10.23.247.2", "ARES/DriverStation/MatchState"))
        assertFalse(isDashboardDriverStationCommandAllowed("192.168.43.1", "ARES/Input/selectedAuto"))
        assertTrue(isDashboardDriverStationCommandAllowed("192.168.43.1", "Camera/SelectedPipeline"))
    }

    private lateinit var tempDb: File
    private lateinit var databaseService: DatabaseService
    private lateinit var nt4ClientService: Nt4ClientService

    @BeforeTest
    /**
     * setUp fun.
     */
    fun setUp() {
        tempDb = File.createTempFile("nt4_test_db", ".db").apply { deleteOnExit() }
        databaseService = DatabaseService(tempDb.absolutePath)
        nt4ClientService = Nt4ClientService(databaseService)
    }

    @AfterTest
    /**
     * tearDown fun.
     */
    fun tearDown() {
        // stop() is now suspend (it cancelAndJoins the WS loop before closing clients).
        runBlocking { nt4ClientService.stop() }
        tempDb.delete()
    }

    @Test
    /**
     * testAnnounceAndUnannounce fun.
     */
    fun testAnnounceAndUnannounce() = runBlocking {
        // 1. Send announce payload
        val announcePayload = """
            [
              {
                "method": "announce",
                "params": {
                  "name": "/Drive/Pose_X",
                  "id": 42,
                  "type": "double"
                }
              }
            ]
        """.trimIndent()

        nt4ClientService.handleIncomingText(announcePayload, "team-1", "season-1", "robot-1")
        val topic = nt4ClientService.topicMap[42]
        assertTrue(topic != null)
        assertEquals("/Drive/Pose_X", topic.name)
        assertEquals(42, topic.id)
        assertEquals("double", topic.type)

        // 2. Send unannounce payload
        val unannouncePayload = """
            [
              {
                "method": "unannounce",
                "params": {
                  "id": 42
                }
              }
            ]
        """.trimIndent()

        nt4ClientService.handleIncomingText(unannouncePayload, "team-1", "season-1", "robot-1")
        assertTrue(nt4ClientService.topicMap[42] == null)
    }

    @Test
    /**
     * testSingleValueDataUpdate fun.
     */
    fun testSingleValueDataUpdate() = runBlocking {
        // Announce topic first
        val announcePayload = """
            [
              {"method": "announce", "params": {"name": "/Drive/Pose_X", "id": 10, "type": "double"}}
            ]
        """.trimIndent()
        nt4ClientService.handleIncomingText(announcePayload, "team-1", "season-1", "robot-1")

        // Send value frame
        val valuePayload = """
            [
              {"topic": 10, "time": 1000000, "value": 1.25}
            ]
        """.trimIndent()

        withTimeout(2000) {
            nt4ClientService.handleIncomingText(valuePayload, "team-1", "season-1", "robot-1")
            val frame = nt4ClientService.telemetryFlow.first()
            assertEquals("Drive/Pose_X", frame.key)
            assertEquals(1.25, frame.value)
            assertEquals(1000L, frame.timestampMs) // 1000000 micros = 1000 ms
        }
    }

    @Test
    fun `live rewind persists receipt time instead of a stale retained source timestamp`() = runBlocking {
        nt4ClientService.handleIncomingText(
            """[{"method":"announce","params":{"name":"/Drive/Pose_X","id":10,"type":"double"}}]""",
            "team-1",
            "season-1",
            "robot-1"
        )
        nt4ClientService.handleIncomingText(
            """[{"topic":10,"time":1000000,"value":1.25}]""",
            "team-1",
            "season-1",
            "robot-1"
        )
        assertTrue(nt4ClientService.flushPendingFrames())

        val persisted = databaseService.getTelemetryForKey(
            Nt4ClientService.LIVE_SESSION_ID,
            "Drive/Pose_X"
        ).single()
        val receiptAgeMs = System.currentTimeMillis() - persisted.timestampMs
        assertTrue(
            persisted.timestampMs > 1_000_000_000_000L,
            "Live persistence kept the stale source timestamp ${persisted.timestampMs}"
        )
        assertTrue(
            receiptAgeMs in 0L..5_000L,
            "Live persistence receipt time is outside the test window: age=$receiptAgeMs ms"
        )
        assertEquals(1.25, persisted.value)
    }

    @Test
    /**
     * testArrayValueDataUpdate fun.
     */
    fun testArrayValueDataUpdate() = runBlocking {
        // Announce array topic
        val announcePayload = """
            [
              {"method": "announce", "params": {"name": "/Drive/EstimatedPose", "id": 20, "type": "double[]"}}
            ]
        """.trimIndent()
        nt4ClientService.handleIncomingText(announcePayload, "team-1", "season-1", "robot-1")

        // Send array update
        val valuePayload = """
            [
              {"topic": 20, "time": 2000000, "value": [1.5, -2.5, 3.14]}
            ]
        """.trimIndent()
        val results = mutableListOf<TelemetryFrame>()

        // Let's capture the emitted frames from telemetryFlow
        val job = launch {
            nt4ClientService.telemetryFlow.collect {
                results.add(it)
            }
        }

        nt4ClientService.handleIncomingText(valuePayload, "team-1", "season-1", "robot-1")
        kotlinx.coroutines.delay(200)
        job.cancel()

        assertEquals(3, results.size)

        assertEquals("Drive/EstimatedPose/0", results[0].key)
        assertEquals(1.5, results[0].value)

        assertEquals("Drive/EstimatedPose/1", results[1].key)
        assertEquals(-2.5, results[1].value)

        assertEquals("Drive/EstimatedPose/2", results[2].key)
        assertEquals(3.14, results[2].value)
    }

    @Test
    /**
     * Console topics are routed to the console flow only — they must not fall through and
     * double-persist as telemetry frames under the same key.
     */
    fun `console topics emit console messages without telemetry frames`() = runBlocking {
        nt4ClientService.handleIncomingText(
            """[{"method": "announce", "params": {"name": "/Robot/Console", "id": 30, "type": "string"}}]""",
            "team-1", "season-1", "robot-1"
        )

        val consoleMessage = async(start = CoroutineStart.UNDISPATCHED) {
            nt4ClientService.consoleFlow.first()
        }
        val telemetryFrames = mutableListOf<TelemetryFrame>()
        val collector = launch {
            nt4ClientService.telemetryFlow.collect { telemetryFrames.add(it) }
        }

        nt4ClientService.handleIncomingText(
            """[{"topic": 30, "time": 3000000, "value": "[WARN] intake overcurrent"}]""",
            "team-1", "season-1", "robot-1"
        )

        val console = withTimeout(2000) { consoleMessage.await() }
        assertEquals("[WARN] intake overcurrent", console.text)
        assertEquals("WARN", console.severity)

        // Give any (incorrect) telemetry emission a window to arrive, then assert none did.
        delay(200)
        collector.cancel()
        assertTrue(telemetryFrames.none { it.key.startsWith("Robot/Console") })
    }

    @Test
    /**
     * Console matching is exact (case-insensitive): a topic that merely contains "console"
     * must still flow through the ordinary telemetry path.
     */
    fun `console-like topic names still flow as telemetry`() = runBlocking {
        nt4ClientService.handleIncomingText(
            """[{"method": "announce", "params": {"name": "/Robot/ConsoleStatus", "id": 31, "type": "double"}}]""",
            "team-1", "season-1", "robot-1"
        )

        nt4ClientService.handleIncomingText(
            """[{"topic": 31, "time": 4000000, "value": 7.0}]""",
            "team-1", "season-1", "robot-1"
        )

        val frame = withTimeout(2000) {
            nt4ClientService.telemetryFlow.first { it.key == "Robot/ConsoleStatus" }
        }
        assertEquals(7.0, frame.value)
    }

    @Test
    /**
     * testMalformedPayloadResilience fun.
     */
    fun testMalformedPayloadResilience() = runBlocking {
        // Verify that malformed JSON payloads do not propagate errors or crash the service
        nt4ClientService.handleIncomingText("{invalid_json", "team-1", "season-1", "robot-1")
        nt4ClientService.handleIncomingText("[{method: 'non-existing'}]", "team-1", "season-1", "robot-1")
        assertEquals(2L, nt4ClientService.malformedTextFrameCount.get())
    }

    @Test
    fun `binary publish uses the standard NT4 tuple stream`() {
        val encoded = nt4ClientService.encodeNt4BinaryUpdate(
            pubuid = 1001,
            timestampUs = 0x0102030405060708L,
            typeId = 1,
            valueBytes = byteArrayOf(0xca.toByte(), 0xfe.toByte())
        )

        assertEquals(0x94.toByte(), encoded[0], "four-element update tuple header")
        assertEquals(0xcd.toByte(), encoded[1], "pubuid uint16 marker")
        assertEquals(0xcf.toByte(), encoded[4], "timestamp uint64 marker")
        assertEquals(1.toByte(), encoded[13], "NT4 type id")
        assertTrue(encoded.takeLast(2).toByteArray().contentEquals(byteArrayOf(0xca.toByte(), 0xfe.toByte())))
    }

    @Test
    fun `analytics publisher is decoded by the shared NT4 wire codec`() {
        val encodedDouble = ByteBuffer.allocate(9)
            .put(0xcb.toByte())
            .putDouble(6.25)
            .array()
        val encoded = nt4ClientService.encodeNt4BinaryUpdate(
            pubuid = 1010,
            timestampUs = 2_500_000L,
            typeId = 1,
            valueBytes = encodedDouble
        )

        val decoded = com.areslib.networktables.NT4WireProtocol.unpackMessageFrames(encoded)

        assertEquals(1, decoded.size)
        assertEquals(1010L, decoded.single().topicId)
        assertEquals(2_500_000L, decoded.single().timestampUs)
        assertEquals(1, decoded.single().typeId)
        assertEquals(6.25, decoded.single().value)
    }

    @Test
    fun `shared NT4 server wire frame is consumed by analytics`() = runBlocking {
        nt4ClientService.topicMap[42] = com.ares.analytics.service.nt4.Nt4Topic(
            id = 42,
            name = "/Drive/Pose_X",
            type = "double"
        )
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            nt4ClientService.telemetryFlow.first()
        }

        nt4ClientService.handleIncomingBinary(
            bytes = com.areslib.networktables.NT4WireProtocol.encodeValueMessage(
                topicId = 42L,
                timestampUs = 3_000_000L,
                typeId = 1,
                value = 2.75
            ),
            teamId = "team-1",
            seasonId = "season-1",
            robotId = "robot-1"
        )
        val frame = withTimeout(2_000) { received.await() }

        assertEquals("Drive/Pose_X", frame.key)
        assertEquals(3_000L, frame.timestampMs)
        assertEquals(2.75, frame.value)
    }

    @Test
    fun `canonical subscription prefixes cover every ARES publisher family`() {
        val prefixes = Nt4ClientService.CANONICAL_SUBSCRIPTION_PREFIXES
        listOf(
            "ARES", "Drive", "Robot", "Hardware", "Topology", "Tuning",
            "Profiling", "Diagnostics", "Vision", "Path", "Gamepad1", "Gamepad2",
            "Superstructure", "Subsystems", "Calibration", "SysId", "Swerve"
        ).forEach { prefix -> assertTrue(prefix in prefixes, "missing subscription for $prefix") }
        assertTrue(prefixes.none { it.startsWith('/') }, "canonical subscriptions must match slash-free publishers")
    }

    @Test
    fun `boolean telemetry is coerced to numeric one and zero`() {
        assertEquals(1.0, nt4ClientService.coerceTelemetryValue(true).first)
        assertEquals(0.0, nt4ClientService.coerceTelemetryValue(false).first)
        assertEquals(1.0, nt4ClientService.coerceTelemetryValue(JsonPrimitive(true)).first)
        assertEquals(0.0, nt4ClientService.coerceTelemetryValue(JsonPrimitive(false)).first)
    }

    @Test
    fun `atomic drive frame is not recorded before transport readiness`() {
        runBlocking {
            val values = doubleArrayOf(2.0, 42.0, 7.0, 1_000.0, 0.0, 0.0, 0.0, 56.0)
            val received = async(start = CoroutineStart.UNDISPATCHED) {
                nt4ClientService.telemetryFlow.first()
            }

            assertFalse(
                nt4ClientService.publishDriveFrame(values),
                "a locally accepted frame must not be reported as transmitted before clock sync"
            )

            assertNull(withTimeoutOrNull(100) { received.await() }, "unsent controls must not enter telemetry")
            received.cancel()
            assertFailsWith<IllegalArgumentException> {
                nt4ClientService.publishDriveFrame(values.copyOf().also {
                    it[2] = 8.0
                    it[3] = 1_001.0
                    it[4] = 1.0
                })
            }
        }
    }

    @Test
    fun `scalar controls and malformed atomic frames are rejected`() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                nt4ClientService.publishDouble("ARES/Input/vx", 1.0)
            }
            assertFailsWith<IllegalArgumentException> {
                nt4ClientService.publishString("ARES/Input/isIntaking", "true")
            }
            nt4ClientService.publishString("ARES/Input/fieldConfig", "{}")
            nt4ClientService.publishString("ARES/Input/obstacles", "[]")
            nt4ClientService.publishString("ARES/Input/selectedAuto", "light-practice")
            assertFailsWith<IllegalArgumentException> {
                nt4ClientService.publishDriveFrame(
                    doubleArrayOf(2.0, 1.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0)
                )
            }
            assertFailsWith<IllegalArgumentException> {
                nt4ClientService.publishDriveFrame(
                    doubleArrayOf(2.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1_024.0)
                )
            }
            assertFailsWith<IllegalArgumentException> {
                nt4ClientService.publishDriveFrame(
                    doubleArrayOf(2.0, 1.0, 0.0, 1.0, 8.01, 0.0, 0.0, 0.0)
                )
            }
        }
    }

    @Test
    fun `drive frame validator enforces neutral first and ordered session state`() {
        val validator = DriveFrameContractValidator()
        val modeFlags = 56.0 // teleop + field-centric + red alliance are non-actuating
        val first = doubleArrayOf(2.0, 10.0, 0.0, 100.0, 0.0, 0.0, 0.0, modeFlags)
        validator.commit(validator.validate(first))

        val motion = doubleArrayOf(2.0, 10.0, 1.0, 101.0, 1.0, -0.5, 0.25, modeFlags)
        validator.commit(validator.validate(motion))
        assertFailsWith<IllegalArgumentException> { validator.validate(motion) }
        assertFailsWith<IllegalArgumentException> {
            validator.validate(doubleArrayOf(2.0, 10.0, 2.0, 100.0, 0.0, 0.0, 0.0, modeFlags))
        }
        assertFailsWith<IllegalArgumentException> {
            validator.validate(doubleArrayOf(2.0, 11.0, 0.0, 102.0, 0.1, 0.0, 0.0, modeFlags))
        }
        assertFailsWith<IllegalArgumentException> {
            validator.validate(doubleArrayOf(2.0, 11.0, 0.0, 102.0, 0.0, 0.0, 0.0, modeFlags + 64.0))
        }

        validator.reset()
        validator.validate(first)
    }

    @Test
    fun `alliance selection survives publisher and view lifecycles`() = runBlocking {
        assertTrue(nt4ClientService.selectedRedAlliance.value)

        nt4ClientService.selectRedAlliance(false)

        assertFalse(nt4ClientService.selectedRedAlliance.value)
    }

    @Test
    fun `failed database flush retains frames for ordered retry`() = runBlocking {
        nt4ClientService.publishFrame(
            com.ares.analytics.shared.models.TelemetryFrame(100L, "ignored", "Drive/Pose_X", 1.0)
        )
        databaseService.close()

        assertFalse(nt4ClientService.stop())
        assertEquals(1, nt4ClientService.retainedRetryFrameCount())
    }

    @Test
    fun `recording is exclusive and stop finalizes its persisted duration`() = runBlocking {
        val recording = nt4ClientService.startRecordingSession(
            teamId = "23247",
            seasonId = "2026",
            robotId = "sim-robot",
            tags = listOf("simulation", "studio-experiment"),
        )

        assertFailsWith<IllegalStateException> {
            nt4ClientService.startRecordingSession("23247", "2026", "sim-robot")
        }
        delay(5)
        assertTrue(nt4ClientService.stop())

        assertNull(nt4ClientService.currentSession.value)
        val persisted = databaseService.getSessions().single { it.sessionId == recording.sessionId }
        assertTrue(persisted.durationMs > 0L)
        assertTrue("simulation" in persisted.tags)
    }

    @Test
    fun `recording stop runs analytics finalization and persists enriched tags`() = runBlocking {
        var finalizedSessionId: String? = null
        val client = Nt4ClientService(databaseService) { session ->
            finalizedSessionId = session.sessionId
            databaseService.insertSessionSummary(
                SessionSummary(
                    sessionId = session.sessionId,
                    teamId = session.teamId,
                    seasonId = session.seasonId,
                    robotId = session.robotId,
                    createdAt = session.createdAt,
                    durationMs = session.durationMs,
                    avgLoopTimeMs = 20.0,
                    tags = session.tags + "summarized",
                )
            )
            session.copy(tags = session.tags + "summarized")
        }
        val recording = client.startRecordingSession("23247", "2026", "sim-robot", tags = listOf("simulation"))
        client.publishFrame(TelemetryFrame(100L, "ignored", "Diagnostics/LoopTimeMs", 20.0))

        client.stopRecordingSession()

        assertEquals(recording.sessionId, finalizedSessionId)
        assertTrue("summarized" in databaseService.getSessions().single { it.sessionId == recording.sessionId }.tags)
        assertEquals(20.0, databaseService.getSessionSummary(recording.sessionId)?.avgLoopTimeMs)
        client.stop()
        Unit
    }

    @Test
    fun `stop prevents a queued start from reconnecting afterward`() = runBlocking {
        nt4ClientService.start("127.0.0.1", "team", "season", "robot", port = 1)

        assertTrue(nt4ClientService.stop())
        val attemptsAtStop = nt4ClientService.connectionMetrics().attempts
        delay(300)

        assertEquals(attemptsAtStop, nt4ClientService.connectionMetrics().attempts)
        assertFalse(nt4ClientService.isConnected.value)
    }

    @Test
    fun `terminal disposal rejects Compose restart during desktop shutdown`() = runBlocking {
        assertTrue(nt4ClientService.disposeAndJoin())
        val attemptsAtDisposal = nt4ClientService.connectionMetrics().attempts

        nt4ClientService.start("127.0.0.1", "team", "season", "robot", port = 1)
        delay(300)

        assertEquals(attemptsAtDisposal, nt4ClientService.connectionMetrics().attempts)
        assertFalse(nt4ClientService.isConnected.value)
    }

    @Test
    fun `stop completes while a healthy server keeps the websocket open`() = runBlocking {
        val port = 5_827
        val server = NT4Instance.defaultInstance.startServer("127.0.0.1", port)
        try {
            NT4Server.publishTopic("Drive/Pose_X", 1.25)
            nt4ClientService.start("127.0.0.1", "team", "season", "robot", port)
            withTimeout(5_000) { nt4ClientService.isConnected.first { it } }

            withTimeout(3_000) { assertTrue(nt4ClientService.stop()) }

            assertFalse(nt4ClientService.isConnected.value)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `target reset clears topics latest values and history`() = runBlocking {
        nt4ClientService.topicMap[1] = com.ares.analytics.service.nt4.Nt4Topic(1, "/Old/Value", "double")
        val frame = com.ares.analytics.shared.models.TelemetryFrame(1L, "live-telemetry", "Old/Value", 2.0)
        nt4ClientService.telemetryStore.accept(frame)
        assertEquals(2.0, withTimeout(1_000) { nt4ClientService.uiTelemetryFlow.first() }.value)

        nt4ClientService.clearLiveTargetState()

        assertTrue(nt4ClientService.topicMap.isEmpty())
        assertNull(nt4ClientService.telemetryStore.latest(frame.key))
        assertTrue(nt4ClientService.telemetryStore.history(frame.key).isEmpty())
        assertTrue(nt4ClientService.getActiveTopics().isEmpty())
        assertNull(withTimeoutOrNull(100) { nt4ClientService.uiTelemetryFlow.first() })
    }
}
