package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.areslib.simulation.SimulationProductId
import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.Locale

internal const val ARES_REPOSITORY_URI_PROPERTY = "ares.repository.uri"
internal const val ARES_VERSION_PROPERTY = "ares.version"
internal const val ARES_REPOSITORY_GRADLE_ENVIRONMENT = "ORG_GRADLE_PROJECT_aresRepository"
internal const val ARES_VERSION_GRADLE_ENVIRONMENT = "ORG_GRADLE_PROJECT_aresVersion"
internal const val FTC_ADB_TARGET = "192.168.43.1:5555"

/**
 * Pure command/configuration boundary for robot-project child processes.
 * Process ownership, cancellation, output, and observable state remain in the dedicated build,
 * deployment, and simulator process services.
 */
internal class ProjectProcessCommandFactory(
    aresRepositoryUri: String?,
    aresVersion: String?,
    gradleJavaInstallations: List<File>,
) {
    private val aresRepositoryFileUri = aresRepositoryUri
        ?.takeIf(String::isNotBlank)
        ?.let(::validatedAresRepositoryUri)
    private val aresRepositoryArgument = aresRepositoryFileUri?.let { "-ParesRepository=$it" }
    val explicitAresVersion = aresVersion
        ?.takeIf(String::isNotBlank)
        ?.let(::validatedAresVersion)
    private val aresVersionArgument = explicitAresVersion?.let { "-ParesVersion=$it" }
    private val gradleJavaInstallationsArgument = gradleJavaInstallations
        .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .distinctBy { it.path.lowercase(Locale.ROOT) }
        .takeIf(List<File>::isNotEmpty)
        ?.joinToString(",") { it.path }
        ?.let { "-Porg.gradle.java.installations.paths=$it" }

    fun verificationBuild(league: League, isWindows: Boolean): List<String> = decorateGradle(buildList {
        addGradleWrapper(isWindows)
        when (league) {
            League.FTC -> addAll(
                listOf(
                    "generateAresProject",
                    ":TeamCode:verifyAresProject",
                    ":TeamCode:testDebugUnitTest",
                    ":simulator:test",
                    ":TeamCode:assembleDebug",
                ),
            )
            League.FRC -> addAll(listOf("generateAresProject", "verifyAresProject", "test", "build"))
        }
        addDesktopGradleProcessOptions()
        add("--rerun-tasks")
    })

    fun authoring(task: String, isWindows: Boolean, confirmationToken: String? = null): List<String> =
        decorateGradle(buildList {
            addGradleWrapper(isWindows)
            add(task)
            addDesktopGradleProcessOptions()
            confirmationToken?.let { add("-Pares.subsystemReplacementToken=$it") }
        })

    fun ftcDeployBuild(isWindows: Boolean): List<String> = decorateGradle(buildList {
        addGradleWrapper(isWindows)
        add("generateAresProject")
        add("verifyAresProject")
        add(":TeamCode:testDebugUnitTest")
        add(":simulator:test")
        add(":TeamCode:assembleDebug")
        addDesktopGradleProcessOptions()
    })

    fun frcDeployBuild(isWindows: Boolean): List<String> = decorateGradle(buildList {
        addGradleWrapper(isWindows)
        add("generateAresProject")
        add("verifyAresProject")
        add("test")
        add("build")
        add("deploy")
        addDesktopGradleProcessOptions()
    })

    fun simulation(isWindows: Boolean, product: SimulationProductId): List<String> {
        val command = decorateGradle(buildList {
            addGradleWrapper(isWindows)
            add(
                when (product) {
                    SimulationProductId.FTC_DESKTOP_OPMODE -> ":TeamCode:runSim"
                    SimulationProductId.FRC_WPILIB_DESKTOP -> "simulateJava"
                },
            )
            addDesktopGradleProcessOptions()
        })
        val studioOwnedCommand = if (product == SimulationProductId.FRC_WPILIB_DESKTOP) {
            command + "-ParesFrcHalGui=false"
        } else {
            command
        }
        if (!isWindows || product != SimulationProductId.FRC_WPILIB_DESKTOP) return studioOwnedCommand
        val javaExecutable = ManagedToolchainPaths.resolveFrcSimulationJavaHome()
            ?.let { File(it, "bin/java.exe") }
            ?.takeIf(File::isFile)
            ?: return studioOwnedCommand
        return studioOwnedCommand + "-ParesFrcJavaExecutable=${javaExecutable.path}"
    }

    fun adbConnect(adb: String): List<String> = listOf(adb, "connect", FTC_ADB_TARGET)

    fun adbIdentity(adb: String): List<String> =
        listOf(adb, "-s", FTC_ADB_TARGET, "shell", "getprop", "ro.product.model")

    fun adbInstall(adb: String, apkPath: String): List<String> =
        listOf(adb, "-s", FTC_ADB_TARGET, "install", "-r", "-d", apkPath)

    fun adbPackageCheck(adb: String): List<String> =
        listOf(adb, "-s", FTC_ADB_TARGET, "shell", "pm", "path", FTC_ROBOT_CONTROLLER_PACKAGE)

    fun requireGradleWrapper(root: File, isWindows: Boolean) {
        val wrapperScript = File(root, if (isWindows) "gradlew.bat" else "gradlew").canonicalFile
        require(wrapperScript.isFile && wrapperScript.toPath().startsWith(root.toPath())) {
            "This directory does not contain ${wrapperScript.name}"
        }
        val wrapperJar = File(root, "gradle/wrapper/gradle-wrapper.jar").canonicalFile
        require(wrapperJar.isFile && wrapperJar.toPath().startsWith(root.toPath())) {
            "This directory does not contain gradle/wrapper/gradle-wrapper.jar"
        }
        if (isWindows) return
        normalizeUnixGradleWrapper(wrapperScript)
        check(wrapperScript.setExecutable(true, false) || wrapperScript.canExecute()) {
            "Could not make ${wrapperScript.path} executable"
        }
    }

    fun projectPinnedAresVersion(projectRoot: File): String? =
        listOf("gradle.properties", "gradle/libs.versions.toml")
            .map { File(projectRoot, it) }
            .firstNotNullOfOrNull { file ->
                file.takeIf(File::isFile)?.useLines { lines ->
                    lines.map(String::trim)
                        .firstOrNull { line -> line.startsWith("aresVersion=") || line.startsWith("ares = ") }
                        ?.substringAfter('=')
                        ?.trim()
                        ?.trim('"')
                        ?.takeIf(String::isNotBlank)
                }
            }

    fun requireProjectDependenciesCompatible(projectRoot: File) {
        ProjectDependencyPreflight.inspect(
            projectRoot = projectRoot,
            expectedVersion = explicitAresVersion,
            pinnedVersion = projectPinnedAresVersion(projectRoot),
        ).requireCompatible()
    }

    fun decorateGradle(command: List<String>): List<String> = buildList {
        addAll(command)
        gradleJavaInstallationsArgument?.let(::add)
        aresRepositoryArgument?.let(::add)
        aresVersionArgument?.let(::add)
    }

    fun configureEnvironment(processBuilder: ProcessBuilder): ProcessBuilder = processBuilder.also { builder ->
        ManagedToolchainPaths.configureEnvironment(builder)
        aresRepositoryFileUri?.let { builder.environment()[ARES_REPOSITORY_GRADLE_ENVIRONMENT] = it }
        explicitAresVersion?.let { builder.environment()[ARES_VERSION_GRADLE_ENVIRONMENT] = it }
    }

    fun configuredRepositoryEnvironment(): String? =
        configureEnvironment(ProcessBuilder("ares-environment-test")).environment()[ARES_REPOSITORY_GRADLE_ENVIRONMENT]

    fun configuredVersionEnvironment(): String? =
        configureEnvironment(ProcessBuilder("ares-version-environment-test")).environment()[ARES_VERSION_GRADLE_ENVIRONMENT]

    private fun MutableList<String>.addGradleWrapper(isWindows: Boolean) {
        if (isWindows) addAll(listOf("cmd.exe", "/c", "gradlew.bat")) else add("./gradlew")
    }

    private fun MutableList<String>.addDesktopGradleProcessOptions() {
        add("--no-parallel")
        add("--no-daemon")
        add("--console=plain")
    }

    private fun validatedAresVersion(rawVersion: String): String {
        val version = rawVersion.trim()
        require(ARES_VERSION_PATTERN.matches(version)) {
            "ARES version override must be a semantic version or release candidate without whitespace"
        }
        return version
    }

    private fun validatedAresRepositoryUri(rawUri: String): String {
        val uri = runCatching { URI.create(rawUri) }.getOrElse {
            throw IllegalArgumentException("ARES repository override must be a valid file URI", it)
        }
        require(uri.scheme.equals("file", ignoreCase = true)) {
            "ARES repository override must use a file URI; remote and implicit local repositories are not forwarded"
        }
        val directory = runCatching { Paths.get(uri).toFile().canonicalFile }.getOrElse {
            throw IllegalArgumentException("ARES repository override must identify a local directory", it)
        }
        require(directory.isDirectory) { "ARES repository override directory does not exist: $directory" }
        return directory.toURI().toASCIIString()
    }

    private companion object {
        const val FTC_ROBOT_CONTROLLER_PACKAGE = "com.qualcomm.ftcrobotcontroller"
        val ARES_VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?")
    }
}
