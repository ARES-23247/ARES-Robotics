package com.ares.analytics.service

import com.areslib.networktables.NT4WireProtocol
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertTrue

/**
 * Connects to a LIVE running NT4Server on 127.0.0.1:5810
 * and monitors what frames arrive over 5 seconds.
 * Run this while the sim is running to diagnose real-world connectivity.
 */
class Nt4LiveWireDiagnosticTest {

    private data class WireSample(
        val receivedAtNs: Long,
        val serverTimestampUs: Long,
        val value: Double,
    )

    @Test
    fun testLiveWireConnectivity() = runBlocking {
        val client = HttpClient(OkHttp) { install(WebSockets) }
        val topicMap = ConcurrentHashMap<Int, String>()
        val receivedAnnounces = mutableListOf<String>()
        val receivedBinaryTopics = mutableListOf<String>()
        val latestValues = ConcurrentHashMap<String, Any?>()
        val updateCounts = ConcurrentHashMap<String, Int>()
        val poseSamples = ConcurrentHashMap<String, CopyOnWriteArrayList<WireSample>>()
        val packedPoseSamples = CopyOnWriteArrayList<Pair<Long, DoubleArray>>()
        var textFrameCount = 0
        var binaryFrameCount = 0
        var announceCount = 0
        var motionStartedAtNs = Long.MIN_VALUE
        var motionStoppedAtNs = Long.MIN_VALUE

        println("=== LIVE WIRE DIAGNOSTIC: Connecting to ws://127.0.0.1:5810 ===")

        val result = try {
            withTimeoutOrNull(if (System.getenv("ARES_LIVE_POSE_MOTION") == "1") 15_000 else 7_000) {
                client.webSocket(
                    method = io.ktor.http.HttpMethod.Get,
                    host = "127.0.0.1",
                    port = 5810,
                    path = "/nt/ARES-Diagnostic-${System.currentTimeMillis()}",
                    request = {
                        headers.append("Sec-WebSocket-Protocol", "v4.1.networktables.first.wpi.edu")
                    }
                ) {
                    println("[DIAG] WebSocket connected!")

                    val subMsg = """
                        [{"method": "subscribe", "params": {"topics": [""], "subuid": 1, "options": {"prefix": true}}}]
                    """.trimIndent()
                    send(Frame.Text(subMsg))

                    if (System.getenv("ARES_LIVE_START_FTC_TELEOP") == "1") {
                        send(
                            Frame.Text(
                                """[{"method":"publish","params":{"name":"ARES/DriverStation/SelectedOpMode","pubuid":2091,"type":"string"}},{"method":"publish","params":{"name":"ARES/DriverStation/Command","pubuid":2092,"type":"string"}}]"""
                            )
                        )
                        delay(100)
                        send(
                            Frame.Binary(
                                true,
                                NT4WireProtocol.encodeValueMessage(
                                    2091,
                                    0,
                                    4,
                                    "org.firstinspires.ftc.teamcode.opmodes.ARESMecanumTeleOp",
                                ),
                            )
                        )
                        send(Frame.Binary(true, NT4WireProtocol.encodeValueMessage(2092, 0, 4, "INIT")))
                        delay(2_000)
                        send(Frame.Binary(true, NT4WireProtocol.encodeValueMessage(2092, 0, 4, "START")))
                        println("[DIAG] Requested FTC ARESMecanumTeleOp INIT -> START")
                    }

                    val readJob = launch {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    textFrameCount++
                                    val text = frame.readText()
                                    try {
                                        val jsonArray = Json.parseToJsonElement(text).jsonArray
                                        for (element in jsonArray) {
                                            val obj = element.jsonObject
                                            val method = obj["method"]?.jsonPrimitive?.content
                                            if (method == "announce") {
                                                val params = obj["params"]?.jsonObject
                                                val name = params?.get("name")?.jsonPrimitive?.content ?: "?"
                                                val id = params?.get("id")?.jsonPrimitive?.intOrNull ?: -1
                                                val type = params?.get("type")?.jsonPrimitive?.content ?: "?"
                                                topicMap[id] = name
                                                receivedAnnounces.add("$name (id=$id, type=$type)")
                                                announceCount++
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                                is Frame.Binary -> {
                                    binaryFrameCount++
                                    val bytes = frame.readBytes()
                                    try {
                                        val messages = NT4WireProtocol.unpackMessageFrames(bytes)
                                        for (msg in messages) {
                                            val topicName = (topicMap[msg.topicId.toInt()] ?: "UNKNOWN(id=${msg.topicId})")
                                                .removePrefix("/")
                                            receivedBinaryTopics.add(topicName)
                                            msg.value?.let { latestValues[topicName] = it }
                                            updateCounts.compute(topicName) { _, count -> (count ?: 0) + 1 }
                                            if (topicName == "ARES/SimulatorPoseFrame") {
                                                val packedValues = (msg.value as? List<*>)
                                                    ?.mapNotNull { (it as? Number)?.toDouble() }
                                                    ?.takeIf { it.size == 10 }
                                                    ?.toDoubleArray()
                                                if (packedValues != null) {
                                                    packedPoseSamples.add(System.nanoTime() to packedValues)
                                                }
                                            }
                                            val numericValue = msg.value as? Number
                                            if (topicName in POSE_DIAGNOSTIC_TOPICS && numericValue != null) {
                                                poseSamples.computeIfAbsent(topicName) { CopyOnWriteArrayList() }
                                                    .add(
                                                        WireSample(
                                                            receivedAtNs = System.nanoTime(),
                                                            serverTimestampUs = msg.timestampUs,
                                                            value = numericValue.toDouble(),
                                                        )
                                                    )
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                                else -> {}
                            }
                        }
                    }

                    if (System.getenv("ARES_LIVE_POSE_MOTION") == "1") {
                        val drivePublisherUid = 2_093
                        send(
                            Frame.Text(
                                """[{"method":"publish","params":{"name":"ARES/Input/driveFrame","pubuid":$drivePublisherUid,"type":"double[]"}}]"""
                            )
                        )
                        delay(100)
                        val driveFrame = DoubleArray(8)
                        val sessionNonce = (System.currentTimeMillis() % 1_000_000_000L) + 1L
                        var sequence = 0L
                        suspend fun publishDrive(
                            vxMetersPerSecond: Double,
                            vyMetersPerSecond: Double,
                            omegaRadiansPerSecond: Double,
                        ) {
                            driveFrame[0] = 2.0
                            driveFrame[1] = sessionNonce.toDouble()
                            driveFrame[2] = sequence++.toDouble()
                            driveFrame[3] = (System.nanoTime() / 1_000_000L).toDouble()
                            driveFrame[4] = vxMetersPerSecond
                            driveFrame[5] = vyMetersPerSecond
                            driveFrame[6] = omegaRadiansPerSecond
                            driveFrame[7] = 56.0 // TeleOp + field-centric + red alliance
                            send(
                                Frame.Binary(
                                    true,
                                    encodeDoubleArrayValueMessage(
                                        topicId = drivePublisherUid.toLong(),
                                        timestampUs = 0,
                                        values = driveFrame,
                                    ),
                                )
                            )
                        }

                        repeat(6) {
                            publishDrive(0.0, 0.0, 0.0)
                            delay(20)
                        }
                        motionStartedAtNs = System.nanoTime()
                        val curvedMotion = System.getenv("ARES_LIVE_POSE_CURVE") == "1"
                        repeat(150) {
                            if (curvedMotion) {
                                publishDrive(1.0, 0.8, 1.5)
                            } else {
                                publishDrive(0.0, 1.5, 0.0)
                            }
                            delay(20)
                        }
                        motionStoppedAtNs = System.nanoTime()
                        repeat(6) {
                            publishDrive(0.0, 0.0, 0.0)
                            delay(20)
                        }
                        delay(500)
                    } else {
                        delay(5_000)
                    }
                    readJob.cancel()
                }
                "OK"
            }
        } catch (_: Exception) {
            println("[DIAG] Live simulator server not running on port 5810 (skipping live-wire test).")
            null
        }

        if (result != null) {
            assertTrue(announceCount > 0, "Expected at least 1 announce from the server")
            println("[DIAG] Text frames=$textFrameCount, binary frames=$binaryFrameCount, announces=$announceCount")
            println(
                "[DIAG] Relevant announced topics=" +
                    topicMap.values
                        .filter { name ->
                            name.contains("ARES", ignoreCase = true) ||
                                name.contains("DriverStation", ignoreCase = true) ||
                                name.contains("Pose", ignoreCase = true)
                        }
                        .sorted()
                        .joinToString()
            )
            listOf(
                "ARES/Input/driveFrame",
                "ARES/DriverStation/Command",
                "ARES/DriverStation/ActiveOpModeState",
                "ARES/DriverStation/SelectedOpMode",
                "ARES/TruePose",
                "ARES/SimulatorPoseFrame",
                "ARES/TruePose/0",
                "ARES/TruePose/1",
                "ARES/TruePose/2",
                "ARES/EstimatedPose/0",
                "ARES/EstimatedPose/1",
                "ARES/EstimatedPose/2",
                "Drive/Pose_X",
                "Drive/Pose_Y",
                "Drive/Pose_Heading",
                "Drive/Odom_X",
                "Drive/Odom_Y",
                "Drive/Odom_Heading",
            ).forEach { topic ->
                println("[DIAG] $topic updates=${updateCounts[topic] ?: 0}, latest=${latestValues[topic]}")
            }
            if (motionStartedAtNs != Long.MIN_VALUE && motionStoppedAtNs != Long.MIN_VALUE) {
                println("[DIAG] Active-motion pose interception:")
                printPackedPoseError(
                    packedPoseSamples.filter { (receivedAtNs, _) ->
                        receivedAtNs in motionStartedAtNs..motionStoppedAtNs
                    }.map { it.second }
                )
                POSE_DIAGNOSTIC_TOPICS.forEach { topic ->
                    val samples = poseSamples[topic].orEmpty()
                    val active = samples.filter { it.receivedAtNs in motionStartedAtNs..motionStoppedAtNs }
                    val timestampSpan = active.lastOrNull()?.serverTimestampUs?.minus(
                        active.firstOrNull()?.serverTimestampUs ?: 0L
                    ) ?: 0L
                    println(
                        "[DIAG]   $topic activeUpdates=${active.size}, serverSpanUs=$timestampSpan, " +
                            "first=${active.firstOrNull()?.value}, last=${active.lastOrNull()?.value}"
                    )
                }
                listOf("X" to "0", "Y" to "1", "heading" to "2").forEach { (axis, index) ->
                    val unit = if (axis == "heading") "radians" else "meters"
                    printAlignedPoseError(
                        label = "simulator estimate $axis",
                        unit = unit,
                        reference = poseSamples["ARES/TruePose/$index"].orEmpty(),
                        candidate = poseSamples["ARES/EstimatedPose/$index"].orEmpty(),
                        motionStartedAtNs = motionStartedAtNs,
                        motionStoppedAtNs = motionStoppedAtNs,
                    )
                    printAlignedPoseError(
                        label = "Drive/Pose EKF $axis",
                        unit = unit,
                        reference = poseSamples["ARES/TruePose/$index"].orEmpty(),
                        candidate = poseSamples["Drive/Pose_${if (axis == "heading") "Heading" else axis}"].orEmpty(),
                        motionStartedAtNs = motionStartedAtNs,
                        motionStoppedAtNs = motionStoppedAtNs,
                    )
                    printAlignedPoseError(
                        label = "raw odometry $axis",
                        unit = unit,
                        reference = poseSamples["ARES/TruePose/$index"].orEmpty(),
                        candidate = poseSamples["Drive/Odom_${if (axis == "heading") "Heading" else axis}"].orEmpty(),
                        motionStartedAtNs = motionStartedAtNs,
                        motionStoppedAtNs = motionStoppedAtNs,
                    )
                }
            }
        }
        client.close()
    }

    private fun printPackedPoseError(samples: List<DoubleArray>) {
        val ekfErrors = samples.map { values ->
            kotlin.math.hypot(values[3] - values[0], values[4] - values[1])
        }
        val odomErrors = samples.map { values ->
            kotlin.math.hypot(values[6] - values[0], values[7] - values[1])
        }
        val peakIndex = ekfErrors.indices.maxByOrNull { ekfErrors[it] }
        val peak = peakIndex?.let(samples::get)
        println(
            "[DIAG]   packed EKF translation error: samples=${ekfErrors.size}, " +
                "meanMeters=${ekfErrors.average()}, maxMeters=${ekfErrors.maxOrNull() ?: Double.NaN}"
        )
        println(
            "[DIAG]   packed raw odometry translation error: samples=${odomErrors.size}, " +
                "meanMeters=${odomErrors.average()}, maxMeters=${odomErrors.maxOrNull() ?: Double.NaN}"
        )
        if (peak != null) {
            println(
                "[DIAG]   packed peak frame: truth=(${peak[0]}, ${peak[1]}, ${peak[2]}), " +
                    "ekf=(${peak[3]}, ${peak[4]}, ${peak[5]}), " +
                    "odom=(${peak[6]}, ${peak[7]}, ${peak[8]}), sequence=${peak[9]}"
            )
        }
    }

    private fun printAlignedPoseError(
        label: String,
        unit: String,
        reference: List<WireSample>,
        candidate: List<WireSample>,
        motionStartedAtNs: Long,
        motionStoppedAtNs: Long,
    ) {
        val activeReference = reference.filter { it.receivedAtNs in motionStartedAtNs..motionStoppedAtNs }
        val activeCandidate = candidate.filter { it.receivedAtNs in motionStartedAtNs..motionStoppedAtNs }
        val errors = activeReference.zip(activeCandidate) { truth, estimate ->
            kotlin.math.abs(truth.value - estimate.value)
        }
        println(
            "[DIAG]   $label paired-frame error: samples=${errors.size}, " +
                "mean$unit=${errors.average()}, max$unit=${errors.maxOrNull() ?: Double.NaN}"
        )
    }

    private fun encodeDoubleArrayValueMessage(
        topicId: Long,
        timestampUs: Long,
        values: DoubleArray,
    ): ByteArray {
        require(topicId in 0..0xffff) { "diagnostic publisher UID must fit an unsigned 16-bit ID" }
        require(values.size <= 15) { "diagnostic helper supports fixed-array MessagePack headers" }
        val valueBytes = ByteArray(1 + values.size * 9)
        valueBytes[0] = (0x90 or values.size).toByte()
        var valueOffset = 1
        values.forEach { value ->
            valueBytes[valueOffset++] = 0xcb.toByte()
            val bits = value.toBits()
            for (shift in 56 downTo 0 step 8) valueBytes[valueOffset++] = (bits shr shift).toByte()
        }

        // Same fixed NT4 tuple layout as Nt4OutboundPublisher.encodeNt4BinaryUpdate.
        val buffer = ByteArray(14 + valueBytes.size)
        buffer[0] = 0x94.toByte()
        buffer[1] = 0xcd.toByte()
        buffer[2] = (topicId.toInt() shr 8).toByte()
        buffer[3] = topicId.toByte()
        buffer[4] = 0xcf.toByte()
        for (index in 0 until 8) buffer[5 + index] = (timestampUs shr (56 - index * 8)).toByte()
        buffer[13] = 17
        System.arraycopy(valueBytes, 0, buffer, 14, valueBytes.size)
        return buffer
    }

    private companion object {
        val POSE_DIAGNOSTIC_TOPICS = setOf(
            "ARES/TruePose/0",
            "ARES/TruePose/1",
            "ARES/TruePose/2",
            "ARES/EstimatedPose/0",
            "ARES/EstimatedPose/1",
            "ARES/EstimatedPose/2",
            "Drive/Pose_X",
            "Drive/Pose_Y",
            "Drive/Pose_Heading",
            "Drive/Odom_X",
            "Drive/Odom_Y",
            "Drive/Odom_Heading",
        )
    }
}
