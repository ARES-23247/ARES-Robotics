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
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

data class NT4Message(
    val id: Long,
    val timestamp: Long,
    val dataType: Int,
    val dataValue: Any?
)

/**
 * Idiomatic Kotlin NT4 Server for ARESLib-Kotlin.
 * Provides high-performance, standard-compliant WPILib NT4 4.1 WebSocket server functionality.
 * Uses bounded parsing and reusable encoding buffers on high-rate paths. Connection lifecycle,
 * JSON control messages, and topic discovery may allocate.
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
    private val announcedEntriesByConnection = ConcurrentHashMap<WebSocket, MutableSet<NT4Entry>>()
    private val sendStateByConnection = ConcurrentHashMap<WebSocket, ConnectionSendState>()
    private val dirtyEntriesLock = Any()
    private var dirtyEntries: MutableSet<NT4Entry> = LinkedHashSet()
    private var reusableDrainedEntries: MutableSet<NT4Entry> = LinkedHashSet()
    private val rejectedTextFrames = AtomicLong()
    private val rejectedBinaryFrames = AtomicLong()
    private val sendFailures = AtomicLong()
    private val socketErrors = AtomicLong()
    private val ownedSendBufferAllocations = AtomicLong()

    private val encodeBuffer = java.io.ByteArrayOutputStream(4096)
    private val entriesToSendBuffer = ArrayList<NT4Entry>(128)
    private var packer: org.msgpack.core.MessagePacker = try {
        MessagePack.newDefaultPacker(encodeBuffer)
    } catch (_: Throwable) {
        MessagePack.PackerConfig().newPacker(encodeBuffer)
    }

    /** One transport-owned slot per connection; reuse is delayed until its socket queue drains. */
    private inner class ConnectionSendState {
        val slot = OwnedSendSlot()

        /**
         * Returns the send slot once the transport has drained. Java-WebSocket reports no
         * buffered data only after a frame is fully handed to the kernel, so this is the sole
         * reuse gate; there is no additional in-flight protocol.
         */
        fun acquireIfDrained(conn: WebSocket): OwnedSendSlot? =
            if (conn.hasBufferedData()) null else slot
    }

    private inner class OwnedSendSlot {
        val output = ReusableByteArrayOutputStream(
            INITIAL_OWNED_SEND_CAPACITY,
            MAX_DECODED_FRAME_BYTES
        ) { ownedSendBufferAllocations.incrementAndGet() }
        val messagePacker: org.msgpack.core.MessagePacker = try {
            MessagePack.newDefaultPacker(output)
        } catch (_: Throwable) {
            MessagePack.PackerConfig().newPacker(output)
        }
        private var sendBuffer = ByteBuffer.wrap(output.backingArray())

        fun reset() {
            output.reset()
        }

        fun finish(): ByteBuffer {
            messagePacker.flush()
            if (sendBuffer.array() !== output.backingArray()) {
                sendBuffer = ByteBuffer.wrap(output.backingArray())
            }
            sendBuffer.clear()
            sendBuffer.limit(output.size())
            return sendBuffer
        }
    }

    private class ReusableByteArrayOutputStream(
        initialCapacity: Int,
        private val maxCapacity: Int,
        private val onAllocation: () -> Unit
    ) : OutputStream() {
        private var storage = ByteArray(initialCapacity).also { onAllocation() }
        private var count = 0

        override fun write(value: Int) {
            ensureCapacity(count + 1)
            storage[count++] = value.toByte()
        }

        override fun write(source: ByteArray, offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > source.size - length) {
                throw IndexOutOfBoundsException()
            }
            ensureCapacity(count + length)
            source.copyInto(storage, destinationOffset = count, startIndex = offset, endIndex = offset + length)
            count += length
        }

        fun reset() {
            count = 0
        }

        fun size(): Int = count
        fun backingArray(): ByteArray = storage

        private fun ensureCapacity(required: Int) {
            if (required <= storage.size) return
            if (required > maxCapacity) throw IOException("NT4 encoded frame exceeds $maxCapacity bytes")
            var capacity = storage.size
            while (capacity < required) {
                capacity = (capacity * 2).coerceAtMost(maxCapacity)
                if (capacity < required && capacity == maxCapacity) {
                    throw IOException("NT4 encoded frame exceeds $maxCapacity bytes")
                }
            }
            storage = storage.copyOf(capacity)
            onAllocation()
        }
    }

    override fun onOpen(conn: WebSocket, handshake: org.java_websocket.handshake.ClientHandshake) {
        pendingEntriesByConnection.computeIfAbsent(conn) { ConcurrentHashMap.newKeySet() }
        announcedEntriesByConnection.computeIfAbsent(conn) { ConcurrentHashMap.newKeySet() }
        sendStateByConnection.computeIfAbsent(conn) { ConnectionSendState() }
        connections.add(conn)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        connections.remove(conn)
        pendingEntriesByConnection.remove(conn)
        announcedEntriesByConnection.remove(conn)
        sendStateByConnection.remove(conn)
        clientSubscriptions.remove(conn)

        val publishers = synchronized(topicMutationLock) {
            clientPublishers.remove(conn)?.values?.toSet().orEmpty()
        }
        for (entry in publishers) {
            handleLastClientPublisherRemoved(entry)
        }
    }

    override fun stop() {
        if (serverInstance == this) {
            serverInstance = null
        }
        try {
            super.stop()
        } catch (e: Exception) {
            // Teardown failures are surfaced instead of swallowed: a port that fails to
            // release silently breaks the next server instance on the same JVM.
            println("NT4Server: stop() failed: ${e::class.java.simpleName}: ${e.message}")
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        if (message.length > NT4Json.MAX_JSON_CHARS) {
            reportRateLimited("rejected JSON frame", rejectedTextFrames, null)
            return
        }
        try {
            val parsedList = NT4Json.parseMessages(message)
            for (msg in parsedList) {
                processParsedMessage(conn, msg)
            }
        } catch (e: Exception) {
            reportRateLimited("rejected JSON frame", rejectedTextFrames, e)
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
                    if (decoded.dataValue != null) {
                        synchronized(topicMutationLock) {
                            val entry = getEntryForId(conn, decoded.id)
                            if (
                                entry != null &&
                                entries[entry.topic] === entry &&
                                !serverOwnedTopics.contains(entry.topic)
                            ) {
                                val receivedAtUs = com.areslib.util.RobotClock.currentTimeMillis() * 1_000L
                                val normalizedTimestamp = normalizeClientTimestamp(decoded.timestamp, receivedAtUs)
                                    ?: return@synchronized
                                val newValue = NT4Value.fromObject(decoded.dataValue)
                                if (
                                    newValue.typeString == entry.value.typeString &&
                                    entry.update(newValue, normalizedTimestamp)
                                ) {
                                    markDirty(entry)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            reportRateLimited("rejected binary frame", rejectedBinaryFrames, e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        reportRateLimited("WebSocket error", socketErrors, ex)
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
        if (pubUID < 0 || topic.length > MAX_TOPIC_NAME_CHARS || topic.startsWith('$')) return
        val type = msg.type ?: "string"
        if (type !in SUPPORTED_TOPIC_TYPES) return
        var isNew = false
        var previous: NT4Entry? = null
        lateinit var entry: NT4Entry
        synchronized(topicMutationLock) {
            if (serverOwnedTopics.contains(topic)) return
            val publishers = clientPublishers.computeIfAbsent(conn) { ConcurrentHashMap() }
            if (!publishers.containsKey(pubUID.toLong()) && publishers.size >= MAX_PUBLISHERS_PER_CLIENT) return

            val existing = entries[topic]
            if (existing != null) {
                if (existing.value.typeString != type) return
                entry = existing
            } else {
                if (entries.size >= MAX_TOPICS) return
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
                entry = NT4Entry(
                    nextTopicId.getAndIncrement(),
                    topic,
                    NT4Value.fromObject(defaultValue),
                    hasValue = false
                )
                entries[topic] = entry
            }
            previous = publishers.put(pubUID.toLong(), entry)
        }
        val displacedEntry = previous
        if (displacedEntry != null && displacedEntry !== entry) {
            handleLastClientPublisherRemoved(displacedEntry)
        }

        // A publish request always receives an acknowledgement announcement containing the
        // caller's connection-local publisher UID. Other subscribers must not see that UID.
        sendAnnouncement(conn, entry, pubUID)
        if (isNew) {
            announceEntryToSubscribers(entry, except = conn)
        }
        entry.notifyListeners(NT4EventType.TOPIC_PUBLISHED, entry.value)
    }

    private fun handleUnpublish(conn: WebSocket, msg: NT4Json.ParsedMessage) {
        val pubUID = msg.pubUid ?: return
        lateinit var entry: NT4Entry
        synchronized(topicMutationLock) {
            val publishers = clientPublishers[conn] ?: return
            entry = publishers.remove(pubUID.toLong()) ?: return
            if (publishers.isEmpty()) clientPublishers.remove(conn, publishers)
        }
        handleLastClientPublisherRemoved(entry)
    }

    private fun isPublishedByAnyClient(entry: NT4Entry): Boolean {
        return clientPublishers.values.any { publishers -> publishers.containsValue(entry) }
    }

    private fun handleLastClientPublisherRemoved(entry: NT4Entry) {
        synchronized(topicMutationLock) {
            if (isPublishedByAnyClient(entry) || serverOwnedTopics.contains(entry.topic)) return
            if (!entries.remove(entry.topic, entry)) return
        }
        synchronized(dirtyEntriesLock) {
            dirtyEntries.remove(entry)
        }
        for (pending in pendingEntriesByConnection.values) pending.remove(entry)
        unannounceDeletedEntry(entry)
        entry.notifyListeners(NT4EventType.TOPIC_UNPUBLISHED, entry.value)
    }

    private fun handleSubscribe(conn: WebSocket, msg: NT4Json.ParsedMessage) {
        val subUid = msg.subUid ?: return
        if (msg.topics.isEmpty() || msg.topics.size > NT4Json.MAX_TOPICS) return
        val topics = ArrayList<String>(msg.topics.size)

        for (t in msg.topics) {
            val topic = t.trimStart('/')
            if (topic.length > MAX_TOPIC_NAME_CHARS) return
            topics.add(topic)
        }
        val subscription = ClientSubscription(topics, msg.prefix)
        val subscriptions = clientSubscriptions.computeIfAbsent(conn) { ConcurrentHashMap() }
        if (!subscriptions.containsKey(subUid) && subscriptions.size >= MAX_SUBSCRIPTIONS_PER_CLIENT) return
        subscriptions[subUid] = subscription

        val matchingEntries = ArrayList<NT4Entry>()
        for (entry in entries.values) {
            if (subscription.matches(entry.topic)) {
                matchingEntries.add(entry)
            }
        }
        sendAnnouncements(conn, matchingEntries)
        sendBinaryEntries(conn, matchingEntries.filter(NT4Entry::hasValue))
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

    private fun announceEntryToSubscribers(entry: NT4Entry, except: WebSocket? = null) {
        for (conn in connections) {
            if (conn !== except && isSubscribed(conn, entry)) sendAnnouncement(conn, entry)
        }
    }

    private fun sendAnnouncement(conn: WebSocket, entry: NT4Entry, pubUid: Int? = null) {
        if (sendText(conn, NT4Json.buildAnnounceSingle(entry, pubUid))) {
            announcedEntriesByConnection.computeIfAbsent(conn) { ConcurrentHashMap.newKeySet() }.add(entry)
        }
    }

    private fun sendAnnouncements(conn: WebSocket, entries: List<NT4Entry>) {
        var start = 0
        while (start < entries.size) {
            val end = (start + MAX_ENTRIES_PER_SEND).coerceAtMost(entries.size)
            val batch = entries.subList(start, end)
            if (!sendText(conn, NT4Json.buildAnnounceArray(batch))) return
            announcedEntriesByConnection.computeIfAbsent(conn) { ConcurrentHashMap.newKeySet() }.addAll(batch)
            start = end
        }
    }

    private fun unannounceDeletedEntry(entry: NT4Entry) {
        val message = NT4Json.buildUnannounceSingle(entry)
        for (conn in connections) {
            val announced = announcedEntriesByConnection[conn] ?: continue
            if (announced.remove(entry)) sendText(conn, message)
        }
    }

    private fun sendText(conn: WebSocket, message: String): Boolean = try {
        conn.send(message)
        true
    } catch (e: Exception) {
        reportRateLimited("WebSocket send failure", sendFailures, e)
        false
    }

    private fun heartbeat(conn: WebSocket, clientTime: Long) {
        val binMsg = encodeNT4Message(com.areslib.util.RobotClock.currentTimeMillis() * 1000L, -1L, -1L, 2, clientTime)
        sendBinaryBuffer(conn, binMsg)
    }

    private fun sendBinaryEntries(conn: WebSocket, entries: List<NT4Entry>): Boolean {
        var start = 0
        while (start < entries.size) {
            val end = (start + MAX_ENTRIES_PER_SEND).coerceAtMost(entries.size)
            val timestamp = com.areslib.util.RobotClock.currentTimeMillis() * 1_000L
            try {
                if (!sendBinaryBuffer(conn, encodeNT4Messages(timestamp, entries, start, end))) return false
            } catch (e: IOException) {
                reportRateLimited("NT4 encode failure", sendFailures, e)
                return false
            }
            start = end
        }
        return true
    }

    private fun sendBinaryBuffer(conn: WebSocket, buffer: ByteBuffer): Boolean {
        return try {
            conn.send(buffer)
            true
        } catch (e: Exception) {
            reportRateLimited("WebSocket send failure", sendFailures, e)
            false
        }
    }

    @Synchronized
    fun encodeNT4Messages(timestamp: Long, entries: List<NT4Entry>): ByteBuffer {
        return encodeNT4Messages(timestamp, entries, 0, entries.size)
    }

    @Synchronized
    private fun encodeNT4Messages(
        timestamp: Long,
        entries: List<NT4Entry>,
        startIndex: Int,
        endIndex: Int
    ): ByteBuffer {
        encodeBuffer.reset()
        for (index in startIndex until endIndex) {
            val entry = entries[index]
            val dataType = getTypeIdFromValue(entry.value)
            packer.packArrayHeader(4)
            packer.packLong(entry.id.toLong())
            packer.packLong(if (entry.timestampUs == Long.MIN_VALUE) timestamp else entry.timestampUs)
            packer.packInt(dataType)
            packDataValue(packer, dataType, entry.value.borrowedValueForEncoding())
        }
        packer.flush()
        // Java-WebSocket is allowed to enqueue the ByteBuffer by reference. Return an owned
        // snapshot because the shared encoder stream is reused by the next publication.
        return ByteBuffer.wrap(encodeBuffer.toByteArray())
    }

    @Synchronized
    fun encodeNT4Message(
        timestamp: Long,
        topicId: Long,
        @Suppress("UNUSED_PARAMETER") pubUID: Long,
        dataType: Int,
        dataValue: Any
    ): ByteBuffer {
        encodeBuffer.reset()
        packer.packArrayHeader(4)
        packer.packLong(topicId)
        packer.packLong(timestamp)
        packer.packInt(dataType)
        packDataValue(packer, dataType, dataValue)
        packer.flush()
        return ByteBuffer.wrap(encodeBuffer.toByteArray())
    }

    private fun encodeOwnedEntries(
        slot: OwnedSendSlot,
        timestamp: Long,
        entries: List<NT4Entry>,
        startIndex: Int,
        endIndex: Int
    ): ByteBuffer {
        slot.reset()
        val ownedPacker = slot.messagePacker
        for (index in startIndex until endIndex) {
            val entry = entries[index]
            val dataType = getTypeIdFromValue(entry.value)
            ownedPacker.packArrayHeader(4)
            ownedPacker.packLong(entry.id.toLong())
            ownedPacker.packLong(if (entry.timestampUs == Long.MIN_VALUE) timestamp else entry.timestampUs)
            ownedPacker.packInt(dataType)
            packDataValue(ownedPacker, dataType, entry.value.borrowedValueForEncoding())
        }
        return slot.finish()
    }

    private fun packDataValue(
        targetPacker: org.msgpack.core.MessagePacker,
        dataType: Int,
        dataValue: Any
    ) {
        when (NT4Value.fromId(dataType)) {
            NT4Type.BOOLEAN -> targetPacker.packBoolean(dataValue as Boolean)
            NT4Type.DOUBLE -> targetPacker.packDouble((dataValue as Number).toDouble())
            NT4Type.INT -> targetPacker.packLong((dataValue as Number).toLong())
            NT4Type.FLOAT -> targetPacker.packFloat((dataValue as Number).toFloat())
            NT4Type.STRING -> targetPacker.packString(dataValue.toString())
            NT4Type.BOOLEAN_ARRAY -> {
                val arr = dataValue as BooleanArray
                targetPacker.packArrayHeader(arr.size)
                for (b in arr) targetPacker.packBoolean(b)
            }
            NT4Type.DOUBLE_ARRAY -> {
                val arr = dataValue as DoubleArray
                targetPacker.packArrayHeader(arr.size)
                for (d in arr) targetPacker.packDouble(d)
            }
            NT4Type.INT_ARRAY -> {
                val arr = dataValue as LongArray
                targetPacker.packArrayHeader(arr.size)
                for (l in arr) targetPacker.packLong(l)
            }
            NT4Type.FLOAT_ARRAY -> {
                val arr = dataValue as FloatArray
                targetPacker.packArrayHeader(arr.size)
                for (f in arr) targetPacker.packFloat(f)
            }
            NT4Type.STRING_ARRAY -> {
                @Suppress("UNCHECKED_CAST")
                val arr = dataValue as Array<String>
                targetPacker.packArrayHeader(arr.size)
                for (s in arr) targetPacker.packString(s)
            }
            else -> {
                if (dataType == 5 || dataType == 7 || dataType == 8) {
                    val bytes = dataValue as? ByteArray ?: ByteArray(0)
                    targetPacker.packBinaryHeader(bytes.size)
                    targetPacker.writePayload(bytes)
                } else {
                    targetPacker.packNil()
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
            val list = ArrayList<NT4Message>()
            while (unpacker.hasNext()) {
                if (unpacker.nextFormat.valueType.name != "ARRAY") {
                    throw IOException("NT4 frame contains a non-array message")
                }
                val header = unpacker.unpackArrayHeader()
                if (header != 4) throw IOException("NT4 update tuple must have 4 elements, got $header")
                requireDecodedLength("message count", list.size + 1, MAX_MESSAGES_PER_FRAME)
                list.add(decodeSingleNT4Message(unpacker))
            }
            return list
        } finally {
            unpacker.close()
        }
    }

    private fun decodeSingleNT4Message(unpacker: MessageUnpacker): NT4Message {
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
        require(normalizedTopic.length <= MAX_TOPIC_NAME_CHARS) {
            "NT4 topic exceeds $MAX_TOPIC_NAME_CHARS characters"
        }
        require(!normalizedTopic.startsWith('$')) { "NT4 topics beginning with '$' are server-reserved" }
        val serverTimestampUs = com.areslib.util.RobotClock.currentTimeMillis() * 1_000L
        var isNew = false
        var updated = false
        var replacedEntry: NT4Entry? = null
        lateinit var entry: NT4Entry
        synchronized(topicMutationLock) {
            val existing = entries[normalizedTopic]
            if (existing == null) {
                require(entries.size < MAX_TOPICS) { "NT4 topic limit of $MAX_TOPICS reached" }
            }
            serverOwnedTopics.add(normalizedTopic)
            revokeClientPublishersLocked(normalizedTopic)
            when {
                existing == null -> {
                    isNew = true
                    entry = NT4Entry(
                        nextTopicId.getAndIncrement(),
                        normalizedTopic,
                        value,
                        hasValue = true,
                        timestampUs = serverTimestampUs
                    )
                    entries[normalizedTopic] = entry
                }
                existing.value.typeString == value.typeString -> {
                    entry = existing
                    updated = existing.replaceAuthoritatively(value, serverTimestampUs)
                }
                else -> {
                    // NT4 types are immutable for one announcement lifetime. End the client-owned
                    // announcement and create a fresh server-owned topic with a new topic id.
                    replacedEntry = existing
                    entry = NT4Entry(
                        nextTopicId.getAndIncrement(),
                        normalizedTopic,
                        value,
                        hasValue = true,
                        timestampUs = serverTimestampUs
                    )
                    entries[normalizedTopic] = entry
                    isNew = true
                }
            }
        }

        replacedEntry?.let { oldEntry ->
            synchronized(dirtyEntriesLock) { dirtyEntries.remove(oldEntry) }
            for (pending in pendingEntriesByConnection.values) pending.remove(oldEntry)
            unannounceDeletedEntry(oldEntry)
            oldEntry.notifyListeners(NT4EventType.TOPIC_UNPUBLISHED, oldEntry.value)
        }
        if (isNew || updated) markDirty(entry)

        if (isNew) {
            announceEntryToSubscribers(entry)
            entry.notifyListeners(NT4EventType.TOPIC_PUBLISHED, entry.value)
        } else if (updated) {
            entry.notifyListeners(NT4EventType.TOPIC_UPDATED, entry.value)
        }
        return entry
    }

    /** Must be called with [topicMutationLock] held. */
    private fun revokeClientPublishersLocked(topic: String) {
        val emptyConnections = ArrayList<WebSocket>()
        for ((connection, publishers) in clientPublishers) {
            publishers.entries.removeIf { it.value.topic == topic }
            if (publishers.isEmpty()) emptyConnections.add(connection)
        }
        for (connection in emptyConnections) {
            val publishers = clientPublishers[connection] ?: continue
            if (publishers.isEmpty()) clientPublishers.remove(connection, publishers)
        }
    }

    private fun markDirty(entry: NT4Entry) {
        synchronized(dirtyEntriesLock) {
            dirtyEntries.add(entry)
        }
    }

    private fun drainDirtyEntries(): MutableSet<NT4Entry> {
        synchronized(dirtyEntriesLock) {
            if (dirtyEntries.isEmpty()) {
                reusableDrainedEntries.clear()
                return reusableDrainedEntries
            }
            reusableDrainedEntries.clear()
            val currentDirty = dirtyEntries
            dirtyEntries = reusableDrainedEntries
            reusableDrainedEntries = currentDirty
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

        try {
            for (conn in connections) {
                // A connection may close (and onClose may have already torn its maps down)
                // while this snapshot is iterated; never re-insert state for it.
                if (!conn.isOpen) continue
                val pendingEntries = pendingEntriesByConnection.computeIfAbsent(conn) {
                    ConcurrentHashMap.newKeySet()
                }
                if (!conn.isOpen) {
                    // Closed between the check and the insert: drop what we just added.
                    pendingEntriesByConnection.remove(conn)
                    sendStateByConnection.remove(conn)
                    continue
                }
                for (entry in currentDirty) {
                    if (isSubscribed(conn, entry)) {
                        pendingEntries.add(entry)
                    }
                }
            }
        } finally {
            // Entries are now retained per connection. Reuse the detached set on the next frame.
            currentDirty.clear()
        }

        for (conn in connections) {
            if (!conn.isOpen) continue
            val pendingEntries = pendingEntriesByConnection[conn] ?: continue
            val sendState = sendStateByConnection.computeIfAbsent(conn) { ConnectionSendState() }
            if (!conn.isOpen) {
                pendingEntriesByConnection.remove(conn)
                sendStateByConnection.remove(conn)
                continue
            }
            val ownedSlot = sendState.acquireIfDrained(conn) ?: continue
            if (pendingEntries.isEmpty()) continue

            entriesToSendBuffer.clear()
            pendingEntries.filterTo(entriesToSendBuffer, NT4Entry::hasValue)
            if (entriesToSendBuffer.isNotEmpty()) {
                val end = MAX_ENTRIES_PER_SEND.coerceAtMost(entriesToSendBuffer.size)
                try {
                    val binMsg = encodeOwnedEntries(ownedSlot, timestamp, entriesToSendBuffer, 0, end)
                    if (sendBinaryBuffer(conn, binMsg)) {
                        for (index in 0 until end) pendingEntries.remove(entriesToSendBuffer[index])
                    }
                } catch (e: IOException) {
                    reportRateLimited("NT4 encode failure", sendFailures, e)
                }
            }
        }
    }

    private fun normalizeClientTimestamp(timestampUs: Long, receivedAtUs: Long): Long? {
        if (timestampUs == 0L) return receivedAtUs
        val minimum = receivedAtUs - MAX_CLIENT_TIMESTAMP_SKEW_US
        val maximum = receivedAtUs + MAX_CLIENT_TIMESTAMP_SKEW_US
        return timestampUs.takeIf { it in minimum..maximum }
    }

    /** Diagnostic used by allocation regressions; includes initial and growth backing arrays. */
    internal fun ownedSendBufferAllocationCount(): Long = ownedSendBufferAllocations.get()

    private fun reportRateLimited(label: String, counter: AtomicLong, error: Exception?) {
        val occurrence = counter.incrementAndGet()
        if (occurrence == 1L || occurrence and (occurrence - 1L) == 0L) {
            val detail = error?.message?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
            println("[NT4Server] $label ($occurrence occurrences)$detail")
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
        internal const val MAX_TOPIC_NAME_CHARS = 1_024
        internal const val MAX_TOPICS = 16_384
        internal const val MAX_PUBLISHERS_PER_CLIENT = 4_096
        internal const val MAX_SUBSCRIPTIONS_PER_CLIENT = 1_024
        internal const val MAX_ENTRIES_PER_SEND = 128
        internal const val MAX_CLIENT_TIMESTAMP_SKEW_US = 60_000_000L
        private const val INITIAL_OWNED_SEND_CAPACITY = 8_192
        private val SUPPORTED_TOPIC_TYPES = setOf(
            "boolean", "double", "int", "float", "string",
            "boolean[]", "double[]", "int[]", "float[]", "string[]"
        )

        private var serverInstance: NT4Server? = null
        private var shutdownHookAdded = false
        private val entries = ConcurrentHashMap<String, NT4Entry>()
        private val serverOwnedTopics = ConcurrentHashMap.newKeySet<String>()
        private val topicMutationLock = Any()
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
                        serverInstance?.stop()
                    } catch (_: Exception) {}
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
            synchronized(topicMutationLock) {
                entries.clear()
                serverOwnedTopics.clear()
                nextTopicId.set(1)
            }
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

        /**
         * Copies a retained double-array value into caller-owned storage without allocating.
         *
         * @return source element count, or `-1` when the topic has no published double-array value.
         * Callers must reject a count larger than [destination] because only the fitting prefix is
         * copied.
         */
        @JvmStatic
        fun copyDoubleArray(topic: String, destination: DoubleArray): Int {
            if (serverInstance == null) return -1
            val entry = getEntryFlexible(topic) ?: return -1
            if (!entry.hasValue) return -1
            val source = (entry.value as? NT4Value.DoubleArrayVal)?.borrowedArray() ?: return -1
            source.copyInto(destination, endIndex = minOf(source.size, destination.size))
            return source.size
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
