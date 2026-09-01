package com.ares.analytics.service.integration

import com.ares.analytics.service.security.PlatformSecretStore
import com.ares.analytics.service.security.createPlatformSecretStore
import com.ares.analytics.shared.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

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

internal class PlatformIntegrationCredentialStore(
    private val secretStore: PlatformSecretStore,
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
        val bytes = secretStore.read(INTEGRATION_CREDENTIAL_KEY) ?: return StoredIntegrationCredentials()
        require(bytes.size <= MAX_CREDENTIAL_BYTES) { "Integration credential store is too large" }
        return AppJson.decodeFromString(bytes.toString(Charsets.UTF_8))
    }

    private fun persist(credentials: StoredIntegrationCredentials) {
        val plainBytes = AppJson.encodeToString(credentials).toByteArray(Charsets.UTF_8)
        require(plainBytes.size <= MAX_CREDENTIAL_BYTES) { "Integration credential store is too large" }
        if (credentials.credentials.isEmpty()) {
            secretStore.delete(INTEGRATION_CREDENTIAL_KEY)
        } else {
            secretStore.write(INTEGRATION_CREDENTIAL_KEY, plainBytes)
        }
    }

    private fun validateProviderId(providerId: String) {
        require(providerId.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))) {
            "Integration provider ID is invalid"
        }
    }

    private companion object {
        const val MAX_CREDENTIAL_BYTES = 256 * 1_024
    }

    override val protectionDescription: String = secretStore.protectionDescription
}

internal fun createIntegrationCredentialStore(): IntegrationCredentialStore =
    PlatformIntegrationCredentialStore(createPlatformSecretStore())

private const val INTEGRATION_CREDENTIAL_KEY = "notification-integrations"
