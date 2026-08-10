package com.areslib.sim.network

import edu.wpi.first.networktables.NetworkTableInstance

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
    private val sb = java.lang.StringBuilder(2048)

    fun publishConfigId(configId: String) {
        configIdPub.set(configId)
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
        apriltagsPub.set(sb.toString())
    }

    fun publishObstacles(obstacles: List<com.areslib.state.RobotFieldObstacle>) {
        sb.setLength(0)
        sb.append("[")
        for (i in obstacles.indices) {
            val o = obstacles[i]
            sb.append("{\"id\":\"").append(o.id)
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
                sb.append("null")
            } else {
                sb.append("\"").append(rampDir.name.lowercase()).append("\"")
            }
            sb.append(",\"shape\":\"").append(o.shape)
              .append("\",\"points\":[")
            for (j in o.points.indices) {
                val p = o.points[j]
                sb.append("{\"x\":").append(p.x).append(",\"y\":").append(p.y).append("}")
                if (j < o.points.size - 1) sb.append(",")
            }
            sb.append("],\"friction\":").append(o.friction)
              .append(",\"restitution\":").append(o.restitution)
              .append(",\"rotation\":").append(o.rotation)
              .append("}")
            if (i < obstacles.size - 1) sb.append(",")
        }
        val jsonStr = sb.toString()
        obstaclesPub.set(jsonStr)
        com.areslib.networktables.NT4Server.publishTopic("ARES/Field/Obstacles", jsonStr)
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
        elementsPub.set(sb.toString())
    }

    fun publishScores(blueScore: Int, redScore: Int) {
        sb.setLength(0)
        sb.append("{\"blue\":").append(blueScore).append(",\"red\":").append(redScore).append("}")
        scoresPub.set(sb.toString())
    }
}
