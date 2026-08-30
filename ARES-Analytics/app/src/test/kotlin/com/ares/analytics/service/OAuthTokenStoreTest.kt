package com.ares.analytics.service

import java.nio.file.Files
import java.util.Locale
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
    fun `windows DPAPI token store round trips encrypted bytes`() {
        if (!System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")) return
        val directory = Files.createTempDirectory("ares-token-dpapi").toFile()
        val encrypted = directory.resolve("auth.dpapi")
        val bytes = "sensitive refresh token".toByteArray()
        val store = WindowsDpapiOAuthTokenStore(encrypted)
        try {
            store.write(bytes)
            assertContentEquals(bytes, store.read())
            assertTrue(encrypted.isFile)
            assertFalse(encrypted.readBytes().contentEquals(bytes))
            assertContentEquals(bytes, store.read())
        } finally {
            store.delete()
            directory.deleteRecursively()
        }
    }
}
