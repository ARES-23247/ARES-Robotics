package com.ares.analytics.service

import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsUpdateServiceTest {
    private val trustedThumbprint = "0123456789ABCDEF0123456789ABCDEF01234567"

    @Test
    fun `stages only a digest-matched installer from its named checksum`() = runBlocking {
        val bytes = "signed-msi-fixture".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val root = createTempDirectory("ares-update-test").toFile()
        try {
            val service = WindowsUpdateService(
                downloadClient = FakeDownloadClient(bytes, "$digest  ARES-Robotics-Studio-99.0.0.msi"),
                trustedSignerThumbprints = setOf(trustedThumbprint),
                signatureVerifier = WindowsInstallerSignatureVerifier {
                    InstallerSignature(valid = true, thumbprint = trustedThumbprint)
                },
                stagingRoot = root,
                platformName = "Windows 11",
                architecture = "amd64",
                installedVersion = "1.0.0",
            )
            val staged = service.stage(candidate(bytes.size.toLong()))

            assertNotNull(staged)
            assertEquals(bytes.toList(), staged.installer.readBytes().toList())
            assertEquals(digest, staged.sha256)
            assertIs<WindowsUpdateStageState.Verified>(service.state.value)
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    @Test
    fun `digest mismatch deletes the executable staging file`() = runBlocking {
        val bytes = "tampered".toByteArray()
        val root = createTempDirectory("ares-update-test").toFile()
        try {
            val service = WindowsUpdateService(
                downloadClient = FakeDownloadClient(bytes, "${"0".repeat(64)}  ARES-Robotics-Studio-99.0.0.msi"),
                trustedSignerThumbprints = setOf(trustedThumbprint),
                signatureVerifier = WindowsInstallerSignatureVerifier { InstallerSignature(true, trustedThumbprint) },
                stagingRoot = root,
                platformName = "Windows",
                architecture = "x86_64",
                installedVersion = "1.0.0",
            )

            assertNull(service.stage(candidate(bytes.size.toLong())))
            assertIs<WindowsUpdateStageState.Failed>(service.state.value).also {
                assertEquals(WindowsUpdateFailureKind.DIGEST_MISMATCH, it.kind)
            }
            assertTrue(root.walkTopDown().none { it.isFile })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing signer policy fails before any network request`() = runBlocking {
        val client = FakeDownloadClient(byteArrayOf(1), "")
        val service = WindowsUpdateService(
            downloadClient = client,
            trustedSignerThumbprints = emptySet(),
            stagingRoot = createTempDirectory("ares-update-test").toFile(),
            platformName = "Windows",
            architecture = "amd64",
            installedVersion = "1.0.0",
        )

        assertNull(service.stage(candidate(1)))
        assertEquals(0, client.requests)
        assertEquals(
            WindowsUpdateFailureKind.SIGNER_POLICY_UNAVAILABLE,
            assertIs<WindowsUpdateStageState.Failed>(service.state.value).kind,
        )
    }

    @Test
    fun `critical activity reports every install blocker`() {
        val blockers = UpdateActivitySnapshot(
            recording = true,
            simulatorControl = true,
            importActive = true,
            externalDeliveryActive = true,
        ).blockers()

        assertEquals(4, blockers.size)
        assertTrue(blockers.any { "recording" in it })
        assertTrue(blockers.any { "simulator" in it })
        assertTrue(blockers.any { "import" in it })
        assertTrue(blockers.any { "delivery" in it })
    }

    @Test
    fun `downgrades fail before network access`() = runBlocking {
        val client = FakeDownloadClient(byteArrayOf(1), "")
        val service = WindowsUpdateService(
            downloadClient = client,
            trustedSignerThumbprints = setOf(trustedThumbprint),
            stagingRoot = createTempDirectory("ares-update-test").toFile(),
            platformName = "Windows",
            architecture = "amd64",
            installedVersion = "100.0.0",
        )

        assertNull(service.stage(candidate(1)))
        assertEquals(0, client.requests)
        assertEquals(
            WindowsUpdateFailureKind.INVALID_CANDIDATE,
            assertIs<WindowsUpdateStageState.Failed>(service.state.value).kind,
        )
    }

    @Test
    fun `checksum parser rejects a digest for a different asset`() {
        val failure = runCatching {
            parseChecksum("${"a".repeat(64)}  other.msi", "expected.msi")
        }.exceptionOrNull()
        assertIs<IllegalArgumentException>(failure)
    }

    @Test
    fun `installer defers before launching helper when critical work is active`() = runBlocking {
        var launches = 0
        var shutdowns = 0
        val installer = WindowsUpdateInstaller(
            trustedSignerThumbprints = setOf(trustedThumbprint),
            signatureVerifier = WindowsInstallerSignatureVerifier { InstallerSignature(true, trustedThumbprint) },
            helperLauncher = UpdateHelperLauncher { _, _ ->
                launches++
                ProcessBuilder("cmd.exe", "/c", "exit", "0").start()
            },
            helperResource = { "Write-Output helper".toByteArray() },
            platformName = "Windows",
        )
        val result = installer.installAndRestart(
            update = StagedWindowsUpdate(
                version = "99.0.0",
                installer = File("missing.msi"),
                sha256 = "0".repeat(64),
                signerThumbprint = trustedThumbprint,
                releasePageUrl = "https://github.com/ARES-23247/ARES-Analytics/releases/tag/v99.0.0",
            ),
            activity = UpdateActivitySnapshot(recording = true),
            relaunchExecutable = File("missing.exe"),
            requestShutdown = { shutdowns++ },
        )

        assertIs<WindowsUpdateInstallResult.Deferred>(result)
        assertEquals(0, launches)
        assertEquals(0, shutdowns)
    }

    private fun candidate(size: Long) = WindowsUpdateCandidate(
        version = "99.0.0",
        installerName = "ARES-Robotics-Studio-99.0.0.msi",
        installerUrl = "https://github.com/ARES-23247/ARES-Analytics/releases/download/v99.0.0/app.msi",
        checksumUrl = "https://github.com/ARES-23247/ARES-Analytics/releases/download/v99.0.0/app.msi.sha256",
        sizeBytes = size,
        releasePageUrl = "https://github.com/ARES-23247/ARES-Analytics/releases/tag/v99.0.0",
        releaseNotes = null,
    )

    private class FakeDownloadClient(
        private val bytes: ByteArray,
        private val checksum: String,
    ) : WindowsUpdateDownloadClient {
        var requests: Int = 0

        override suspend fun download(url: String, offset: Long, consume: suspend (ByteArray, Int) -> Unit): Boolean {
            requests++
            val remaining = bytes.copyOfRange(offset.toInt(), bytes.size)
            consume(remaining, remaining.size)
            return offset > 0L
        }

        override suspend fun readChecksum(url: String): String {
            requests++
            return checksum
        }
    }
}
