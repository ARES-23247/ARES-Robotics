package com.ares.analytics.viewmodel

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MjpegFrameAssemblerTest {
    @Test
    fun `extracts markers split across network reads`() {
        val assembler = MjpegFrameAssembler()
        val frames = mutableListOf<ByteArray>()

        assembler.offer(byteArrayOf(0x01, 0xFF.toByte()), 2, frames::add)
        assembler.offer(byteArrayOf(0xD8.toByte(), 0x10, 0x20, 0xFF.toByte()), 4, frames::add)
        assembler.offer(byteArrayOf(0xD9.toByte(), 0x02), 2, frames::add)

        assertEquals(1, frames.size)
        assertContentEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x10, 0x20, 0xFF.toByte(), 0xD9.toByte()),
            frames.single(),
        )
    }

    @Test
    fun `emits multiple frames from one read without retaining boundary bytes`() {
        val assembler = MjpegFrameAssembler()
        val frames = mutableListOf<ByteArray>()
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte(),
            0x00,
            0xFF.toByte(), 0xD8.toByte(), 0x02, 0xFF.toByte(), 0xD9.toByte(),
        )

        assembler.offer(bytes, bytes.size, frames::add)

        assertEquals(2, frames.size)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte()), frames[0])
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x02, 0xFF.toByte(), 0xD9.toByte()), frames[1])
    }

    @Test
    fun `drops an oversized partial frame and resynchronizes`() {
        val assembler = MjpegFrameAssembler(maxFrameBytes = 5)
        val frames = mutableListOf<ByteArray>()
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03, 0x04,
            0xFF.toByte(), 0xD8.toByte(), 0x05, 0xFF.toByte(), 0xD9.toByte(),
        )

        assembler.offer(bytes, bytes.size, frames::add)

        assertEquals(1, frames.size)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x05, 0xFF.toByte(), 0xD9.toByte()), frames.single())
    }
}
