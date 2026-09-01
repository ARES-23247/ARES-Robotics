package com.ares.analytics.util

import java.io.File
import java.security.MessageDigest

/** One canonical SHA-256 implementation for Studio integrity and evidence boundaries. */
internal object Sha256 {
    fun hex(bytes: ByteArray): String = digest(bytes).toHex()

    fun hex(text: String): String = hex(text.toByteArray(Charsets.UTF_8))

    fun canonicalTextHex(text: String): String = hex(text.replace("\r\n", "\n"))

    fun fileHex(file: File): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    fun prefixHex(text: String, byteCount: Int): String {
        require(byteCount > 0) { "byteCount must be positive" }
        return digest(text.toByteArray(Charsets.UTF_8)).take(byteCount).toByteArray().toHex()
    }

    /** Supports framed/composite hashes without duplicating the algorithm or hex encoding. */
    fun compositeHex(update: MessageDigest.() -> Unit): String =
        newDigest().apply(update).let(::finishHex)

    /** For streaming I/O boundaries that must update a digest while bytes are copied. */
    fun newDigest(): MessageDigest = MessageDigest.getInstance(ALGORITHM)

    fun finishHex(digest: MessageDigest): String = digest.digest().toHex()

    private fun digest(bytes: ByteArray): ByteArray = newDigest().digest(bytes)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private const val ALGORITHM = "SHA-256"
    private const val BUFFER_SIZE = 64 * 1024
}
