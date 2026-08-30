package com.ares.analytics.service

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.util.Locale

internal interface OAuthTokenStore {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun delete(): Boolean
    val protectionDescription: String
}

internal class FileOAuthTokenStore(
    private val file: File,
    private val secretsWriter: (File, ByteArray) -> Unit = ::writeSecrets,
) : OAuthTokenStore {
    override fun read(): ByteArray? = file.takeIf(File::isFile)?.readBytes()

    override fun write(bytes: ByteArray) = secretsWriter(file, bytes)

    override fun delete(): Boolean = !file.exists() || file.delete()

    override val protectionDescription: String = "owner-only local token file"
}

/** Windows DPAPI binds encrypted OAuth tokens to the current Windows user account. */
internal class WindowsDpapiOAuthTokenStore(
    private val encryptedFile: File,
    private val secretsWriter: (File, ByteArray) -> Unit = ::writeSecrets,
) : OAuthTokenStore {
    override fun read(): ByteArray? = encryptedFile
        .takeIf(File::isFile)
        ?.readBytes()
        ?.let(Crypt32Util::cryptUnprotectData)

    override fun write(bytes: ByteArray) {
        val encrypted = Crypt32Util.cryptProtectData(bytes)
        secretsWriter(encryptedFile, encrypted)
    }

    override fun delete(): Boolean = !encryptedFile.exists() || encryptedFile.delete()

    override val protectionDescription: String = "Windows DPAPI (current user)"
}

internal fun createOAuthTokenStore(
    authFilePath: String,
    secretsWriter: (File, ByteArray) -> Unit,
): OAuthTokenStore {
    val authFile = File(authFilePath)
    val defaultPath = AppDataPaths.file("auth.json")
    val isDefaultStore = runCatching { authFile.canonicalFile == defaultPath.canonicalFile }.getOrDefault(false)
    val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
    return if (isWindows && isDefaultStore) {
        WindowsDpapiOAuthTokenStore(
            encryptedFile = File(authFile.parentFile, "auth.dpapi"),
            secretsWriter = secretsWriter,
        )
    } else {
        FileOAuthTokenStore(authFile, secretsWriter)
    }
}
