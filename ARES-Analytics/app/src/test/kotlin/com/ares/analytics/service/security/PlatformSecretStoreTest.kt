package com.ares.analytics.service.security

import java.nio.file.Files
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformSecretStoreTest {
    @Test
    fun `windows DPAPI store round trips encrypted bytes`() {
        if (!System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")) return
        val directory = Files.createTempDirectory("ares-secret-dpapi").toFile()
        val bytes = "sensitive refresh token".toByteArray()
        val store = WindowsDpapiSecretStore(directory)
        try {
            store.write("oauth", bytes)
            val encrypted = directory.resolve("oauth.dpapi")
            assertTrue(encrypted.isFile)
            assertFalse(encrypted.readBytes().contentEquals(bytes))
            assertContentEquals(bytes, store.read("oauth"))
            assertTrue(store.delete("oauth"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `linux Secret Service sends secret through stdin and never arguments`() {
        val secret = "credential bytes".toByteArray()
        val calls = mutableListOf<Pair<List<String>, String?>>()
        val command = SecretToolCommand { arguments, input ->
            calls += arguments to input
            when (arguments.first()) {
                "lookup" -> SecretToolResult(0, java.util.Base64.getEncoder().encodeToString(secret) + "\n")
                else -> SecretToolResult(0, "")
            }
        }
        val store = LinuxSecretServiceStore(command)

        store.write("github", secret)
        assertContentEquals(secret, store.read("github"))
        assertTrue(store.delete("github"))

        val writeCall = calls.first()
        assertTrue(writeCall.first.none { it.contains("credential bytes") })
        assertEquals(java.util.Base64.getEncoder().encodeToString(secret) + "\n", writeCall.second)
    }

    @Test
    fun `linux Secret Service failures do not fall back to plaintext`() {
        val store = LinuxSecretServiceStore(SecretToolCommand { _, _ -> SecretToolResult(2, "keyring locked") })

        val error = assertFailsWith<IllegalStateException> { store.write("oauth", byteArrayOf(1, 2, 3)) }

        assertTrue(error.message.orEmpty().contains("keyring locked"))
    }
}
