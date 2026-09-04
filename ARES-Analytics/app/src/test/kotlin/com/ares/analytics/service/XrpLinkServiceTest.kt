package com.ares.analytics.service

import com.google.gson.JsonParser
import com.ares.analytics.util.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XrpLinkServiceTest {
    private val tempDir: Path = Files.createTempDirectory("ares-xrp-link-test-")

    @Test
    fun `valid handshake carries control and monotonic telemetry`() = runBlocking {
        val database = DatabaseService(tempDir.resolve("xrp.duckdb").toString())
        val telemetry = Nt4ClientService(database)
        val service = XrpLinkService(telemetry)
        val server = ServerSocket(0)
        val received = mutableListOf<String>()
        val serverJob = launch(Dispatchers.IO) {
            server.accept().use { socket ->
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write(
                        "{\"protocol\":\"ares-xrp/1\",\"type\":\"hello\",\"role\":\"robot\"," +
                        "\"projectId\":\"test-project\",\"contentSha256\":\"${"a".repeat(64)}\"," +
                        "\"drivetrainType\":\"mecanum\",\"boardType\":\"0\"," +
                        "\"micropythonVersion\":\"1.28.0\",\"xrplibVersion\":\"2026.08.2\"," +
                        "\"aresRuntimeVersion\":\"1.0.0\"}\n",
                )
                writer.flush()
                received += socket.getInputStream().bufferedReader().readLine()
                writer.write("{\"protocol\":\"ares-xrp/1\",\"type\":\"telemetry\",\"sequence\":1,\"poseX\":0.25,\"poseY\":-0.1,\"heading\":0.5,\"battery\":5.8,\"loopTimeMs\":7.0,\"faulted\":false,\"mode\":\"TELEOP\",\"subsystems\":{\"rangefinder\":{\"distance\":0.42}}}\n")
                writer.write("{\"protocol\":\"ares-xrp/1\",\"type\":\"telemetry\",\"sequence\":1,\"poseX\":99.0,\"poseY\":99.0,\"heading\":99.0}\n")
                writer.write("{\"protocol\":\"ares-xrp/1\",\"type\":\"telemetry\",\"sequence\":2,\"poseX\":0.5,\"poseY\":-0.2,\"heading\":1.0}\n")
                writer.flush()
                delay(100)
            }
        }
        try {
            service.start("127.0.0.1", server.localPort, "test-project")
            withTimeout(3_000) {
                while (!service.isConnected.value) delay(10)
            }
            assertTrue(service.publishControl("test-session", 3, 7, true, 0.4, 0.0, -0.2))
            withTimeout(3_000) {
                while (telemetry.latestValues["ARES/TruePose/0"]?.value != 0.5) delay(10)
            }
            assertEquals(0.5, telemetry.latestValues["ARES/TruePose/0"]?.value)
            assertEquals(-0.2, telemetry.latestValues["ARES/TruePose/1"]?.value)
            assertEquals("mecanum", service.peerIdentity.value?.drivetrainType)
            assertEquals("1.28.0", service.peerIdentity.value?.micropythonVersion)
            assertEquals("2026.08.2", service.peerIdentity.value?.xrplibVersion)
            assertEquals(7.0, telemetry.latestValues["Robot/LoopTimeMs"]?.value)
            assertEquals(0.42, telemetry.latestValues["Subsystem/rangefinder/distance"]?.value)
            assertTrue(received.single().contains("\"requestRevision\":7"))
            assertTrue(received.single().contains("\"sequence\":3"))
        } finally {
            service.disposeAndJoin()
            telemetry.disposeAndJoin()
            database.closeAndJoin()
            withContext(Dispatchers.IO) { server.close() }
            serverJob.cancel()
        }
    }

    @Test
    fun `canonical XRP field payload receives an exact simulator receipt`() = runBlocking {
        val database = DatabaseService(tempDir.resolve("xrp-field.duckdb").toString())
        val telemetry = Nt4ClientService(database)
        val service = XrpLinkService(telemetry)
        val server = ServerSocket(0)
        val payload = """{"id":"tabletop","revision":4,"fieldType":"xrp","widthMeters":2.54,"heightMeters":1.4224}"""
        val serverJob = launch(Dispatchers.IO) {
            server.accept().use { socket ->
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write(
                    "{\"protocol\":\"ares-xrp/1\",\"type\":\"hello\",\"role\":\"robot\"," +
                        "\"projectId\":\"field-project\",\"contentSha256\":\"${"c".repeat(64)}\"," +
                        "\"drivetrainType\":\"mecanum\"}\n",
                )
                writer.flush()
                val request = JsonParser.parseString(
                    socket.getInputStream().bufferedReader().readLine(),
                ).asJsonObject
                writer.write(
                    "{\"protocol\":\"ares-xrp/1\",\"type\":\"fieldApplied\"," +
                        "\"session\":\"sim-field\",\"sequence\":1," +
                        "\"configId\":\"${request["configId"].asString}\"," +
                        "\"revision\":${request["revision"].asLong}," +
                        "\"sha256\":\"${request["sha256"].asString}\"," +
                        "\"obstacleCount\":2,\"elementCount\":1,\"aprilTagCount\":0}\n",
                )
                writer.flush()
                delay(100)
            }
        }
        try {
            service.start("127.0.0.1", server.localPort, "field-project")
            withTimeout(3_000) {
                while (!service.isConnected.value) delay(10)
            }
            assertTrue(service.publishFieldConfig(payload))
            val receipt = withTimeout(3_000) {
                service.awaitFieldApply("tabletop", 4, Sha256.hex(payload), null)
            }
            assertEquals("sim-field:1", receipt?.eventId)
            assertEquals(2, receipt?.obstacleCount)
            assertEquals(1, receipt?.elementCount)
        } finally {
            service.disposeAndJoin()
            telemetry.disposeAndJoin()
            database.closeAndJoin()
            withContext(Dispatchers.IO) { server.close() }
            serverJob.cancel()
        }
    }

    @Test
    fun `invalid handshake never reports connected`() = runBlocking {
        val database = DatabaseService(tempDir.resolve("invalid.duckdb").toString())
        val telemetry = Nt4ClientService(database)
        val service = XrpLinkService(telemetry)
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            server.accept().use { socket ->
                socket.getOutputStream().bufferedWriter().use { writer ->
                    writer.write("{\"protocol\":\"nt4\",\"type\":\"hello\",\"role\":\"robot\"}\n")
                    writer.flush()
                }
            }
        }
        try {
            service.start("127.0.0.1", server.localPort, "test-project")
            delay(250)
            assertFalse(service.isConnected.value)
            assertTrue(service.connectionError.value?.contains("handshake") == true)
        } finally {
            service.disposeAndJoin()
            telemetry.disposeAndJoin()
            database.closeAndJoin()
            withContext(Dispatchers.IO) { server.close() }
            serverJob.cancel()
        }
    }

    @Test
    fun `handshake rejects telemetry from a different project`() = runBlocking {
        val database = DatabaseService(tempDir.resolve("wrong-project.duckdb").toString())
        val telemetry = Nt4ClientService(database)
        val service = XrpLinkService(telemetry)
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            server.accept().use { socket ->
                socket.getOutputStream().bufferedWriter().use { writer ->
                    writer.write(
                        "{\"protocol\":\"ares-xrp/1\",\"type\":\"hello\",\"role\":\"robot\"," +
                            "\"projectId\":\"another-robot\",\"contentSha256\":\"${"b".repeat(64)}\"," +
                            "\"drivetrainType\":\"differential\"}\n",
                    )
                    writer.flush()
                }
            }
        }
        try {
            service.start("127.0.0.1", server.localPort, "expected-robot")
            withTimeout(3_000) {
                while (service.connectionError.value?.contains("another-robot") != true) delay(10)
            }
            assertFalse(service.isConnected.value)
            assertEquals(null, service.peerIdentity.value)
        } finally {
            service.disposeAndJoin()
            telemetry.disposeAndJoin()
            database.closeAndJoin()
            withContext(Dispatchers.IO) { server.close() }
            serverJob.cancel()
        }
    }
}
