package com.areslib.networktables

import org.msgpack.core.MessagePack
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

/** Connection-owned encoding storage; the caller must wait for transport drain before reuse. */
internal class NT4OwnedSendSlot(
    initialCapacity: Int,
    maxCapacity: Int,
    onAllocation: () -> Unit
) {
    private val output = ReusableByteArrayOutputStream(initialCapacity, maxCapacity, onAllocation)
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
