package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.areslib.simulation.SimulationProductId
import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.Locale
import java.util.Properties

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

    fun verificationBuild(league: League, isWindows: Boolean): List<String> {
        if (league == League.XRP) return xrpProjectCommand(isWindows, "build")
        return decorateGradle(buildList {
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
            League.XRP -> error("XRP uses its Python-native project wrapper")
        }
        addDesktopGradleProcessOptions()
        add("--rerun-tasks")
        })
    }

    fun authoring(league: League, task: String, isWindows: Boolean, confirmationToken: String? = null): List<String> =
        if (league == League.XRP) {
            xrpProjectCommand(isWindows, "generate")
        } else {
        decorateGradle(buildList {
            addGradleWrapper(isWindows)
            add(task)
            addDesktopGradleProcessOptions()
            confirmationToken?.let { add("-Pares.subsystemReplacementToken=$it") }
        })
        }

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

    fun xrpDeployBuild(isWindows: Boolean): List<String> = xrpProjectCommand(isWindows, "deploy")

    fun simulation(isWindows: Boolean, product: SimulationProductId): List<String> {
        if (product == SimulationProductId.XRP_DESKTOP) return xrpProjectCommand(isWindows, "simulate")
        val command = decorateGradle(buildList {
            addGradleWrapper(isWindows)
            add(
                when (product) {
                    SimulationProductId.FTC_DESKTOP_OPMODE -> ":TeamCode:runSim"
                    SimulationProductId.FRC_WPILIB_DESKTOP -> "simulateJava"
                    SimulationProductId.XRP_DESKTOP -> error("XRP uses its Python-native project wrapper")
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

    fun requireProjectWrapper(root: File, league: League, isWindows: Boolean) {
        if (league != League.XRP) {
            requireGradleWrapper(root, isWindows)
            return
        }
        val wrapper = File(root, if (isWindows) "ares.bat" else "ares").canonicalFile
        require(wrapper.isFile && wrapper.toPath().startsWith(root.toPath())) {
            "This XRP project does not contain its native ${wrapper.name} wrapper"
        }
        val tool = File(root, "tools/ares_project.py").canonicalFile
        require(tool.isFile && tool.toPath().startsWith(root.toPath())) {
            "This XRP project does not contain tools/ares_project.py"
        }
        if (!isWindows) {
            check(wrapper.setExecutable(true, false) || wrapper.canExecute()) {
                "Could not make ${wrapper.path} executable"
            }
        }
    }

    fun projectPinnedAresVersion(projectRoot: File): String? =
        File(projectRoot, "release/ares-versions.properties")
            .takeIf(File::isFile)
            ?.inputStream()
            ?.use { input ->
                Properties().apply { load(input) }
                    .getProperty("aresVersion")
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            }

    fun requireProjectDependenciesCompatible(projectRoot: File) {
        ProjectDependencyPreflight.inspect(
            projectRoot = projectRoot,
            expectedVersion = explicitAresVersion,
            pinnedVersion = projectPinnedAresVersion(projectRoot),
            isolatedRepositoryConfigured = aresRepositoryFileUri != null,
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

    private fun xrpProjectCommand(isWindows: Boolean, task: String): List<String> =
        if (isWindows) listOf("cmd.exe", "/d", "/s", "/c", "ares.bat", task)
        else listOf("./ares", task)

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
