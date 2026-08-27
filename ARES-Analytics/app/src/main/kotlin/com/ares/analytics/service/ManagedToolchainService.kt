package com.ares.analytics.service

import com.ares.analytics.shared.League
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

enum class ToolchainReadiness { READY, OPTIONAL_DOWNLOAD, MANUAL_SETUP_REQUIRED }

data class RobotToolchainComponent(
    val name: String,
    val readiness: ToolchainReadiness,
    val detail: String,
    val location: String? = null,
)

data class RobotToolchainSnapshot(
    val league: League = League.FTC,
    val components: List<RobotToolchainComponent> = emptyList(),
) {
    val buildReady: Boolean get() = components.all { it.readiness == ToolchainReadiness.READY }
}

sealed class ManagedToolchainInstallState {
    object Idle : ManagedToolchainInstallState()
    data class Working(val message: String, val fraction: Float? = null) : ManagedToolchainInstallState()
    data class Succeeded(val message: String) : ManagedToolchainInstallState()
    data class Failed(val message: String) : ManagedToolchainInstallState()
}

internal data class JdkPackage(
    val name: String,
    val link: String,
    val checksum: String,
)

/**
 * Resolves app-managed robot build tools without changing machine-wide environment variables.
 *
 * The desktop runtime is already included in the installer. This service installs only the full
 * JDK required by Gradle robot builds. FTC Android/NDK and FRC WPILib readiness are reported
 * separately because their vendor licenses, season versions, and disk footprints must remain
 * visible to the user rather than being silently accepted or partially installed.
 */
class ManagedToolchainService internal constructor(
    private val rootDirectory: File = ManagedToolchainPaths.rootDirectory(),
    private val packageResolver: () -> JdkPackage = ::resolveLatestTemurin21,
    private val packageDownloader: (JdkPackage, File, (Long, Long?) -> Unit) -> Unit = ::downloadJdkPackage,
    private val jdkVerifier: (File) -> Unit = ::verifyInstalledJdk,
    private val managedInstallationSupported: () -> Boolean = ManagedToolchainPaths::managedJdkInstallationSupported,
) {
    private val installMutex = Mutex()
    private val _installState = MutableStateFlow<ManagedToolchainInstallState>(ManagedToolchainInstallState.Idle)
    val installState: StateFlow<ManagedToolchainInstallState> = _installState.asStateFlow()
    private val _snapshot = MutableStateFlow(RobotToolchainSnapshot())
    val snapshot: StateFlow<RobotToolchainSnapshot> = _snapshot.asStateFlow()

    suspend fun refresh(league: League): RobotToolchainSnapshot = withContext(Dispatchers.IO) {
        val jdk = ManagedToolchainPaths.resolveJavaHome()
        val javaComponent = if (jdk != null) {
            RobotToolchainComponent(
                name = "Java Development Kit 17 or 21",
                readiness = ToolchainReadiness.READY,
                detail = if (ManagedToolchainPaths.isManagedJavaHome(jdk)) {
                    "ARES-managed JDK is ready for Gradle builds and simulation."
                } else {
                    "A compatible system JDK is available. ARES will use it for robot builds."
                },
                location = jdk.path,
            )
        } else {
            RobotToolchainComponent(
                name = "Java Development Kit 17 or 21",
                readiness = if (ManagedToolchainPaths.managedJdkInstallationSupported()) {
                    ToolchainReadiness.OPTIONAL_DOWNLOAD
                } else {
                    ToolchainReadiness.MANUAL_SETUP_REQUIRED
                },
                detail = if (ManagedToolchainPaths.managedJdkInstallationSupported()) {
                    "Install a private, verified Eclipse Temurin JDK for this Windows account."
                } else {
                    "Install JDK 21. ARES automatically discovers macOS Java bundles and Homebrew installations; JAVA_HOME is optional."
                },
            )
        }

        val platformComponent = when (league) {
            League.FTC -> {
                val sdk = ManagedToolchainPaths.resolveAndroidSdk()
                val missing = sdk?.let(::missingFtcAndroidComponents).orEmpty()
                if (sdk != null && missing.isEmpty()) {
                    RobotToolchainComponent(
                        name = "FTC Android build tools",
                        readiness = ToolchainReadiness.READY,
                        detail = "Android platform 30 and platform tools are available. FTC projects package reviewed native libraries and do not require a local NDK to build.",
                        location = sdk.path,
                    )
                } else {
                    RobotToolchainComponent(
                        name = "FTC Android build tools",
                        readiness = ToolchainReadiness.MANUAL_SETUP_REQUIRED,
                        detail = if (sdk == null) {
                            "Install Android Studio or the Android command-line SDK before building an FTC APK."
                        } else {
                            "Android SDK found, but these reviewed FTC components are missing: ${missing.joinToString()}."
                        },
                        location = sdk?.path,
                    )
                }
            }
            League.FRC -> {
                val wpilib = ManagedToolchainPaths.resolveWpilibHome()
                if (wpilib != null) {
                    RobotToolchainComponent(
                        name = "FRC WPILib 2026",
                        readiness = ToolchainReadiness.READY,
                        detail = "WPILib and the roboRIO development toolchain are available.",
                        location = wpilib.path,
                    )
                } else {
                    RobotToolchainComponent(
                        name = "FRC WPILib 2026",
                        readiness = ToolchainReadiness.MANUAL_SETUP_REQUIRED,
                        detail = "Install the official WPILib 2026 release before building or deploying an FRC robot.",
                    )
                }
            }
        }
        RobotToolchainSnapshot(league, listOf(javaComponent, platformComponent)).also { _snapshot.value = it }
    }

    suspend fun installManagedJdk21(league: League): RobotToolchainSnapshot = installMutex.withLock {
        withContext(Dispatchers.IO) {
            require(managedInstallationSupported()) {
                "Managed JDK installation is currently supported on Windows x64. Install JDK 21 manually on this platform."
            }
            rootDirectory.mkdirs()
            require(rootDirectory.isDirectory) { "ARES could not create its private toolchain directory." }
            val staging = File(rootDirectory, ".jdk-install-${UUID.randomUUID()}")
            val archive = File(rootDirectory, ".jdk-download-${UUID.randomUUID()}.zip")
            try {
                _installState.value = ManagedToolchainInstallState.Working("Finding the current Eclipse Temurin 21 package…")
                val pkg = packageResolver()
                validateJdkPackage(pkg)
                _installState.value = ManagedToolchainInstallState.Working("Downloading ${pkg.name}…", 0f)
                packageDownloader(pkg, archive) { received, total ->
                    _installState.value = ManagedToolchainInstallState.Working(
                        message = "Downloading the JDK for robot builds…",
                        fraction = total?.takeIf { it > 0 }?.let { (received.toDouble() / it).coerceIn(0.0, 1.0).toFloat() },
                    )
                }
                val actualHash = toolchainSha256(archive)
                check(actualHash.equals(pkg.checksum, ignoreCase = true)) {
                    "The JDK download did not match Eclipse Adoptium's SHA-256 metadata. Nothing was installed."
                }
                _installState.value = ManagedToolchainInstallState.Working("Verifying and unpacking the JDK…")
                extractZipSafely(archive, staging)
                val javaHome = findJavaHome(staging)
                jdkVerifier(javaHome)
                val installationName = "temurin-21-${actualHash.take(12)}"
                val destination = File(rootDirectory, installationName)
                if (!destination.exists()) {
                    Files.move(javaHome.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
                writeFileAtomically(File(rootDirectory, ACTIVE_JDK_MARKER)) { temporary ->
                    temporary.writeText(installationName + System.lineSeparator())
                }
                _installState.value = ManagedToolchainInstallState.Succeeded(
                    "JDK 21 is installed for ARES robot builds. No system-wide Java settings were changed.",
                )
            } catch (failure: Exception) {
                _installState.value = ManagedToolchainInstallState.Failed(
                    failure.message ?: "The managed JDK could not be installed.",
                )
                throw failure
            } finally {
                archive.delete()
                if (staging.exists()) staging.deleteRecursively()
            }
            refresh(league)
        }
    }

    fun clearInstallMessage() {
        _installState.value = ManagedToolchainInstallState.Idle
    }

    private fun validateJdkPackage(pkg: JdkPackage) {
        require(pkg.name.endsWith(".zip", ignoreCase = true)) { "The managed Windows JDK must be a ZIP archive." }
        require(pkg.checksum.matches(Regex("[0-9a-fA-F]{64}"))) { "Adoptium returned an invalid JDK checksum." }
        val uri = URI.create(pkg.link)
        require(uri.scheme.equals("https", ignoreCase = true)) { "The JDK package must use HTTPS." }
        require(uri.host?.lowercase(Locale.ROOT) in ALLOWED_JDK_DOWNLOAD_HOSTS) {
            "Adoptium returned an unexpected JDK download host. Nothing was installed."
        }
    }

    private fun findJavaHome(staging: File): File {
        val candidates = staging.walkTopDown().maxDepth(3)
            .filter(File::isDirectory)
            .filter { File(it, "bin/java.exe").isFile && File(it, "bin/javac.exe").isFile }
            .toList()
        require(candidates.size == 1) { "The downloaded archive did not contain exactly one complete Windows JDK." }
        return candidates.single()
    }

    companion object {
        const val ACTIVE_JDK_MARKER = "active-jdk.txt"
        const val MAX_JDK_ARCHIVE_BYTES = 350L * 1024L * 1024L
        const val MAX_JDK_EXTRACTED_BYTES = 900L * 1024L * 1024L
        const val MAX_JDK_ENTRIES = 40_000
        private val ALLOWED_JDK_DOWNLOAD_HOSTS = setOf(
            "api.adoptium.net",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )

        internal fun isAllowedJdkDownloadUri(uri: URI): Boolean =
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.lowercase(Locale.ROOT) in ALLOWED_JDK_DOWNLOAD_HOSTS
    }
}

/** Shared child-process environment used by builds, generation, simulation, and deployment. */
object ManagedToolchainPaths {
    const val JDK_21_DOWNLOAD_URL = "https://adoptium.net/temurin/releases/?version=21&package=jdk"

    fun rootDirectory(): File = System.getProperty("ares.toolchains.root")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?: AppDataPaths.file("toolchains")

    fun managedJdkInstallationSupported(): Boolean =
        System.getProperty("os.name").contains("win", ignoreCase = true) &&
            (System.getProperty("os.arch").contains("64") || System.getProperty("os.arch").equals("amd64", true))

    fun resolveJavaHome(): File? {
        val managed = activeManagedJavaHome()
        if (managed != null) return managed
        val configured = System.getenv("JAVA_HOME")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(::isCompleteJdk)
        if (configured != null) return configured

        // Apps started by Finder/Launch Services do not inherit variables exported by a login
        // shell. Check the standard macOS bundles and Homebrew's stable opt symlinks so users do
        // not have to launch the app from Terminal just to make a supported JDK visible.
        return systemJavaHomes()
            .filter(::isCompleteJdk)
            .sortedByDescending { jdkMajorVersion(it) }
            .firstOrNull()
    }

    fun javaExecutable(): File? = resolveJavaHome()?.let { home ->
        File(home, "bin/${if (isWindows()) "java.exe" else "java"}").takeIf(File::isFile)
    }

    /**
     * WPILib's Windows JNI runtime is tested with the JDK shipped by the matching WPILib season.
     * An otherwise valid system JDK can carry an older `msvcp140.dll` beside `java.exe`; Windows
     * loads that copy before the current system redistributable and WPILib then refuses to start.
     */
    internal fun resolveFrcSimulationJavaHome(): File? =
        resolveWpilibHome()
            ?.let { File(it, "jdk") }
            ?.takeIf(::isCompleteJdk)
            ?: resolveJavaHome()

    /**
     * Returns every supported JDK that Gradle children should be allowed to use as a toolchain.
     *
     * Gradle's normal Windows discovery only sees JDKs registered by their installer. Oracle ZIP
     * installs and WPILib's bundled JDK are common on student laptops but are not necessarily in
     * that registry. ARES runs student builds with an isolated home, so relying on a developer's
     * cached Gradle toolchain locations would also make clean installs fail unexpectedly.
     */
    fun gradleJavaInstallations(): List<File> {
        val directCandidates = buildList {
            resolveJavaHome()?.let(::add)
            System.getProperty("java.home")?.takeIf(String::isNotBlank)?.let(::File)?.let(::add)
            resolveWpilibHome()?.let { add(File(it, "jdk")) }
        }
        val installationRoots = buildList {
            add(rootDirectory())
            if (isWindows()) {
                add(File("C:/Program Files/Java"))
                add(File("C:/Program Files/Eclipse Adoptium"))
                add(File("C:/Program Files/Microsoft"))
            } else {
                add(File("/usr/lib/jvm"))
                add(File("/Library/Java/JavaVirtualMachines"))
                add(File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"))
            }
        }
        return (directCandidates + systemJavaHomes() + installationRoots.flatMap { root -> root.listFiles().orEmpty().toList() })
            .mapNotNull { candidate -> runCatching { candidate.canonicalFile }.getOrNull() }
            .filter(::isCompleteJdk)
            .distinctBy { it.path.lowercase(Locale.ROOT) }
    }

    fun isManagedJavaHome(file: File): Boolean = runCatching {
        file.canonicalFile.toPath().startsWith(rootDirectory().canonicalFile.toPath())
    }.getOrDefault(false)

    fun resolveAndroidSdk(): File? {
        val managed = File(rootDirectory(), "android-sdk").takeIf(File::isDirectory)
        if (managed != null) return managed
        val candidates = listOfNotNull(
            System.getenv("ANDROID_HOME")?.let(::File),
            System.getenv("ANDROID_SDK_ROOT")?.let(::File),
            System.getenv("LOCALAPPDATA")?.let { File(it, "Android/Sdk") },
            File(System.getProperty("user.home"), "Android/Sdk"),
            File(System.getProperty("user.home"), "Library/Android/sdk"),
        )
        return candidates.firstOrNull(File::isDirectory)
    }

    fun resolveWpilibHome(): File? {
        val candidates = if (isWindows()) {
            listOf(File("C:/Users/Public/wpilib/2026"), File(System.getProperty("user.home"), "wpilib/2026"))
        } else {
            listOf(File(System.getProperty("user.home"), "wpilib/2026"))
        }
        return candidates.firstOrNull { it.isDirectory && File(it, "jdk").isDirectory }
    }

    fun configureEnvironment(builder: ProcessBuilder): ProcessBuilder = builder.also {
        val env = it.environment()
        resolveJavaHome()?.let { javaHome -> configureJavaEnvironment(it, javaHome) }
        resolveAndroidSdk()?.let { sdk ->
            env["ANDROID_HOME"] = sdk.path
            env["ANDROID_SDK_ROOT"] = sdk.path
            prependPath(env, File(sdk, "platform-tools"))
        }
    }

    internal fun configureJavaEnvironment(builder: ProcessBuilder, javaHome: File): ProcessBuilder = builder.also {
        require(isCompleteJdk(javaHome)) { "Java home is not a complete supported JDK: ${javaHome.path}" }
        it.environment()["JAVA_HOME"] = javaHome.path
        prependPath(it.environment(), File(javaHome, "bin"), moveToFront = true)
    }

    private fun activeManagedJavaHome(): File? {
        val root = rootDirectory()
        val marker = File(root, ManagedToolchainService.ACTIVE_JDK_MARKER).takeIf(File::isFile) ?: return null
        val name = marker.readText().trim()
        if (!name.matches(Regex("[A-Za-z0-9._-]{1,96}"))) return null
        val candidate = File(root, name)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (canonical.parentFile != runCatching { root.canonicalFile }.getOrNull()) return null
        return canonical.takeIf(::isCompleteJdk)
    }

    private fun isCompleteJdk(home: File): Boolean {
        val javaName = if (isWindows()) "java.exe" else "java"
        val javacName = if (isWindows()) "javac.exe" else "javac"
        return File(home, "bin/$javaName").isFile &&
            File(home, "bin/$javacName").isFile &&
            jdkMajorVersion(home) in setOf(17, 21)
    }

    private fun systemJavaHomes(): List<File> {
        if (isWindows()) return emptyList()
        val registeredMacHomes = if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
            listOf(21, 17).mapNotNull { version ->
                runCatching {
                    ProcessBuilder("/usr/libexec/java_home", "-v", version.toString())
                        .redirectErrorStream(true)
                        .start()
                        .let { process ->
                            val output = process.inputStream.bufferedReader().readText().trim()
                            if (process.waitFor() == 0 && output.isNotBlank()) File(output.lineSequence().last()) else null
                        }
                }.getOrNull()
            }
        } else {
            emptyList()
        }
        val macBundleRoots = listOf(
            File("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"),
            File("/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"),
            File("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"),
            File("/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"),
            File("/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"),
            File("/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home"),
        )
        val installedBundles = listOf(
            File("/Library/Java/JavaVirtualMachines"),
            File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"),
        ).flatMap { root -> root.listFiles().orEmpty().map { File(it, "Contents/Home") } }
        return (registeredMacHomes + macBundleRoots + installedBundles).distinctBy { it.absolutePath }
    }

    private fun jdkMajorVersion(home: File): Int? {
        val release = File(home, "release").takeIf(File::isFile)?.readText() ?: return null
        val value = Regex("(?m)^JAVA_VERSION=\"([^\"]+)\"").find(release)?.groupValues?.get(1) ?: return null
        val first = value.substringBefore('.')
        return if (first == "1") value.substringAfter('.').substringBefore('.').toIntOrNull() else first.toIntOrNull()
    }

    private fun prependPath(
        environment: MutableMap<String, String>,
        directory: File,
        moveToFront: Boolean = false,
    ) {
        if (!directory.isDirectory) return
        val key = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val current = environment[key].orEmpty()
        val entries = current.split(File.pathSeparatorChar).filter(String::isNotBlank)
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrDefault(directory.absoluteFile)
        val matching = { entry: String ->
            runCatching { File(entry).canonicalFile == canonicalDirectory }.getOrDefault(false)
        }
        if (!moveToFront && entries.any(matching)) return
        val remainder = entries.filterNot(matching)
        environment[key] = (listOf(directory.path) + remainder).joinToString(File.pathSeparator)
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").contains("win", ignoreCase = true)
}

internal fun missingFtcAndroidComponents(sdk: File): List<String> = buildList {
    if (!File(sdk, "platforms/android-30").isDirectory) add("Android platform 30")
    if (!File(sdk, "platform-tools").isDirectory) add("platform tools")
}

private fun resolveLatestTemurin21(): JdkPackage {
    val connection = URL(
        "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=windows&project=jdk&vendor=eclipse",
    ).openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("User-Agent", "ARES-Analytics toolchain manager")
    try {
        check(connection.responseCode in 200..299) { "Eclipse Adoptium metadata returned HTTP ${connection.responseCode}." }
        val root = JsonParser.parseReader(connection.inputStream.bufferedReader()).asJsonArray
        val binary = root.firstOrNull()?.asJsonObject?.getAsJsonObject("binary")
            ?: error("Eclipse Adoptium did not return a Windows JDK 21 package.")
        val pkg = binary.getAsJsonObject("package") ?: error("Adoptium package metadata is incomplete.")
        return JdkPackage(
            name = pkg.get("name")?.asString ?: error("Adoptium package name is missing."),
            link = pkg.get("link")?.asString ?: error("Adoptium package link is missing."),
            checksum = pkg.get("checksum")?.asString ?: error("Adoptium package checksum is missing."),
        )
    } finally {
        connection.disconnect()
    }
}

private fun downloadJdkPackage(pkg: JdkPackage, destination: File, progress: (Long, Long?) -> Unit) {
    val connection = URL(pkg.link).openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 20_000
    connection.readTimeout = 120_000
    connection.setRequestProperty("User-Agent", "ARES-Analytics toolchain manager")
    try {
        check(connection.responseCode in 200..299) { "The JDK download returned HTTP ${connection.responseCode}." }
        check(ManagedToolchainService.isAllowedJdkDownloadUri(connection.url.toURI())) {
            "The JDK download redirected to an unexpected host. Nothing was installed."
        }
        val expected = connection.contentLengthLong.takeIf { it >= 0 }
        check(expected == null || expected <= ManagedToolchainService.MAX_JDK_ARCHIVE_BYTES) { "The JDK archive is unexpectedly large." }
        BufferedInputStream(connection.inputStream).use { input ->
            BufferedOutputStream(FileOutputStream(destination)).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= ManagedToolchainService.MAX_JDK_ARCHIVE_BYTES) { "The JDK archive exceeded the safe download limit." }
                    output.write(buffer, 0, read)
                    progress(total, expected)
                }
            }
        }
    } finally {
        connection.disconnect()
    }
}

private fun verifyInstalledJdk(javaHome: File) {
    val process = ProcessBuilder(File(javaHome, "bin/java.exe").path, "-version")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0 && output.contains("21")) { "The downloaded JDK did not report Java 21." }
}

internal fun extractZipSafely(archive: File, destination: File) {
    check(destination.mkdirs()) { "Could not create the private JDK staging directory." }
    val root = destination.toPath().toAbsolutePath().normalize()
    var entries = 0
    var total = 0L
    ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            entries++
            check(entries <= ManagedToolchainService.MAX_JDK_ENTRIES) { "The JDK archive contains too many files." }
            val entryPath = root.fileSystem.getPath(entry.name)
            if (entryPath.isAbsolute) {
                throw SecurityException("The JDK archive contains an absolute path.")
            }
            val output = root.resolve(entryPath).normalize()
            if (!output.startsWith(root)) {
                throw SecurityException("The JDK archive attempted to write outside its staging directory.")
            }
            if (entry.isDirectory) {
                Files.createDirectories(output)
            } else {
                Files.createDirectories(output.parent)
                BufferedOutputStream(Files.newOutputStream(output)).use { target ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= ManagedToolchainService.MAX_JDK_EXTRACTED_BYTES) { "The JDK archive expanded beyond the safe limit." }
                        target.write(buffer, 0, read)
                    }
                }
            }
            zip.closeEntry()
        }
    }
}

private fun toolchainSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
