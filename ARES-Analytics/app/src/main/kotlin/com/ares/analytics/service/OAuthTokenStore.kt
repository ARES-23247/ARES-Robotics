package com.ares.analytics.service

import com.ares.analytics.service.security.PlatformSecretStore
import com.ares.analytics.service.security.createPlatformSecretStore
import java.io.File

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

/** The default application store delegates to the operating system's current-user vault. */
internal class PlatformOAuthTokenStore(
    private val secretStore: PlatformSecretStore,
) : OAuthTokenStore {
    override fun read(): ByteArray? = secretStore.read(OAUTH_TOKEN_KEY)
    override fun write(bytes: ByteArray) = secretStore.write(OAUTH_TOKEN_KEY, bytes)
    override fun delete(): Boolean = secretStore.delete(OAUTH_TOKEN_KEY)
    override val protectionDescription: String = secretStore.protectionDescription
}

internal fun createOAuthTokenStore(
    authFilePath: String,
    secretsWriter: (File, ByteArray) -> Unit,
): OAuthTokenStore {
    val authFile = File(authFilePath)
    val defaultPath = AppDataPaths.file("auth.json")
    val isDefaultStore = runCatching { authFile.canonicalFile == defaultPath.canonicalFile }.getOrDefault(false)
    return if (isDefaultStore) {
        PlatformOAuthTokenStore(createPlatformSecretStore())
    } else {
        FileOAuthTokenStore(authFile, secretsWriter)
    }
}

private const val OAUTH_TOKEN_KEY = "google-oauth"
