package com.ares.analytics.service.nt4

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class Nt4TargetIdentity(
    val teamId: String,
    val seasonId: String,
    val robotId: String
)

/**
 * Owns the inbound NT4 topic registry and protocol decoding. Domain telemetry reduction is
 * delegated through [onValue], keeping wire parsing independent of persistence and UI state.
 */
internal class Nt4InboundRouter(
    private val onClockSyncReply: (serverTimestampUs: Long, sentAtUs: Long) -> Unit,
    private val onValue: suspend (
        topic: Nt4Topic,
        value: Any?,
        timestampMs: Long,
        timestampUs: Long,
        target: Nt4TargetIdentity
    ) -> Unit,
    private val wallClockMs: () -> Long = System::currentTimeMillis
) {
    val topicMap = ConcurrentHashMap<Int, Nt4Topic>()
    val malformedTextFrameCount = AtomicLong()
    private val discoveredKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var cachedActiveTopics: List<String>? = null

    private var binaryFrameCount = 0L
    private var lastBinaryDiagLog = wallClockMs()

    fun activeTopics(): List<String> = cachedActiveTopics ?: run {
        val announced = topicMap.values.map { it.name.removePrefix("/") }
        val topics = (announced + discoveredKeys).distinct().filter(String::isNotEmpty).sorted()
        cachedActiveTopics = topics
        topics
    }

    fun markDiscovered(normalizedName: String, type: String) {
        if (discoveredKeys.add(normalizedName)) {
            trace("Discovered telemetry key: $normalizedName (type=$type)")
            cachedActiveTopics = null
        }
    }

    fun clear() {
        topicMap.clear()
        discoveredKeys.clear()
        cachedActiveTopics = null
    }

    suspend fun handleText(text: String, target: Nt4TargetIdentity) {
        if (text.length > MAX_TEXT_FRAME_CHARS) {
            println("[Nt4ClientService] Rejected oversized text frame (${text.length} characters)")
            return
        }
        try {
            val messages = Json.parseToJsonElement(text) as? JsonArray ?: return
            if (messages.size > MAX_TEXT_FRAME_MESSAGES) return
            for (element in messages) {
                val objectValue = element as? JsonObject ?: continue
                when (objectValue["method"]?.jsonPrimitive?.contentOrNull) {
                    "announce" -> announce(objectValue)
                    "unannounce" -> unannounce(objectValue)
                    null -> routeTextValue(objectValue, target)
                }
            }
        } catch (exception: Exception) {
            val rejectedCount = malformedTextFrameCount.incrementAndGet()
            if (rejectedCount == 1L || rejectedCount % MALFORMED_TEXT_LOG_INTERVAL == 0L) {
                println(
                    "[Nt4ClientService] Rejected malformed text frame " +
                        "(count=$rejectedCount, error=${exception::class.java.simpleName})"
                )
            }
        }
    }

    suspend fun handleBinary(bytes: ByteArray, target: Nt4TargetIdentity) {
        val messages = try {
            com.areslib.networktables.NT4WireProtocol.unpackMessageFrames(bytes)
        } catch (exception: Exception) {
            println("ERROR decoding NT4 binary frame: ${exception.message}")
            emptyList()
        }
        val now = wallClockMs()
        if (traceEnabled) {
            binaryFrameCount += messages.size
            if (now - lastBinaryDiagLog > BINARY_DIAGNOSTIC_INTERVAL_MS) {
                trace(
                    "$binaryFrameCount binary messages decoded in last 2s, " +
                        "topicMap.size=${topicMap.size}"
                )
                lastBinaryDiagLog = now
                binaryFrameCount = 0L
            }
        }
        for (message in messages) {
            if (message.topicId == CLOCK_SYNC_TOPIC_ID) {
                val sentAtUs = (message.value as? Number)?.toLong() ?: continue
                onClockSyncReply(message.timestampUs, sentAtUs)
                continue
            }
            val timestampUs = if (message.timestampUs <= 1L) now * 1_000L else message.timestampUs
            val topic = topicMap[message.topicId.toInt()] ?: continue
            onValue(topic, message.value, timestampUs / 1_000L, timestampUs, target)
        }
    }

    private fun announce(message: JsonObject) {
        val params = message["params"] as? JsonObject ?: return
        val name = params["name"]?.jsonPrimitive?.contentOrNull ?: return
        val id = params["id"]?.jsonPrimitive?.intOrNull ?: return
        val type = params["type"]?.jsonPrimitive?.contentOrNull ?: "double"
        val properties = buildMap {
            (params["properties"] as? JsonObject)?.forEach { (key, value) ->
                put(key, if (value is JsonPrimitive && value.isString) value.content else value.toString())
            }
        }
        if (name.removePrefix("/") == "ARES/Input/driveFrame" && type != "double[]") {
            println("[Nt4ClientService] WARN: Topic $name announced with type $type, expected double[]")
        }
        trace("Server announced topic: $name (id=$id, type=$type)")
        topicMap[id] = Nt4Topic(id, name, type, properties)
        cachedActiveTopics = null
    }

    private fun unannounce(message: JsonObject) {
        val params = message["params"] as? JsonObject ?: return
        val id = params["id"]?.jsonPrimitive?.intOrNull ?: return
        trace("Server unannounced topic id: $id")
        topicMap.remove(id)
        cachedActiveTopics = null
    }

    private suspend fun routeTextValue(message: JsonObject, target: Nt4TargetIdentity) {
        val topicId = message["topic"]?.jsonPrimitive?.intOrNull ?: return
        val value = message["value"] ?: return
        val topic = topicMap[topicId] ?: return
        val timestampUs = message["time"]?.jsonPrimitive?.longOrNull ?: wallClockMs() * 1_000L
        onValue(topic, value, timestampUs / 1_000L, timestampUs, target)
    }

    private fun trace(message: String) {
        if (traceEnabled) println("[Nt4ClientService] TRACE: $message")
    }

    companion object {
        private const val CLOCK_SYNC_TOPIC_ID = -1L
        private const val MAX_TEXT_FRAME_CHARS = 1_048_576
        private const val MAX_TEXT_FRAME_MESSAGES = 1_024
        private const val MALFORMED_TEXT_LOG_INTERVAL = 100L
        private const val BINARY_DIAGNOSTIC_INTERVAL_MS = 2_000L
        private val traceEnabled: Boolean = java.lang.Boolean.getBoolean("ares.nt4.trace")
    }
}
