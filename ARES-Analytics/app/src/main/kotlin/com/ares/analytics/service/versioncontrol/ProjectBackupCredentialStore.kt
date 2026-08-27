package com.ares.analytics.service.versioncontrol

import com.ares.analytics.service.AppDataPaths
import com.ares.analytics.service.writeSecrets
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.util.Locale

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

internal class WindowsDpapiProjectBackupCredentialStore(
    private val file: File,
    private val writer: (File, ByteArray) -> Unit = ::writeSecrets,
) : ProjectBackupCredentialStore {
    override fun read(): ByteArray? = readBoundedCredential(file)?.let(Crypt32Util::cryptUnprotectData)
    override fun write(bytes: ByteArray) {
        require(bytes.size <= MAX_PROJECT_BACKUP_CREDENTIAL_BYTES) { "The GitHub credential record is unexpectedly large." }
        writer(file, Crypt32Util.cryptProtectData(bytes))
    }
    override fun delete(): Boolean = !file.exists() || file.delete()
    override val protectionDescription: String = "Windows DPAPI (current user)"
}

private const val MAX_PROJECT_BACKUP_CREDENTIAL_BYTES = 64 * 1024

private fun readBoundedCredential(file: File): ByteArray? = file.takeIf(File::isFile)?.let {
    require(it.length() <= MAX_PROJECT_BACKUP_CREDENTIAL_BYTES) { "The saved GitHub credential record is unexpectedly large." }
    it.readBytes()
}

internal fun createProjectBackupCredentialStore(): ProjectBackupCredentialStore {
    val directory = AppDataPaths.rootDirectory()
    val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
    return if (isWindows) {
        WindowsDpapiProjectBackupCredentialStore(File(directory, "github.dpapi"))
    } else {
        FileProjectBackupCredentialStore(File(directory, "github.json"))
    }
}
