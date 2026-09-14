package com.areslib.logging

import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/** Counting and fast compression stages used by the asynchronous log writer. */
internal class CountingLogOutputStream(output: OutputStream) : FilterOutputStream(output) {
    @Volatile
    var count: Long = 0L
        private set

    override fun write(value: Int) {
        out.write(value)
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
        count += length.toLong()
    }
}

internal class FastLogGzipOutputStream(output: OutputStream, bufferBytes: Int) :
    GZIPOutputStream(output, bufferBytes, true) {
    init {
        def.setLevel(Deflater.BEST_SPEED)
    }
}
