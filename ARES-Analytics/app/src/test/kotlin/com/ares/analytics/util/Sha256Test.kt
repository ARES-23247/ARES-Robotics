package com.ares.analytics.util

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Sha256Test {
    @Test
    fun `standard and UTF8 vectors retain lowercase encoding and prefix truncation`() {
        val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(abc, Sha256.hex("abc"))
        assertEquals("1d8d1fe98c94deeab7a354f4451212bfc5539092570accadfee85382a2c82e59", Sha256.hex("ARES 温度\n"))
        for (count in listOf(1, 12, 31, 32, 33, Int.MAX_VALUE))
            assertEquals(abc.take(minOf(count, 32) * 2), Sha256.prefixHex("abc", count))
        assertFailsWith<IllegalArgumentException> { Sha256.prefixHex("abc", -1) }
        assertNotEquals(Sha256.hex("a\r\nb"), Sha256.hex("a\nb"))
        assertEquals(Sha256.hex("a\rb"), Sha256.canonicalTextHex("a\rb"))
    }

    @Test
    fun `streaming and composite hashes preserve chunk boundaries and digest reset`() {
        val bytes = ByteArray(65536 + 17) { it.toByte() }
        val expected = "4de6ff0a51a4cd16f2bb867a3f3ff954ee6509504b64ba03c20eb4b34adf2b81"
        val file = Files.createTempFile("ares-sha256-stream", ".bin").toFile()
        try {
            file.writeBytes(bytes)
            assertEquals(expected, Sha256.fileHex(file))
            assertEquals(expected, Sha256.hex(bytes))
            assertEquals(expected, Sha256.compositeHex {
                update(bytes, 0, 17)
                update(bytes, 17, bytes.size - 17)
            })
            val digest = Sha256.newDigest().apply { update(bytes) }
            assertEquals(expected, Sha256.finishHex(digest))
            val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            assertEquals(emptyHash, Sha256.finishHex(digest))
            file.writeBytes(byteArrayOf())
            assertEquals(emptyHash, Sha256.fileHex(file))
        } finally {
            assertTrue(file.delete(), "Streaming input must be closed after hashing")
        }
    }

    @Test
    fun `bytes text and file use the same canonical digest`() {
        val bytes = "ARES integrity".toByteArray()
        val file = Files.createTempFile("ares-sha256", ".txt").toFile().apply {
            writeBytes(bytes)
        }
        try {
            val expected = "1c6b1f8b77ac37079967a3ac147ce4a0f53be8fb0608ee2cb9186a2e270c5b3a"
            assertEquals(expected, Sha256.hex(bytes))
            assertEquals(expected, Sha256.hex("ARES integrity"))
            assertEquals(expected, Sha256.fileHex(file))
        } finally {
            assertTrue(file.delete())
        }
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
