package com.ares.analytics.service.integration

import com.ares.analytics.service.AppDataPaths
import com.ares.analytics.service.writeSecrets
import com.ares.analytics.shared.AppJson
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Locale

@Serializable
data class IntegrationCredential(
    val principal: String? = null,
    val secret: String,
)

interface IntegrationCredentialStore {
    fun read(providerId: String): IntegrationCredential?
    fun write(providerId: String, credential: IntegrationCredential)
    fun delete(providerId: String): Boolean
    val protectionDescription: String
}
@Serializable
private data class StoredIntegrationCredentials(
    val credentials: Map<String, IntegrationCredential> = emptyMap(),
    val schemaVersion: Int = 1,
)

internal class ProtectedIntegrationCredentialStore(
    private val file: File = defaultIntegrationCredentialFile(),
    private val protect: (ByteArray) -> ByteArray = defaultProtector(),
    private val unprotect: (ByteArray) -> ByteArray = defaultUnprotector(),
    private val secretsWriter: (File, ByteArray) -> Unit = ::writeSecrets,
    override val protectionDescription: String = defaultProtectionDescription(),
) : IntegrationCredentialStore {
    private val lock = Any()

    override fun read(providerId: String): IntegrationCredential? = synchronized(lock) {
        load().credentials[providerId]
    }

    override fun write(providerId: String, credential: IntegrationCredential) = synchronized(lock) {
        validateProviderId(providerId)
        require(credential.secret.length in 8..8_192) { "Integration secret length is outside the supported range" }
        require(credential.principal == null || credential.principal.length <= 1_024) {
            "Integration principal exceeds the supported length"
        }
        val current = load()
        persist(current.copy(credentials = current.credentials + (providerId to credential)))
    }

    override fun delete(providerId: String): Boolean = synchronized(lock) {
        val current = load()
        if (providerId !in current.credentials) return@synchronized true
        persist(current.copy(credentials = current.credentials - providerId))
        true
    }

    private fun load(): StoredIntegrationCredentials {
        if (!file.isFile) return StoredIntegrationCredentials()
        val protectedBytes = file.readBytes()
        require(protectedBytes.size <= MAX_CREDENTIAL_FILE_BYTES) { "Integration credential store is too large" }
        return AppJson.decodeFromString(unprotect(protectedBytes).toString(Charsets.UTF_8))
    }

    private fun persist(credentials: StoredIntegrationCredentials) {
        val plainBytes = AppJson.encodeToString(credentials).toByteArray(Charsets.UTF_8)
        require(plainBytes.size <= MAX_CREDENTIAL_FILE_BYTES) { "Integration credential store is too large" }
        secretsWriter(file, protect(plainBytes))
    }

    private fun validateProviderId(providerId: String) {
        require(providerId.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))) {
            "Integration provider ID is invalid"
        }
    }

    private companion object {
        const val MAX_CREDENTIAL_FILE_BYTES = 256 * 1_024
    }
}

internal fun createIntegrationCredentialStore(): IntegrationCredentialStore = ProtectedIntegrationCredentialStore()

private fun isWindows(): Boolean = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

private fun defaultIntegrationCredentialFile(): File = AppDataPaths.file(
    if (isWindows()) "integrations.dpapi" else "integrations.secrets.json"
)

private fun defaultProtector(): (ByteArray) -> ByteArray = if (isWindows()) {
    Crypt32Util::cryptProtectData
} else {
    { bytes -> bytes }
}

private fun defaultUnprotector(): (ByteArray) -> ByteArray = if (isWindows()) {
    Crypt32Util::cryptUnprotectData
} else {
    { bytes -> bytes }
}

private fun defaultProtectionDescription(): String = if (isWindows()) {
    "Windows DPAPI (current user)"
} else {
    "owner-only local secret file"
}
