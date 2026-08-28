package com.ares.analytics.service

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import com.ares.analytics.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile

private const val MAX_WINDOWS_INSTALLER_BYTES = 1_073_741_824L
private const val UPDATE_DISK_HEADROOM_BYTES = 52_428_800L
private const val MAX_CHECKSUM_RESPONSE_BYTES = 4_096L

enum class WindowsUpdateFailureKind {
    UNSUPPORTED_PLATFORM,
    INVALID_CANDIDATE,
    INSUFFICIENT_SPACE,
    NETWORK,
    DIGEST_MISMATCH,
    SIGNATURE_MISMATCH,
    CANCELLED,
    IO,
}

enum class WindowsInstallerTrustMode {
    /** Exact SHA-256 from the matching immutable GitHub release asset. */
    GITHUB_RELEASE_SHA256,

    /** SHA-256 plus a valid Authenticode certificate pinned by this Studio build. */
    AUTHENTICODE,
}

sealed interface WindowsUpdateStageState {
    data object Idle : WindowsUpdateStageState
    data class Downloading(val version: String, val receivedBytes: Long, val totalBytes: Long) : WindowsUpdateStageState
    data class Verified(val update: StagedWindowsUpdate) : WindowsUpdateStageState
    data class Failed(val kind: WindowsUpdateFailureKind, val safeMessage: String) : WindowsUpdateStageState
}

data class StagedWindowsUpdate(
    val version: String,
    val installer: File,
    val sha256: String,
    val trustMode: WindowsInstallerTrustMode,
    val signerThumbprint: String?,
    val releasePageUrl: String,
)

data class InstallerSignature(
    val valid: Boolean,
    val thumbprint: String?,
    val subject: String? = null,
)

fun interface WindowsInstallerSignatureVerifier {
    suspend fun verify(installer: File): InstallerSignature
}

/** Uses Windows' Authenticode trust policy; it never trusts a publisher name from release metadata. */
class PowerShellAuthenticodeVerifier : WindowsInstallerSignatureVerifier {
    override suspend fun verify(installer: File): InstallerSignature = withContext(Dispatchers.IO) {
        val script = """
            ${'$'}signature = Get-AuthenticodeSignature -LiteralPath ${'$'}args[0]
            ${'$'}thumbprint = if (${'$'}null -ne ${'$'}signature.SignerCertificate) { ${'$'}signature.SignerCertificate.Thumbprint } else { '' }
            ${'$'}subject = if (${'$'}null -ne ${'$'}signature.SignerCertificate) { ${'$'}signature.SignerCertificate.Subject } else { '' }
            Write-Output ("{0}|{1}|{2}" -f ${'$'}signature.Status, ${'$'}thumbprint, ${'$'}subject)
        """.trimIndent()
        val process = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script, installer.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim().takeLast(4_096)
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@withContext InstallerSignature(false, null)
        }
        val exitCode = process.exitValue()
        if (exitCode != 0) return@withContext InstallerSignature(false, null)
        val parts = output.lineSequence().lastOrNull().orEmpty().split('|', limit = 3)
        InstallerSignature(
            valid = parts.getOrNull(0).equals("Valid", ignoreCase = true),
            thumbprint = parts.getOrNull(1)?.normalizeThumbprint()?.takeIf(String::isNotEmpty),
            subject = parts.getOrNull(2)?.take(512),
        )
    }
}

interface WindowsUpdateDownloadClient : AutoCloseable {
    /** Returns false without consuming bytes when the server did not honor a non-zero offset. */
    suspend fun download(url: String, offset: Long, consume: suspend (ByteArray, Int) -> Unit): Boolean
    suspend fun readChecksum(url: String): String
    override fun close() = Unit
}

class KtorWindowsUpdateDownloadClient(private val httpClient: HttpClient) : WindowsUpdateDownloadClient {
    override suspend fun download(
        url: String,
        offset: Long,
        consume: suspend (ByteArray, Int) -> Unit,
    ): Boolean = httpClient.prepareGet(url) {
        header(HttpHeaders.UserAgent, "ares-robotics-studio-updater/1")
        if (offset > 0L) header(HttpHeaders.Range, "bytes=$offset-")
    }.execute { response ->
        require(response.status == HttpStatusCode.OK || response.status == HttpStatusCode.PartialContent) {
            "Update server returned ${response.status.value}"
        }
        val resumed = response.status == HttpStatusCode.PartialContent
        if (offset > 0L && !resumed) return@execute false
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(64 * 1024)
        while (!channel.isClosedForRead) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count > 0) consume(buffer, count)
        }
        resumed
    }

    override suspend fun readChecksum(url: String): String = httpClient.prepareGet(url) {
        header(HttpHeaders.UserAgent, "ares-robotics-studio-updater/1")
    }.execute { response ->
        require(response.status == HttpStatusCode.OK) { "Checksum server returned ${response.status.value}" }
        val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        require(declaredLength == null || declaredLength <= MAX_CHECKSUM_RESPONSE_BYTES) { "Checksum response is too large" }
        response.bodyAsText().also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_CHECKSUM_RESPONSE_BYTES) { "Checksum response is too large" }
        }
    }

    override fun close() = httpClient.close()
}

class WindowsUpdateService(
    private val downloadClient: WindowsUpdateDownloadClient,
    private val trustedSignerThumbprints: Set<String>,
    private val signatureVerifier: WindowsInstallerSignatureVerifier = PowerShellAuthenticodeVerifier(),
    private val stagingRoot: File = File(AppDataPaths.rootDirectory(), "updates"),
    private val platformName: String = System.getProperty("os.name"),
    private val architecture: String = System.getProperty("os.arch"),
    private val installedVersion: String = BuildConfig.VERSION,
) {
    private val normalizedTrustedSigners = trustedSignerThumbprints.mapTo(linkedSetOf(), String::normalizeThumbprint)
    private val _state = MutableStateFlow<WindowsUpdateStageState>(WindowsUpdateStageState.Idle)
    val state: StateFlow<WindowsUpdateStageState> = _state

    suspend fun stage(candidate: WindowsUpdateCandidate): StagedWindowsUpdate? = withContext(Dispatchers.IO) {
        val validationFailure = validateCandidate(candidate)
        if (validationFailure != null) return@withContext fail(validationFailure.first, validationFailure.second)

        val versionDirectory = File(stagingRoot, candidate.version)
        versionDirectory.mkdirs()
        if (!versionDirectory.isDirectory) return@withContext fail(WindowsUpdateFailureKind.IO, "Update staging directory is unavailable")
        if (versionDirectory.usableSpace < candidate.sizeBytes + UPDATE_DISK_HEADROOM_BYTES) {
            return@withContext fail(WindowsUpdateFailureKind.INSUFFICIENT_SPACE, "Not enough disk space to stage this update")
        }

        val partial = File(versionDirectory, "${candidate.installerName}.partial")
        val destination = File(versionDirectory, candidate.installerName)
        return@withContext try {
            val expectedDigest = parseChecksum(downloadClient.readChecksum(candidate.checksumUrl), candidate.installerName)
            downloadAndHash(candidate, partial, allowResume = true)
            val actualSize = partial.length()
            require(actualSize == candidate.sizeBytes) {
                "Downloaded installer size mismatch (expected ${candidate.sizeBytes}, received $actualSize)"
            }
            val actualDigest = updateSha256(partial)
            if (!actualDigest.equals(expectedDigest, ignoreCase = true)) {
                partial.delete()
                return@withContext fail(WindowsUpdateFailureKind.DIGEST_MISMATCH, "Downloaded installer failed its SHA-256 check")
            }
            val signer = if (normalizedTrustedSigners.isEmpty()) {
                null
            } else {
                val signature = signatureVerifier.verify(partial)
                val actualSigner = signature.thumbprint?.normalizeThumbprint()
                if (!signature.valid || actualSigner == null || actualSigner !in normalizedTrustedSigners) {
                    partial.delete()
                    return@withContext fail(
                        WindowsUpdateFailureKind.SIGNATURE_MISMATCH,
                        "Downloaded installer is not signed by a trusted ARES publisher",
                    )
                }
                actualSigner
            }
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            val staged = StagedWindowsUpdate(
                version = candidate.version,
                installer = destination,
                sha256 = actualDigest,
                trustMode = if (signer == null) {
                    WindowsInstallerTrustMode.GITHUB_RELEASE_SHA256
                } else {
                    WindowsInstallerTrustMode.AUTHENTICODE
                },
                signerThumbprint = signer,
                releasePageUrl = candidate.releasePageUrl,
            )
            _state.value = WindowsUpdateStageState.Verified(staged)
            staged
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            _state.value = WindowsUpdateStageState.Failed(WindowsUpdateFailureKind.CANCELLED, "Update download was paused")
            throw cancelled
        } catch (failure: Exception) {
            fail(WindowsUpdateFailureKind.NETWORK, failure.message?.take(1_024) ?: "Update staging failed")
        }
    }

    fun close() = downloadClient.close()

    private suspend fun downloadAndHash(candidate: WindowsUpdateCandidate, partial: File, allowResume: Boolean) {
        val offset = partial.takeIf { allowResume && it.isFile }?.length()?.coerceAtMost(candidate.sizeBytes) ?: 0L
        if (offset == candidate.sizeBytes) {
            _state.value = WindowsUpdateStageState.Downloading(candidate.version, offset, candidate.sizeBytes)
            return
        }
        var received = offset
        val stream = FileOutputStream(partial, offset > 0L)
        val resumed = try {
            downloadClient.download(candidate.installerUrl, offset) { bytes, count ->
                received += count
                require(received <= candidate.sizeBytes) { "Update download exceeded its declared size" }
                stream.write(bytes, 0, count)
                _state.value = WindowsUpdateStageState.Downloading(candidate.version, received, candidate.sizeBytes)
            }
        } finally {
            stream.fd.sync()
            stream.close()
        }
        if (offset > 0L && !resumed) {
            partial.delete()
            downloadAndHash(candidate, partial, allowResume = false)
        }
    }

    private fun validateCandidate(candidate: WindowsUpdateCandidate): Pair<WindowsUpdateFailureKind, String>? {
        if (!platformName.contains("win", ignoreCase = true)) {
            return WindowsUpdateFailureKind.UNSUPPORTED_PLATFORM to "Automatic installation is currently available only on Windows"
        }
        if (architecture.lowercase() !in setOf("amd64", "x86_64")) {
            return WindowsUpdateFailureKind.INVALID_CANDIDATE to "This update does not match the current Windows architecture"
        }
        if (!isSemanticVersionNewer(installedVersion, candidate.version)) {
            return WindowsUpdateFailureKind.INVALID_CANDIDATE to "The release is not newer than the installed version"
        }
        if (!candidate.installerName.matches(Regex("[A-Za-z0-9._ -]{1,180}\\.msi", RegexOption.IGNORE_CASE))) {
            return WindowsUpdateFailureKind.INVALID_CANDIDATE to "Release does not contain a valid Windows installer name"
        }
        if (candidate.sizeBytes !in 1..MAX_WINDOWS_INSTALLER_BYTES) {
            return WindowsUpdateFailureKind.INVALID_CANDIDATE to "Release installer size is invalid"
        }
        if (
            !isTrustedAresReleaseAssetUrl(candidate.installerUrl, candidate.version, candidate.installerName) ||
            !isTrustedAresReleaseAssetUrl(candidate.checksumUrl, candidate.version, "${candidate.installerName}.sha256") ||
            !isTrustedAresReleasePageUrl(candidate.releasePageUrl, candidate.version)
        ) {
            return WindowsUpdateFailureKind.INVALID_CANDIDATE to "Release assets must belong to the matching immutable ARES GitHub release"
        }
        return null
    }

    private fun fail(kind: WindowsUpdateFailureKind, message: String): StagedWindowsUpdate? {
        _state.value = WindowsUpdateStageState.Failed(kind, message)
        return null
    }
}

data class UpdateActivitySnapshot(
    val recording: Boolean = false,
    val liveRobotControl: Boolean = false,
    val simulatorControl: Boolean = false,
    val importActive: Boolean = false,
    val analysisActive: Boolean = false,
    val externalDeliveryActive: Boolean = false,
    val databaseMigrationActive: Boolean = false,
    val dirtyCriticalState: Boolean = false,
) {
    fun blockers(): List<String> = buildList {
        if (recording) add("telemetry recording is active")
        if (liveRobotControl) add("live robot control is active")
        if (simulatorControl) add("simulator control is active")
        if (importActive) add("a log import is active")
        if (analysisActive) add("analysis is active")
        if (externalDeliveryActive) add("an external delivery is active")
        if (databaseMigrationActive) add("a database migration is active")
        if (dirtyCriticalState) add("critical changes have not been saved")
    }
}

sealed interface WindowsUpdateInstallResult {
    data class Deferred(val blockers: List<String>) : WindowsUpdateInstallResult
    data class HelperLaunched(val resultFile: File) : WindowsUpdateInstallResult
    data class Failed(val safeMessage: String) : WindowsUpdateInstallResult
}

fun interface UpdateHelperLauncher {
    fun launch(command: List<String>, logFile: File): Process
}

class ProcessBuilderUpdateHelperLauncher : UpdateHelperLauncher {
    override fun launch(command: List<String>, logFile: File): Process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        .start()
}

/**
 * Re-verifies a staged update, launches an isolated helper, and only then requests normal Studio shutdown.
 * The helper waits for this JVM to release the single-instance lock before invoking MSI maintenance.
 */
class WindowsUpdateInstaller(
    private val trustedSignerThumbprints: Set<String>,
    private val signatureVerifier: WindowsInstallerSignatureVerifier = PowerShellAuthenticodeVerifier(),
    private val helperLauncher: UpdateHelperLauncher = ProcessBuilderUpdateHelperLauncher(),
    private val helperResource: () -> ByteArray = {
        requireNotNull(WindowsUpdateInstaller::class.java.getResourceAsStream("/update/install-windows-update.ps1")) {
            "Packaged update helper is unavailable"
        }.use { it.readBytes() }
    },
    private val platformName: String = System.getProperty("os.name"),
) {
    private val normalizedTrustedSigners = trustedSignerThumbprints.mapTo(linkedSetOf(), String::normalizeThumbprint)

    suspend fun installAndRestart(
        update: StagedWindowsUpdate,
        activity: UpdateActivitySnapshot,
        relaunchExecutable: File,
        parentPid: Long = ProcessHandle.current().pid(),
        requestShutdown: () -> Unit,
    ): WindowsUpdateInstallResult = withContext(Dispatchers.IO) {
        val blockers = activity.blockers()
        if (blockers.isNotEmpty()) return@withContext WindowsUpdateInstallResult.Deferred(blockers)
        if (!platformName.contains("win", ignoreCase = true)) {
            return@withContext WindowsUpdateInstallResult.Failed("Automatic installation is currently available only on Windows")
        }
        val expectedSigner = update.signerThumbprint?.normalizeThumbprint()
        if (normalizedTrustedSigners.isEmpty()) {
            if (update.trustMode != WindowsInstallerTrustMode.GITHUB_RELEASE_SHA256 || expectedSigner != null) {
                return@withContext WindowsUpdateInstallResult.Failed("The staged update trust policy does not match this build")
            }
        } else if (
            update.trustMode != WindowsInstallerTrustMode.AUTHENTICODE ||
            expectedSigner == null ||
            expectedSigner !in normalizedTrustedSigners
        ) {
            return@withContext WindowsUpdateInstallResult.Failed("The staged update signer is not trusted by this build")
        }
        if (!update.installer.isFile || !relaunchExecutable.isFile || !relaunchExecutable.name.endsWith(".exe", true)) {
            return@withContext WindowsUpdateInstallResult.Failed("The staged installer or installed application is unavailable")
        }
        if (!updateSha256(update.installer).equals(update.sha256, ignoreCase = true)) {
            return@withContext WindowsUpdateInstallResult.Failed("The staged installer changed after verification")
        }
        if (expectedSigner != null) {
            val signature = signatureVerifier.verify(update.installer)
            val actualSigner = signature.thumbprint?.normalizeThumbprint()
            if (!signature.valid || actualSigner != expectedSigner) {
                return@withContext WindowsUpdateInstallResult.Failed("The staged installer signature changed after verification")
            }
        }

        return@withContext try {
            val directory = update.installer.parentFile.canonicalFile
            val temporaryHelper = createTempFile(directory.toPath(), "install-update-", ".ps1.tmp").toFile()
            temporaryHelper.writeBytes(helperResource())
            val helper = File(directory, "install-windows-update.ps1")
            Files.move(
                temporaryHelper.toPath(), helper.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
            val resultFile = File(directory, "install-result.json")
            val logFile = File(directory, "install-helper.log")
            val command = mutableListOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass", "-File", helper.absolutePath,
                "-MsiPath", update.installer.absolutePath,
                "-ExpectedSha256", update.sha256,
            )
            if (expectedSigner != null) {
                command += listOf("-ExpectedSignerThumbprint", expectedSigner)
            }
            command += listOf(
                "-ParentPid", parentPid.toString(),
                "-RelaunchPath", relaunchExecutable.absolutePath,
                "-ResultFile", resultFile.absolutePath,
            )
            helperLauncher.launch(command, logFile)
            requestShutdown()
            WindowsUpdateInstallResult.HelperLaunched(resultFile)
        } catch (failure: Exception) {
            WindowsUpdateInstallResult.Failed(failure.message?.take(1_024) ?: "The update helper could not be started")
        }
    }
}

internal fun parseChecksum(body: String, expectedFilename: String): String {
    val line = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: throw IllegalArgumentException("Checksum response is empty")
    val match = Regex("^([a-fA-F0-9]{64})\\s+[*]?(.+)$").matchEntire(line)
        ?: throw IllegalArgumentException("Checksum response is malformed")
    val namedFile = match.groupValues[2].replace('\\', '/').substringAfterLast('/')
    require(namedFile == expectedFilename) { "Checksum names a different release asset" }
    return match.groupValues[1].lowercase()
}

internal fun updateSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun String.normalizeThumbprint(): String = filter(Char::isLetterOrDigit).uppercase()

private fun isTrustedAresReleaseAssetUrl(value: String, version: String, assetName: String): Boolean = runCatching {
    val uri = URI(value)
    val expectedPath = "/ARES-23247/ARES-Robotics/releases/download/v$version/$assetName"
    uri.scheme.equals("https", true) && uri.userInfo == null && uri.fragment == null && uri.query == null &&
        uri.host.equals("github.com", true) && uri.path == expectedPath
}.getOrDefault(false)

private fun isTrustedAresReleasePageUrl(value: String, version: String): Boolean = runCatching {
    val uri = URI(value)
    val expectedPath = "/ARES-23247/ARES-Robotics/releases/tag/v$version"
    uri.scheme.equals("https", true) && uri.userInfo == null && uri.fragment == null && uri.query == null &&
        uri.host.equals("github.com", true) && uri.path == expectedPath
}.getOrDefault(false)
