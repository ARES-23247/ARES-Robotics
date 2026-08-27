package com.areslib.sim.network

import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.google.gson.JsonObject
import edu.wpi.first.networktables.NetworkTableInstance
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Class implementation for Dynamic Element Pose.
 *
 * Real-time telemetry streaming, diagnostic logging, and NetworkTables 4 communication handler.
 */
data class DynamicElementPose(
    val id: String,
    val x: Double,
    val y: Double,
    val rotation: Double // in degrees
)

/**
 * Object implementation for N T4 Field Publisher.
 *
 * Real-time telemetry streaming, diagnostic logging, and NetworkTables 4 communication handler.
 */
object NT4FieldPublisher {
    private val ntInst = NetworkTableInstance.getDefault()
    private val obstaclesPub = ntInst.getStringTopic("ARES/Field/Obstacles").publish()
    private val elementsPub = ntInst.getStringTopic("ARES/Field/Elements").publish()
    private val scoresPub = ntInst.getStringTopic("ARES/Field/Scores").publish()
    private val configIdPub = ntInst.getStringTopic("ARES/Field/ConfigId").publish()
    private val apriltagsPub = ntInst.getStringTopic("ARES/Field/AprilTags").publish()
    private val appliedReceiptPub = ntInst.getStringTopic(APPLIED_RECEIPT_TOPIC).publish()
    private val sb = java.lang.StringBuilder(2048)
    private val simulatorSessionId = UUID.randomUUID().toString()
    private val appliedSequence = AtomicLong()

    fun publishConfigId(configId: String) {
        configIdPub.set(configId)
        com.areslib.networktables.NT4Server.publishTopic("ARES/Field/ConfigId", configId)
    }

    /**
     * Publishes one atomic receipt only after the complete canonical field has been installed.
     * Desktop clients use its session/sequence event identity and canonical hash to distinguish
     * actual simulator application from a WebSocket message that was merely queued.
     */
    internal fun publishAppliedConfig(config: RobotFieldConfig) {
        publishConfigId(config.id)
        val receipt = encodeAppliedFieldReceipt(
            config = config,
            sessionId = simulatorSessionId,
            sequence = appliedSequence.incrementAndGet(),
        )
        appliedReceiptPub.set(receipt)
        com.areslib.networktables.NT4Server.publishTopic(APPLIED_RECEIPT_TOPIC, receipt)
    }

    fun publishAprilTags(tags: List<com.areslib.state.RobotFieldAprilTag>) {
        sb.setLength(0)
        sb.append("[")
        for (i in tags.indices) {
            val t = tags[i]
            sb.append("{\"id\":").append(t.id)
              .append(",\"x\":").append(t.x)
              .append(",\"y\":").append(t.y)
              .append(",\"z\":").append(t.z)
              .append(",\"yaw\":").append(t.yaw)
              .append("}")
            if (i < tags.size - 1) sb.append(",")
        }
        sb.append("]")
        val jsonStr = sb.toString()
        apriltagsPub.set(jsonStr)
        com.areslib.networktables.NT4Server.publishTopic("ARES/Field/AprilTags", jsonStr)
    }

    fun publishObstacles(obstacles: List<com.areslib.state.RobotFieldObstacle>) {
        val jsonStr = encodeObstaclesJson(obstacles)
        obstaclesPub.set(jsonStr)
        com.areslib.networktables.NT4Server.publishTopic("ARES/Field/Obstacles", jsonStr)
    }

    internal fun encodeObstaclesJson(obstacles: List<com.areslib.state.RobotFieldObstacle>): String {
        val output = StringBuilder(256 + obstacles.size * 192)
        output.append("[")
        for (i in obstacles.indices) {
            val o = obstacles[i]
            output.append("{\"id\":\"").append(o.id)
              .append("\",\"name\":\"").append(o.name)
              .append("\",\"x\":").append(o.x)
              .append(",\"y\":").append(o.y)
              .append(",\"width\":").append(o.width)
              .append(",\"height\":").append(o.height)
              .append(",\"isBlocking\":").append(o.isBlocking)
              .append(",\"obstacleType\":\"").append(o.obstacleType.name.lowercase())
              .append("\",\"rampDirection\":")
            val rampDir = o.rampDirection
            if (rampDir == null) {
                output.append("null")
            } else {
                output.append("\"").append(rampDir.name.lowercase()).append("\"")
            }
            output.append(",\"shape\":\"").append(o.shape)
              .append("\",\"points\":[")
            for (j in o.points.indices) {
                val p = o.points[j]
                output.append("{\"x\":").append(p.x).append(",\"y\":").append(p.y).append("}")
                if (j < o.points.size - 1) output.append(",")
            }
            output.append("],\"friction\":").append(o.friction)
              .append(",\"restitution\":").append(o.restitution)
              .append(",\"rotation\":").append(o.rotation)
              .append("}")
            if (i < obstacles.size - 1) output.append(",")
        }
        return output.append("]").toString()
    }

    fun publishElements(elements: List<DynamicElementPose>) {
        sb.setLength(0)
        sb.append("[")
        for (i in elements.indices) {
            val e = elements[i]
            sb.append("{\"id\":\"").append(e.id)
              .append("\",\"x\":").append(e.x)
              .append(",\"y\":").append(e.y)
              .append(",\"rotation\":").append(e.rotation)
              .append("}")
            if (i < elements.size - 1) sb.append(",")
        }
        sb.append("]")
        val jsonStr = sb.toString()
        elementsPub.set(jsonStr)
        com.areslib.networktables.NT4Server.publishTopic("ARES/Field/Elements", jsonStr)
    }

    fun publishScores(blueScore: Int, redScore: Int) {
        sb.setLength(0)
        sb.append("{\"blue\":").append(blueScore).append(",\"red\":").append(redScore).append("}")
        val jsonStr = sb.toString()
        scoresPub.set(jsonStr)
        com.areslib.networktables.NT4Server.publishTopic("ARES/Field/Scores", jsonStr)
    }

    internal const val APPLIED_RECEIPT_TOPIC = "ARES/Field/AppliedReceipt"

    internal fun encodeAppliedFieldReceipt(
        config: RobotFieldConfig,
        sessionId: String,
        sequence: Long,
    ): String {
        require(sessionId.isNotBlank()) { "simulator field receipt session must not be blank" }
        require(sequence > 0L) { "simulator field receipt sequence must be positive" }
        val canonicalPayload = RobotFieldDocument.encode(config)
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(canonicalPayload.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return JsonObject().apply {
            addProperty("session", sessionId)
            addProperty("sequence", sequence)
            addProperty("configId", config.id)
            addProperty("revision", config.revision)
            addProperty("sha256", sha256)
            addProperty("obstacleCount", config.obstacles.size)
            addProperty("elementCount", config.elements.size)
            addProperty("aprilTagCount", config.apriltags.size)
        }.toString()
    }
}
