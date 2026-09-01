package com.ares.analytics.service

import com.ares.analytics.service.security.PlatformSecretStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OAuthTokenStoreTest {
    @Test
    fun `file token store round trips and deletes owner data`() {
        val directory = Files.createTempDirectory("ares-token-file").toFile()
        val file = directory.resolve("auth.json")
        val store = FileOAuthTokenStore(file)
        val bytes = "oauth token document".toByteArray()
        try {
            store.write(bytes)
            assertContentEquals(bytes, store.read())
            assertTrue(store.delete())
            assertFalse(file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `platform token store delegates without changing bytes`() {
        val platform = MemoryPlatformSecretStore()
        val bytes = "sensitive refresh token".toByteArray()
        val store = PlatformOAuthTokenStore(platform)

        store.write(bytes)

        assertContentEquals(bytes, store.read())
        assertTrue(store.delete())
        assertContentEquals(null, store.read())
    }

    private class MemoryPlatformSecretStore : PlatformSecretStore {
        private val values = mutableMapOf<String, ByteArray>()
        override fun read(key: String): ByteArray? = values[key]?.copyOf()
        override fun write(key: String, bytes: ByteArray) { values[key] = bytes.copyOf() }
        override fun delete(key: String): Boolean { values.remove(key); return true }
        override val protectionDescription: String = "test vault"
    }
}
