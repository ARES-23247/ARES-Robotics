package com.ares.analytics.util

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Sha256Test {
    @Test
    fun `bytes text and file use the same canonical digest`() {
        val bytes = "ARES integrity".toByteArray()
        val file = Files.createTempFile("ares-sha256", ".txt").toFile().apply {
            writeBytes(bytes)
            deleteOnExit()
        }

        val expected = "1c6b1f8b77ac37079967a3ac147ce4a0f53be8fb0608ee2cb9186a2e270c5b3a"
        assertEquals(expected, Sha256.hex(bytes))
        assertEquals(expected, Sha256.hex("ARES integrity"))
        assertEquals(expected, Sha256.fileHex(file))
    }

    @Test
    fun `canonical text normalizes Windows line endings only at the explicit boundary`() {
        assertEquals(Sha256.hex("left\nright"), Sha256.canonicalTextHex("left\r\nright"))
    }

    @Test
    fun `prefix length is expressed in digest bytes`() {
        assertEquals(Sha256.hex("robot").take(24), Sha256.prefixHex("robot", byteCount = 12))
        assertFailsWith<IllegalArgumentException> { Sha256.prefixHex("robot", byteCount = 0) }
    }
}
