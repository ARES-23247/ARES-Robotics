package com.ares.analytics.viewmodel

import java.io.ByteArrayOutputStream

/** Incrementally extracts complete JPEG frames without copying the whole receive buffer per read. */
internal class MjpegFrameAssembler(
    private val maxFrameBytes: Int = 2_000_000,
) {
    private val frame = ByteArrayOutputStream()
    private var collecting = false
    private var previous = -1

    fun offer(bytes: ByteArray, count: Int, onFrame: (ByteArray) -> Unit) {
        require(count in 0..bytes.size)
        for (index in 0 until count) {
            val current = bytes[index].toInt() and 0xFF

            if (!collecting) {
                if (previous == JPEG_MARKER_PREFIX && current == JPEG_START) {
                    frame.reset()
                    frame.write(JPEG_MARKER_PREFIX)
                    frame.write(JPEG_START)
                    collecting = true
                }
                previous = current
                continue
            }

            if (previous == JPEG_MARKER_PREFIX && current == JPEG_START) {
                // A new SOI before EOI means the partial frame was corrupt; resynchronize here.
                frame.reset()
                frame.write(JPEG_MARKER_PREFIX)
                frame.write(JPEG_START)
                previous = current
                continue
            }

            frame.write(current)
            if (previous == JPEG_MARKER_PREFIX && current == JPEG_END) {
                onFrame(frame.toByteArray())
                frame.reset()
                collecting = false
            } else if (frame.size() > maxFrameBytes) {
                frame.reset()
                collecting = false
            }
            previous = current
        }
    }

    private companion object {
        const val JPEG_MARKER_PREFIX = 0xFF
        const val JPEG_START = 0xD8
        const val JPEG_END = 0xD9
    }
}
