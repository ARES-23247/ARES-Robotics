package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManagedToolchainServiceTest {
    @Test
    fun `FTC build readiness does not require an unused local NDK`() {
        val sdk = Files.createTempDirectory("ares-ftc-sdk-readiness").toFile()
        try {
            File(sdk, "platforms/android-30").mkdirs()
            File(sdk, "platform-tools").mkdirs()

            assertEquals(emptyList(), missingFtcAndroidComponents(sdk))
        } finally {
            sdk.deleteRecursively()
        }
    }

    @Test
    fun `FTC build readiness still requires the platform and deployment tools`() {
        val sdk = Files.createTempDirectory("ares-ftc-sdk-incomplete").toFile()
        try {
            assertEquals(
                listOf("Android platform 30", "platform tools"),
                missingFtcAndroidComponents(sdk),
            )
        } finally {
            sdk.deleteRecursively()
        }
    }

    @Test
    fun `managed JDK redirects remain on reviewed HTTPS hosts`() {
        assertTrue(ManagedToolchainService.isAllowedJdkDownloadUri(URI("https://release-assets.githubusercontent.com/file.zip")))
        assertTrue(!ManagedToolchainService.isAllowedJdkDownloadUri(URI("https://downloads.example.net/file.zip")))
        assertTrue(!ManagedToolchainService.isAllowedJdkDownloadUri(URI("http://github.com/file.zip")))
    }

    @Test
    fun `verified managed JDK installs privately and configures child processes`() = runBlocking {
        val root = Files.createTempDirectory("ares-managed-jdk-test").toFile()
        val oldRoot = System.getProperty("ares.toolchains.root")
        try {
            System.setProperty("ares.toolchains.root", root.path)
            val archive = fakeJdkArchive()
            val checksum = sha256(archive)
            val service = ManagedToolchainService(
                rootDirectory = root,
                packageResolver = {
                    JdkPackage(
                        name = "OpenJDK21U-jdk_x64_windows_hotspot_test.zip",
                        link = "https://github.com/adoptium/temurin21-binaries/releases/download/test/jdk.zip",
                        checksum = checksum,
                    )
                },
                packageDownloader = { _, destination, progress ->
                    destination.writeBytes(archive)
                    progress(archive.size.toLong(), archive.size.toLong())
                },
                jdkVerifier = { javaHome ->
                    assertTrue(File(javaHome, "bin/java.exe").isFile)
                    assertTrue(File(javaHome, "bin/javac.exe").isFile)
                },
                managedInstallationSupported = { true },
            )

            val snapshot = service.installManagedJdk21(League.FTC)

            val java = assertNotNull(ManagedToolchainPaths.javaExecutable())
            assertTrue(java.path.contains("temurin-21-${checksum.take(12)}"))
            assertEquals(ToolchainReadiness.READY, snapshot.components.first().readiness)
            val builder = ManagedToolchainPaths.configureEnvironment(ProcessBuilder("fixture"))
            assertEquals(java.parentFile.parentFile.path, builder.environment()["JAVA_HOME"])
            assertTrue(
                ManagedToolchainPaths.gradleJavaInstallations().any { it.canonicalFile == java.parentFile.parentFile.canonicalFile },
                "The managed JDK must also be advertised to nested Gradle toolchain discovery",
            )
            assertTrue(service.installState.value is ManagedToolchainInstallState.Succeeded)
        } finally {
            if (oldRoot == null) System.clearProperty("ares.toolchains.root") else System.setProperty("ares.toolchains.root", oldRoot)
            root.deleteRecursively()
        }
    }

    @Test
    fun `explicit simulation JDK becomes first on path even when it was already later`() {
        val root = Files.createTempDirectory("ares-java-environment-test").toFile()
        val bin = File(root, "bin").apply { mkdirs() }
        val executableSuffix = if (System.getProperty("os.name").contains("win", ignoreCase = true)) ".exe" else ""
        File(bin, "java$executableSuffix").writeText("java")
        File(bin, "javac$executableSuffix").writeText("javac")
        File(root, "release").writeText("JAVA_VERSION=\"17.0.16\"")
        val other = Files.createTempDirectory("ares-other-java-bin").toFile()
        try {
            val builder = ProcessBuilder("fixture")
            builder.environment()["PATH"] = listOf(other.path, bin.path).joinToString(File.pathSeparator)

            ManagedToolchainPaths.configureJavaEnvironment(builder, root)

            assertEquals(root.path, builder.environment()["JAVA_HOME"])
            assertEquals(bin.canonicalPath, builder.environment()["PATH"]?.split(File.pathSeparator)?.first()?.let(::File)?.canonicalPath)
            assertEquals(1, builder.environment()["PATH"]?.split(File.pathSeparator)?.count { File(it).canonicalFile == bin.canonicalFile })
        } finally {
            root.deleteRecursively()
            other.deleteRecursively()
        }
    }

    @Test
    fun `checksum mismatch installs nothing and reports failure`() = runBlocking {
        val root = Files.createTempDirectory("ares-managed-jdk-hash-test").toFile()
        try {
            val archive = fakeJdkArchive()
            val service = ManagedToolchainService(
                rootDirectory = root,
                packageResolver = {
                    JdkPackage("fixture.zip", "https://github.com/example/fixture.zip", "0".repeat(64))
                },
                packageDownloader = { _, destination, _ -> destination.writeBytes(archive) },
                jdkVerifier = { error("must not verify") },
                managedInstallationSupported = { true },
            )

            runCatching { service.installManagedJdk21(League.FTC) }

            assertTrue(service.installState.value is ManagedToolchainInstallState.Failed)
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("temurin-21-") })
            assertTrue(!File(root, ManagedToolchainService.ACTIVE_JDK_MARKER).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `managed JDK extraction rejects entries outside its private staging directory`() {
        val parent = Files.createTempDirectory("ares-managed-jdk-zip-slip").toFile()
        val archive = File(parent, "hostile.zip")
        val destination = File(parent, "staging")
        val escaped = File(parent, "escaped.txt")
        try {
            archive.writeBytes(
                zipArchive(
                    "../escaped.txt" to "must not be written",
                    "jdk-21-test/bin/java.exe" to "java",
                ),
            )

            assertFailsWith<SecurityException> {
                extractZipSafely(archive, destination)
            }
            assertTrue(!escaped.exists(), "A hostile ZIP entry must never escape the staging directory")
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun fakeJdkArchive(): ByteArray = zipArchive(
        "jdk-21-test/bin/java.exe" to "java",
        "jdk-21-test/bin/javac.exe" to "javac",
        "jdk-21-test/bin/java" to "java",
        "jdk-21-test/bin/javac" to "javac",
        "jdk-21-test/release" to "JAVA_VERSION=\"21\"",
    )

    private fun zipArchive(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
