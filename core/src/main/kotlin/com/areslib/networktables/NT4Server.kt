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
    private data class ClientSubscription(val topics: List<String>, val prefix: Boolean) {
        fun matches(topic: String): Boolean = topics.any { requested ->
            requested.isEmpty() || if (prefix) topic.startsWith(requested) else topic == requested
        }
    }

    private val clientSubscriptions = ConcurrentHashMap<WebSocket, ConcurrentHashMap<Int, ClientSubscription>>()
    private val clientPublishers = ConcurrentHashMap<WebSocket, ConcurrentHashMap<Long, NT4Entry>>()
    private val pendingEntriesByConnection = ConcurrentHashMap<WebSocket, MutableSet<NT4Entry>>()
    private val dirtyEntriesLock = Any()
    private var dirtyEntries: MutableSet<NT4Entry> = LinkedHashSet()

    private class FastByteArrayOutputStream(size: Int) : java.io.ByteArrayOutputStream(size) {
        fun buffer(): ByteArray = buf
        fun count(): Int = count
    }

    private val encodeBuffer = FastByteArrayOutputStream(4096)
    private val entriesToSendBuffer = ArrayList<NT4Entry>(128)
    private var packer: org.msgpack.core.MessagePacker = try {
        MessagePack.newDefaultPacker(encodeBuffer)
    } catch (_: Throwable) {
        MessagePack.PackerConfig().newPacker(encodeBuffer)
    }

    override fun onOpen(conn: WebSocket, handshake: org.java_websocket.handshake.ClientHandshake) {
        pendingEntriesByConnection.computeIfAbsent(conn) { ConcurrentHashMap.newKeySet() }
        connections.add(conn)
        val announceText = NT4Json.buildAnnounceArray(entries.values)
        if (announceText != "[]") {
            conn.send(announceText)
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        connections.remove(conn)
        pendingEntriesByConnection.remove(conn)
        clientSubscriptions.remove(conn)
        
        val publishers = clientPublishers.remove(conn)?.values?.toSet().orEmpty()
        for (entry in publishers) {
            if (!isPublishedByAnyClient(entry)) {
                entry.notifyListeners(NT4EventType.TOPIC_UNPUBLISHED, entry.value)
            }
        }
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

    private fun getEntryForId(conn: WebSocket, id: Long): NT4Entry? = clientPublishers[conn]?.get(id)

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        try {
            val decodedList = decodeNT4Messages(message)
            for (decoded in decodedList) {
                if (decoded.id == -1L) {
                    heartbeat(conn, (decoded.dataValue as? Number)?.toLong() ?: com.areslib.util.RobotClock.currentTimeMillis())
                } else {
                    val entry = getEntryForId(conn, decoded.id)
                    if (entry != null && decoded.dataValue != null) {
                        val newValue = NT4Value.fromObject(decoded.dataValue)
                        if (newValue.typeString == entry.value.typeString && entry.update(newValue)) {
                            markDirty(entry)
                        }
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
            "publish" -> handlePublish(conn, msg)
            "unpublish" -> handleUnpublish(conn, msg)
            "subscribe" -> handleSubscribe(conn, msg)
            "unsubscribe" -> handleUnsubscribe(conn, msg)
        }
    }

    private fun handlePublish(conn: WebSocket, msg: NT4Json.ParsedMessage) {
        val topic = msg.topicName?.trimStart('/')?.takeIf { it.isNotEmpty() } ?: return
        val pubUID = msg.pubUid ?: return
        val type = msg.type ?: "string"
        if (type !in SUPPORTED_TOPIC_TYPES) return

        var isNew = false
        var typeMatches = true
        val entry = entries.compute(topic) { _, existing ->
            if (existing != null) {
                typeMatches = existing.value.typeString == type
                return@compute existing
            }
            isNew = true
            val defaultValue: Any = when (type) {
                "boolean" -> false
                "double" -> 0.0
                "float" -> 0.0f
                "int" -> 0L
                "boolean[]" -> BooleanArray(0)
                "double[]" -> DoubleArray(0)
                "float[]" -> FloatArray(0)
                "int[]" -> LongArray(0)
                "string[]" -> emptyArray<String>()
                else -> "" // Guarded by SUPPORTED_TOPIC_TYPES.
            }
            val id = nextTopicId.getAndIncrement()
            NT4Entry(id, topic, NT4Value.fromObject(defaultValue))
        } ?: return
        if (!typeMatches) return
        if (isNew) {
            markDirty(entry)
        }

        val publishers = clientPublishers.computeIfAbsent(conn) { ConcurrentHashMap() }
        val previous = publishers.put(pubUID.toLong(), entry)
        if (previous != null && previous !== entry && !isPublishedByAnyClient(previous)) {
            previous.notifyListeners(NT4EventType.TOPIC_UNPUBLISHED, previous.value)
        }
        if (isNew) {
            announceEntry(entry)
        }
        entry.notifyListeners(NT4EventType.TOPIC_PUBLISHED, entry.value)
    }

    private fun handleUnpublish(conn: WebSocket, msg: NT4Json.ParsedMessage) {
        val pubUID = msg.pubUid ?: return
        val publishers = clientPublishers[conn] ?: return
        val entry = publishers.remove(pubUID.toLong()) ?: return
        if (publishers.isEmpty()) clientPublishers.remove(conn, publishers)
        if (!isPublishedByAnyClient(entry)) {
            entry.notifyListeners(NT4EventType.TOPIC_UNPUBLISHED, entry.value)
        }
    }

    private fun isPublishedByAnyClient(entry: NT4Entry): Boolean {
        return clientPublishers.values.any { publishers -> publishers.containsValue(entry) }
    }

    private fun handleSubscribe(conn: WebSocket, msg: NT4Json.ParsedMessage) {
        val topics = ArrayList<String>(msg.topics.size)

        for (t in msg.topics) {
            topics.add(t.trimStart('/'))
        }
        val subscription = ClientSubscription(topics, msg.prefix)
        val subUid = msg.subUid ?: 0
        clientSubscriptions.computeIfAbsent(conn) { ConcurrentHashMap() }[subUid] = subscription

        for (entry in entries.values) {
            if (subscription.matches(entry.topic)) {
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

    private fun handleUnsubscribe(conn: WebSocket, msg: NT4Json.ParsedMessage) {
        val subUid = msg.subUid ?: return
        val subscriptions = clientSubscriptions[conn] ?: return
        subscriptions.remove(subUid)
        if (subscriptions.isEmpty()) clientSubscriptions.remove(conn, subscriptions)
        pendingEntriesByConnection[conn]?.removeIf { entry -> !isSubscribed(conn, entry) }
    }

    private fun isSubscribed(conn: WebSocket, entry: NT4Entry): Boolean {
        return clientSubscriptions[conn]?.values?.any { it.matches(entry.topic) } == true
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

    private fun sendBinaryBuffer(conn: WebSocket, buffer: ByteBuffer): Boolean {
        return try {
            conn.send(buffer)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Synchronized
    fun encodeNT4Messages(timestamp: Long, entries: List<NT4Entry>): ByteBuffer {
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
        return ByteBuffer.wrap(encodeBuffer.buffer(), 0, encodeBuffer.count())
    }

    @Synchronized
    fun encodeNT4Message(timestamp: Long, topicId: Long, _pubUID: Long, dataType: Int, dataValue: Any): ByteBuffer {
        encodeBuffer.reset()
        packer.packArrayHeader(1)
        packer.packArrayHeader(4)
        packer.packLong(topicId)
        packer.packLong(timestamp)
        packer.packInt(dataType)
        packDataValue(dataType, dataValue)
        packer.flush()
        return ByteBuffer.wrap(encodeBuffer.buffer(), 0, encodeBuffer.count())
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
            else -> {
                if (dataType == 5 || dataType == 7 || dataType == 8) {
                    val bytes = dataValue as? ByteArray ?: ByteArray(0)
                    packer.packBinaryHeader(bytes.size)
                    packer.writePayload(bytes)
                } else {
                    packer.packNil()
                }
            }
        }
    }

    fun decodeNT4Messages(message: ByteBuffer): List<NT4Message> {
        if (message.remaining() > MAX_DECODED_FRAME_BYTES) {
            throw IOException("NT4 frame exceeds $MAX_DECODED_FRAME_BYTES bytes")
        }
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

        try {
            val numElements = unpacker.unpackArrayHeader()
            requireDecodedLength("message count", numElements, MAX_MESSAGES_PER_FRAME)
            val list = ArrayList<NT4Message>(numElements)
            for (i in 0 until numElements) {
                if (!unpacker.hasNext() || unpacker.nextFormat.valueType.name != "ARRAY") {
                    throw IOException("NT4 update batch contains a non-array tuple")
                }
                list.add(decodeSingleNT4Message(unpacker))
            }
            if (unpacker.hasNext()) throw IOException("Trailing data after NT4 update frame")
            return list
        } finally {
            unpacker.close()
        }
    }

    private fun decodeSingleNT4Message(unpacker: MessageUnpacker): NT4Message {
        val tupleSize = unpacker.unpackArrayHeader()
        if (tupleSize != 4) throw IOException("NT4 update tuple must have 4 elements, got $tupleSize")
        val id = unpacker.unpackLong()
        val timestamp = unpacker.unpackLong()
        val dataType = unpacker.unpackInt()
        var value: Any? = null

        when (NT4Value.fromId(dataType)) {
            NT4Type.BOOLEAN -> value = unpacker.unpackBoolean()
            NT4Type.DOUBLE -> value = unpacker.unpackDouble()
            NT4Type.INT -> value = unpacker.unpackLong()
            NT4Type.FLOAT -> value = unpacker.unpackFloat()
            NT4Type.STRING -> value = unpackBoundedString(unpacker)
            NT4Type.BOOLEAN_ARRAY -> {
                val len = unpacker.unpackArrayHeader()
                requireDecodedLength("boolean array", len, MAX_ARRAY_ELEMENTS)
                val arr = BooleanArray(len)
                for (i in 0 until len) arr[i] = unpacker.unpackBoolean()
                value = arr
            }
            NT4Type.DOUBLE_ARRAY -> {
                val len = unpacker.unpackArrayHeader()
                requireDecodedLength("double array", len, MAX_ARRAY_ELEMENTS)
                val arr = DoubleArray(len)
                for (i in 0 until len) arr[i] = unpacker.unpackDouble()
                value = arr
            }
            NT4Type.INT_ARRAY -> {
                val len = unpacker.unpackArrayHeader()
                requireDecodedLength("int array", len, MAX_ARRAY_ELEMENTS)
                val arr = LongArray(len)
                for (i in 0 until len) arr[i] = unpacker.unpackLong()
                value = arr
            }
            NT4Type.FLOAT_ARRAY -> {
                val len = unpacker.unpackArrayHeader()
                requireDecodedLength("float array", len, MAX_ARRAY_ELEMENTS)
                val arr = FloatArray(len)
                for (i in 0 until len) arr[i] = unpacker.unpackFloat()
                value = arr
            }
            NT4Type.STRING_ARRAY -> {
                val len = unpacker.unpackArrayHeader()
                requireDecodedLength("string array", len, MAX_ARRAY_ELEMENTS)
                val arr = Array(len) { "" }
                for (i in 0 until len) arr[i] = unpackBoundedString(unpacker)
                value = arr
            }
            else -> {
                if (dataType == 5 || dataType == 7 || dataType == 8) {
                    val len = unpacker.unpackBinaryHeader()
                    requireDecodedLength("binary value", len, MAX_BINARY_BYTES)
                    val bytes = ByteArray(len)
                    unpacker.readPayload(bytes)
                    value = bytes
                } else {
                    unpacker.unpackNil()
                }
            }
        }
        return NT4Message(id, timestamp, dataType, value)
    }

    private fun unpackBoundedString(unpacker: MessageUnpacker): String {
        val len = unpacker.unpackRawStringHeader()
        requireDecodedLength("string value", len, MAX_STRING_BYTES)
        val bytes = ByteArray(len)
        unpacker.readPayload(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun requireDecodedLength(label: String, length: Int, maximum: Int) {
        if (length < 0 || length > maximum) {
            throw IOException("NT4 $label length $length exceeds limit $maximum")
        }
    }

    fun putTopic(topic: String, value: Any): NT4Entry {
        return putTopic(topic, NT4Value.fromObject(value))
    }

    internal fun getTopicEntry(topic: String): NT4Entry? = entries[topic.trimStart('/')]

    fun putTopic(topic: String, value: NT4Value): NT4Entry {
        val normalizedTopic = topic.trimStart('/').takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("NT4 topic must not be empty")
        var isNew = false
        var updated = false
        val entry = entries.compute(normalizedTopic) { _, existing ->
            if (existing != null) {
                // NT4 topic types are immutable for the lifetime of an announcement.
                // Preserve the existing value when a caller attempts a type change.
                if (existing.value.typeString == value.typeString) {
                    updated = existing.update(value)
                }
                return@compute existing
            }
            isNew = true
            val id = nextTopicId.getAndIncrement()
            NT4Entry(id, normalizedTopic, value)
        } ?: error("ConcurrentHashMap.compute returned null")
        if (isNew || updated) {
            markDirty(entry)
        }

        if (isNew) {
            announceEntry(entry)
        }
        return entry
    }

    private fun markDirty(entry: NT4Entry) {
        synchronized(dirtyEntriesLock) {
            dirtyEntries.add(entry)
        }
    }

    private fun drainDirtyEntries(): Set<NT4Entry> {
        synchronized(dirtyEntriesLock) {
            if (dirtyEntries.isEmpty()) return emptySet()
            val currentDirty = dirtyEntries
            dirtyEntries = LinkedHashSet()
            return currentDirty
        }
    }

    /**
     * Sends the latest value of every dirty topic to each subscribed client.
     *
     * Dirty publication and draining share [dirtyEntriesLock], so an update cannot land in a
     * set that has already been detached. Each connection also owns a pending set: a congested
     * client retains topic identities until its socket queue clears, while healthy clients can
     * continue receiving updates. Because the set stores [NT4Entry] references, retries encode
     * the current value rather than an obsolete snapshot.
     */
    @Synchronized
    fun flush() {
        if (clientSubscriptions.isEmpty() || connections.isEmpty()) return
        val currentDirty = drainDirtyEntries()
        val timestamp = com.areslib.util.RobotClock.currentTimeMillis() * 1000L

        for (conn in connections) {
            val pendingEntries = pendingEntriesByConnection.computeIfAbsent(conn) {
                ConcurrentHashMap.newKeySet()
            }

            for (entry in currentDirty) {
                if (isSubscribed(conn, entry)) {
                    pendingEntries.add(entry)
                }
            }

            if (conn.hasBufferedData() || pendingEntries.isEmpty()) continue

            entriesToSendBuffer.clear()
            entriesToSendBuffer.addAll(pendingEntries)
            if (entriesToSendBuffer.isNotEmpty()) {
                try {
                    val binMsg = encodeNT4Messages(timestamp, entriesToSendBuffer)
                    if (sendBinaryBuffer(conn, binMsg)) {
                        pendingEntries.removeAll(entriesToSendBuffer)
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun getTypeIdFromValue(value: NT4Value): Int = when (value) {
        is NT4Value.BooleanVal -> 0
        is NT4Value.DoubleVal -> 1
        is NT4Value.LongVal -> 2
        is NT4Value.FloatVal -> 3
        is NT4Value.StringVal -> 4
        is NT4Value.BooleanArrayVal -> 16
        is NT4Value.DoubleArrayVal -> 17
        is NT4Value.LongArrayVal -> 18
        is NT4Value.FloatArrayVal -> 19
        is NT4Value.StringArrayVal -> 20
    }

    companion object {
        internal const val MAX_MESSAGES_PER_FRAME = 1024
        internal const val MAX_ARRAY_ELEMENTS = 4096
        internal const val MAX_STRING_BYTES = 65_536
        internal const val MAX_BINARY_BYTES = 1_048_576
        internal const val MAX_DECODED_FRAME_BYTES = 4_194_304
        private val SUPPORTED_TOPIC_TYPES = setOf(
            "boolean", "double", "int", "float", "string",
            "boolean[]", "double[]", "int[]", "float[]", "string[]"
        )

        private var serverInstance: NT4Server? = null
        private var shutdownHookAdded = false
        private val entries = ConcurrentHashMap<String, NT4Entry>()
        private val nextTopicId = java.util.concurrent.atomic.AtomicInteger(1)

        @JvmStatic
        fun createInstance(address: String, port: Int): NT4Server {
            serverInstance?.let { existing ->
                try {
                    existing.stop()
                } catch (_: Exception) {}
            }
            // The topic/entry maps and topic-id counter live in the companion object and
            // would otherwise bleed across server instances. Reset them so a freshly created
            // server starts from a clean slate.
            resetSharedState()
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

        /**
         * Clears the companion-level topic registry and topic-id counter.
         *
         * These structures are intentionally kept in the companion object (moving them risks
         * breaking the many `@JvmStatic` accessors) but, because they are not tied to a single
         * instance, they must be reset when a server is (re)created so stale topics do not
         * bleed across instances.
         */
        @Synchronized
        @JvmStatic
        fun resetSharedState() {
            entries.clear()
            nextTopicId.set(1)
        }

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
