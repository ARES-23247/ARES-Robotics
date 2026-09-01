package com.ares.analytics.service.versioncontrol

import com.ares.analytics.service.security.PlatformSecretStore
import com.ares.analytics.service.security.createPlatformSecretStore
import com.ares.analytics.service.writeSecrets
import java.io.File

internal interface ProjectBackupCredentialStore {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun delete(): Boolean
    val protectionDescription: String
}

internal class FileProjectBackupCredentialStore(
    private val file: File,
    private val writer: (File, ByteArray) -> Unit = ::writeSecrets,
) : ProjectBackupCredentialStore {
    override fun read(): ByteArray? = readBoundedCredential(file)
    override fun write(bytes: ByteArray) {
        require(bytes.size <= MAX_PROJECT_BACKUP_CREDENTIAL_BYTES) { "The GitHub credential record is unexpectedly large." }
        writer(file, bytes)
    }
    override fun delete(): Boolean = !file.exists() || file.delete()
    override val protectionDescription: String = "owner-only local credential file"
}

internal class PlatformProjectBackupCredentialStore(
    private val secretStore: PlatformSecretStore,
) : ProjectBackupCredentialStore {
    override fun read(): ByteArray? = secretStore.read(PROJECT_BACKUP_KEY)?.also(::requireBoundedCredential)
    override fun write(bytes: ByteArray) {
        requireBoundedCredential(bytes)
        secretStore.write(PROJECT_BACKUP_KEY, bytes)
    }
    override fun delete(): Boolean = secretStore.delete(PROJECT_BACKUP_KEY)
    override val protectionDescription: String = secretStore.protectionDescription
}

private const val MAX_PROJECT_BACKUP_CREDENTIAL_BYTES = 64 * 1024

private fun readBoundedCredential(file: File): ByteArray? = file.takeIf(File::isFile)?.let {
    require(it.length() <= MAX_PROJECT_BACKUP_CREDENTIAL_BYTES) { "The saved GitHub credential record is unexpectedly large." }
    it.readBytes()
}

private fun requireBoundedCredential(bytes: ByteArray) {
    require(bytes.size <= MAX_PROJECT_BACKUP_CREDENTIAL_BYTES) { "The GitHub credential record is unexpectedly large." }
}

internal fun createProjectBackupCredentialStore(): ProjectBackupCredentialStore =
    PlatformProjectBackupCredentialStore(createPlatformSecretStore())

private const val PROJECT_BACKUP_KEY = "github-project-backup"
