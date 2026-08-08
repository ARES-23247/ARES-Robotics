package com.areslib.networktables

import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.protocols.IProtocol
import org.java_websocket.protocols.Protocol
import org.java_websocket.server.WebSocketServer
import org.msgpack.core.MessageBufferPacker
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

data class NT4Message(
    val id: Long,
    val timestamp: Long,
    val dataType: Int,
    val dataValue: Any?
)

/**
 * Idiomatic Kotlin NT4 Server for ARESLib-Kotlin.
 * Provides high-performance, standard-compliant WPILib NT4 4.1 WebSocket server functionality.
 * Refactored for Zero-GC compliance and zero-allocation JSON-RPC message handling.
 */
class NT4Server(
    address: InetSocketAddress,
    draftProtocols: Draft_6455
) : WebSocketServer(address, listOf(draftProtocols)) {

    private val connections = CopyOnWriteArraySet<WebSocket>()
    private val clientSubscriptions = ConcurrentHashMap<String, CopyOnWriteArraySet<WebSocket>>()
    private val dirtyEntries = CopyOnWriteArraySet<NT4Entry>()

    private val encodeBuffer = java.io.ByteArrayOutputStream(4096)
    private var packer: org.msgpack.core.MessagePacker = try {
        MessagePack.newDefaultPacker(encodeBuffer)
    } catch (_: Throwable) {
        MessagePack.PackerConfig().newPacker(encodeBuffer)
    }

    override fun onOpen(conn: WebSocket, handshake: org.java_websocket.handshake.ClientHandshake) {
        connections.add(conn)
        val announceText = NT4Json.buildAnnounceArray(entries.values)
        if (announceText != "[]") {
            conn.send(announceText)
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        connections.remove(conn)
        for (subscribers in clientSubscriptions.values) {
            subscribers.remove(conn)
        }
        clientSubscriptions.entries.removeIf { it.value.isEmpty() }
    }

    override fun stop() {
        if (serverInstance == this) {
            serverInstance = null
        }
        try {
            super.stop()
        } catch (_: Exception) {}
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val parsedList = NT4Json.parseMessages(message)
            for (msg in parsedList) {
                processParsedMessage(conn, msg)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getEntryForId(id: Long): NT4Entry? {
        publisherUIDSMap[id]?.let { return it }
        entries.values.firstOrNull { it.id.toLong() == id }?.let {
            publisherUIDSMap[id] = it
            return it
        }

        val knownTopic = when (id) {
            1001L -> "ARES/Input/vx"
            1002L -> "ARES/Input/vy"
            1003L -> "ARES/Input/omega"
            1004L -> "ARES/Input/isIntaking"
            1005L -> "ARES/Input/isFlywheelOn"
            1006L -> "ARES/Input/isTransferring"
            1007L -> "ARES/Input/isTeleopMode"
            1008L -> "ARES/Input/isFieldCentric"
            1009L -> "ARES/Input/isRedAlliance"
            1010L -> "ARES/Input/heartbeat"
            1011L -> "ARES/DriverStation/Command"
            1012L -> "ARES/DriverStation/SelectedOpMode"
            1013L -> "ARES/DriverStation/MatchTime"
            1014L -> "ARES/DriverStation/MatchState"
            1015L -> "SysId/Command"
            1016L -> "ARES/Input/isButtonAPressed"
            1017L -> "ARES/Input/isButtonBPressed"
            1018L -> "ARES/Input/isButtonXPressed"
            1019L -> "ARES/Input/isPoseReset"
            else -> null
        }

        if (knownTopic != null) {
            val entry = putTopic(knownTopic, "")
            publisherUIDSMap[id] = entry
            return entry
        }
        return null
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        try {
            val decoded = decodeNT4Message(message)
            if (decoded.id == -1L) {
                heartbeat(conn, (decoded.dataValue as? Number)?.toLong() ?: com.areslib.util.RobotClock.currentTimeMillis())
            } else {
                val entry = getEntryForId(decoded.id)
                if (entry != null && decoded.dataValue != null) {
                    val newValue = NT4Value.fromObject(decoded.dataValue)
                    if (entry.update(newValue)) {
                        publisherUIDSMap[decoded.id] = entry
                        dirtyEntries.add(entry)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        ex.printStackTrace()
    }

    override fun onStart() {
        // No-op
    }

    private fun processParsedMessage(conn: WebSocket, msg: NT4Json.ParsedMessage) {
        when (msg.method) {
            "publish" -> handlePublish(msg)
            "unpublish" -> handleUnpublish(msg)
            "subscribe" -> handleSubscribe(conn, msg)
        }
    }

    private fun handlePublish(msg: NT4Json.ParsedMessage) {
        var topic = msg.topicName ?: return
        if (topic.startsWith("/")) topic = topic.substring(1)
        val pubUID = msg.pubUid ?: return
        val type = msg.type ?: "string"

        val entry: NT4Entry
        val isNew: Boolean
        if (entries.containsKey(topic)) {
            entry = entries.getValue(topic)
            isNew = false
        } else {
            isNew = true
            val defaultValue: Any = when (type) {
                "boolean" -> false
                "double", "float", "int" -> 0.0
                "boolean[]" -> BooleanArray(0)
                "double[]", "float[]", "int[]" -> DoubleArray(0)
                "string[]" -> emptyArray<String>()
                else -> ""
            }
            val id = entries.size + 1
            entry = NT4Entry(id, topic, NT4Value.fromObject(defaultValue))
            entries[topic] = entry
            dirtyEntries.add(entry)
        }

        publisherUIDSMap[pubUID.toLong()] = entry
        if (isNew) {
            announceEntry(entry)
        }
        entry.notifyListeners(NT4EventType.TOPIC_PUBLISHED, entry.value)
    }

    private fun handleUnpublish(msg: NT4Json.ParsedMessage) {
        var topic = msg.topicName ?: return
        if (topic.startsWith("/")) topic = topic.substring(1)
        val pubUID = msg.pubUid ?: return
        val entry = entries[topic]
        if (entry != null) {
            publisherUIDSMap.remove(pubUID.toLong())
            entry.notifyListeners(NT4EventType.TOPIC_UNPUBLISHED, entry.value)
        }
    }

    private fun handleSubscribe(conn: WebSocket, msg: NT4Json.ParsedMessage) {
        val prefixes = ArrayList<String>(msg.topics.size)

        for (t in msg.topics) {
            var prefix = t
            if (prefix.startsWith("/")) prefix = prefix.substring(1)
            prefixes.add(prefix)
            clientSubscriptions.computeIfAbsent(prefix) { CopyOnWriteArraySet() }.add(conn)
        }

        for (entry in entries.values) {
            val matches = prefixes.any { prefix -> prefix.isEmpty() || entry.topic.startsWith(prefix) }
            if (matches) {
                try {
                    val announceText = NT4Json.buildAnnounceSingle(entry)
                    conn.send(announceText)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                sendBinaryUpdate(conn, entry)
            }
        }
    }

    private fun announceEntry(entry: NT4Entry) {
        val announceText = NT4Json.buildAnnounceSingle(entry)
        broadcast(announceText)
    }

    private fun heartbeat(conn: WebSocket, clientTime: Long) {
        val binMsg = encodeNT4Message(com.areslib.util.RobotClock.currentTimeMillis() * 1000L, -1L, -1L, 2, clientTime)
        sendBinaryBuffer(conn, binMsg)
    }

    private fun sendBinaryUpdate(conn: WebSocket, entry: NT4Entry) {
        try {
            val binMsg = encodeNT4Messages(com.areslib.util.RobotClock.currentTimeMillis() * 1000L, listOf(entry))
            sendBinaryBuffer(conn, binMsg)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun sendBinaryBuffer(conn: WebSocket, bytes: ByteArray) {
        try {
            conn.send(ByteBuffer.wrap(bytes))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun encodeNT4Messages(timestamp: Long, entries: List<NT4Entry>): ByteArray {
        encodeBuffer.reset()
        packer.packArrayHeader(entries.size)
        for (entry in entries) {
            val dataType = getTypeIdFromValue(entry.value)
            val dataValue = entry.value.getAsObject()
            packer.packArrayHeader(4)
            packer.packLong(entry.id.toLong())
            packer.packLong(timestamp)
            packer.packInt(dataType)
            packDataValue(dataType, dataValue)
        }
        packer.flush()
        return encodeBuffer.toByteArray()
    }

    @Synchronized
    fun encodeNT4Message(timestamp: Long, topicId: Long, pubUID: Long, dataType: Int, dataValue: Any): ByteArray {
        encodeBuffer.reset()
        packer.packArrayHeader(1)
        packer.packArrayHeader(4)
        packer.packLong(topicId)
        packer.packLong(timestamp)
        packer.packInt(dataType)
        packDataValue(dataType, dataValue)
        packer.flush()
        return encodeBuffer.toByteArray()
    }

    private fun packDataValue(dataType: Int, dataValue: Any) {
        when (NT4Value.fromId(dataType)) {
            NT4Type.BOOLEAN -> packer.packBoolean(dataValue as Boolean)
            NT4Type.DOUBLE -> packer.packDouble((dataValue as Number).toDouble())
            NT4Type.INT -> packer.packLong((dataValue as Number).toLong())
            NT4Type.FLOAT -> packer.packFloat((dataValue as Number).toFloat())
            NT4Type.STRING -> packer.packString(dataValue.toString())
            NT4Type.BOOLEAN_ARRAY -> {
                val arr = dataValue as BooleanArray
                packer.packArrayHeader(arr.size)
                for (b in arr) packer.packBoolean(b)
            }
            NT4Type.DOUBLE_ARRAY -> {
                val arr = dataValue as DoubleArray
                packer.packArrayHeader(arr.size)
                for (d in arr) packer.packDouble(d)
            }
            NT4Type.INT_ARRAY -> {
                val arr = dataValue as LongArray
                packer.packArrayHeader(arr.size)
                for (l in arr) packer.packLong(l)
            }
            NT4Type.FLOAT_ARRAY -> {
                val arr = dataValue as FloatArray
                packer.packArrayHeader(arr.size)
                for (f in arr) packer.packFloat(f)
            }
            NT4Type.STRING_ARRAY -> {
                @Suppress("UNCHECKED_CAST")
                val arr = dataValue as Array<String>
                packer.packArrayHeader(arr.size)
                for (s in arr) packer.packString(s)
            }
            else -> packer.packNil()
        }
    }

    fun decodeNT4Message(message: ByteBuffer): NT4Message {
        val unpacker: MessageUnpacker = if (message.hasArray()) {
            val offset = message.arrayOffset() + message.position()
            val length = message.remaining()
            val byteBuffer = java.nio.ByteBuffer.wrap(message.array(), offset, length)
            MessagePack.newDefaultUnpacker(byteBuffer)
        } else {
            val arr = ByteArray(message.remaining())
            message.duplicate().get(arr)
            MessagePack.newDefaultUnpacker(arr)
        }

        unpacker.unpackArrayHeader()
        val id = unpacker.unpackLong()
        val timestamp = unpacker.unpackLong()
        val dataType = unpacker.unpackInt()
        var value: Any? = null

        when (NT4Value.fromId(dataType)) {
            NT4Type.BOOLEAN -> value = unpacker.unpackBoolean()
            NT4Type.DOUBLE -> value = unpacker.unpackDouble()
            NT4Type.INT -> value = unpacker.unpackLong()
            NT4Type.FLOAT -> value = unpacker.unpackFloat()
            NT4Type.STRING -> value = unpacker.unpackString()
            NT4Type.BOOLEAN_ARRAY -> {
                val len = unpacker.unpackArrayHeader()
                val arr = BooleanArray(len)
                for (i in 0 until len) arr[i] = unpacker.unpackBoolean()
                value = arr
            }
            NT4Type.DOUBLE_ARRAY -> {
                val len = unpacker.unpackArrayHeader()
                val arr = DoubleArray(len)
                for (i in 0 until len) arr[i] = unpacker.unpackDouble()
                value = arr
            }
            NT4Type.INT_ARRAY -> {
                val len = unpacker.unpackArrayHeader()
                val arr = LongArray(len)
                for (i in 0 until len) arr[i] = unpacker.unpackLong()
                value = arr
            }
            NT4Type.FLOAT_ARRAY -> {
                val len = unpacker.unpackArrayHeader()
                val arr = FloatArray(len)
                for (i in 0 until len) arr[i] = unpacker.unpackFloat()
                value = arr
            }
            NT4Type.STRING_ARRAY -> {
                val len = unpacker.unpackArrayHeader()
                val arr = Array(len) { "" }
                for (i in 0 until len) arr[i] = unpacker.unpackString()
                value = arr
            }
            else -> {}
        }
        unpacker.close()
        return NT4Message(id, timestamp, dataType, value)
    }

    fun putTopic(topic: String, value: Any): NT4Entry {
        return putTopic(topic, NT4Value.fromObject(value))
    }

    fun putTopic(topic: String, value: NT4Value): NT4Entry {
        val entry: NT4Entry
        val isNew: Boolean

        if (entries.containsKey(topic)) {
            entry = entries.getValue(topic)
            entry.update(value)
            dirtyEntries.add(entry)
            isNew = false
        } else {
            isNew = true
            val id = entries.size + 1
            entry = NT4Entry(id, topic, value)
            entries[topic] = entry
            publisherUIDSMap[id.toLong()] = entry
            dirtyEntries.add(entry)
        }

        if (isNew) {
            announceEntry(entry)
        }
        return entry
    }

    fun flush() {
        if (dirtyEntries.isEmpty() || clientSubscriptions.isEmpty()) return
        val timestamp = com.areslib.util.RobotClock.currentTimeMillis() * 1000L

        for (conn in connections) {
            if (conn.hasBufferedData()) continue  // Skip congested clients
            val entriesToSend = ArrayList<NT4Entry>(dirtyEntries.size)
            for (entry in dirtyEntries) {
                var subscribed = false
                for ((prefix, subscribers) in clientSubscriptions) {
                    if (subscribers.contains(conn) && (prefix.isEmpty() || entry.topic.startsWith(prefix))) {
                        subscribed = true
                        break
                    }
                }
                if (subscribed) {
                    entriesToSend.add(entry)
                }
            }

            if (entriesToSend.isNotEmpty()) {
                try {
                    val binMsg = encodeNT4Messages(timestamp, entriesToSend)
                    sendBinaryBuffer(conn, binMsg)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        dirtyEntries.clear()
    }

    private fun getTypeIdFromValue(value: NT4Value): Int = when (value) {
        is NT4Value.BooleanVal -> 0
        is NT4Value.DoubleVal -> 1
        is NT4Value.LongVal -> 2
        is NT4Value.StringVal -> 4
        is NT4Value.BooleanArrayVal -> 16
        is NT4Value.DoubleArrayVal -> 17
        is NT4Value.LongArrayVal -> 18
        is NT4Value.StringArrayVal -> 20
    }

    companion object {
        private var serverInstance: NT4Server? = null
        private var shutdownHookAdded = false
        private val entries = ConcurrentHashMap<String, NT4Entry>()
        private val publisherUIDSMap = ConcurrentHashMap<Long, NT4Entry>()

        @JvmStatic
        fun createInstance(address: String, port: Int): NT4Server {
            serverInstance?.let { existing ->
                try {
                    existing.stop()
                } catch (_: Exception) {}
            }
            val protocols: MutableList<IProtocol> = ArrayList()
            protocols.add(Protocol("v4.1.networktables.first.wpi.edu"))
            protocols.add(Protocol("rtt.networktables.first.wpi.edu"))
            val draftProtocols = Draft_6455(Collections.emptyList(), protocols)
            val server = NT4Server(InetSocketAddress(address, port), draftProtocols)
            serverInstance = server
            server.connectionLostTimeout = Int.MAX_VALUE
            server.isReuseAddr = true
            if (!shutdownHookAdded) {
                Runtime.getRuntime().addShutdownHook(Thread {
                    try {
                        server.stop()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                })
                shutdownHookAdded = true
            }
            try {
                server.start()
            } catch (e: Exception) {
                println("[NT4Server] Bind warning: ${e.message}")
            }
            return server
        }

        @JvmStatic
        fun getInstance(): NT4Server? = serverInstance

        @JvmStatic
        fun publishTopic(topic: String, value: Any) {
            val s = serverInstance ?: return
            val cleanTopic = if (topic.startsWith("/")) topic.substring(1) else topic
            s.putTopic(cleanTopic, value)
        }

        @JvmStatic
        fun flushServer() {
            serverInstance?.flush()
        }

        @JvmStatic
        fun getString(topic: String, defaultValue: String): String {
            if (serverInstance == null) return defaultValue
            val entry = getEntryFlexible(topic)
            val v = entry?.value?.getAsObject()
            return when (v) {
                is String -> v
                null -> defaultValue
                else -> v.toString()
            }
        }

        @JvmStatic
        fun getDouble(topic: String, defaultValue: Double): Double {
            if (serverInstance == null) return defaultValue
            val entry = getEntryFlexible(topic)
            val v = entry?.value?.getAsObject()
            return when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull() ?: defaultValue
                else -> defaultValue
            }
        }

        @JvmStatic
        fun getBoolean(topic: String, defaultValue: Boolean): Boolean {
            if (serverInstance == null) return defaultValue
            val entry = getEntryFlexible(topic)
            val v = entry?.value?.getAsObject()
            return when (v) {
                is Boolean -> v
                is String -> v.toBooleanStrictOrNull() ?: defaultValue
                else -> defaultValue
            }
        }

        @JvmStatic
        fun getDoubleArray(topic: String, defaultValue: DoubleArray): DoubleArray {
            if (serverInstance == null) return defaultValue
            val entry = getEntryFlexible(topic)
            val v = entry?.value?.getAsObject()
            return when (v) {
                is DoubleArray -> v
                is FloatArray -> v.map { it.toDouble() }.toDoubleArray()
                else -> defaultValue
            }
        }

        private fun getEntryFlexible(topic: String): NT4Entry? {
            var entry = entries[topic]
            if (entry == null) {
                entry = entries["/$topic"]
            }
            if (entry == null && topic.startsWith("/")) {
                entry = entries[topic.substring(1)]
            }
            return entry
        }
    }
}
