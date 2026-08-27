package com.ares.analytics.service

import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider
import com.ares.analytics.shared.League
import com.areslib.codegen.SubsystemKotlinCodegenTarget
import com.areslib.codegen.SubsystemKotlinGenerator
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.simulation.SimulationProductId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessManagerServiceTest {
    @Test
    fun `unix Gradle wrapper normalization removes CRLF from Windows-authored starters`() {
        val root = Files.createTempDirectory("ares-gradlew-crlf-test").toFile()
        try {
            val wrapper = File(root, "gradlew").apply {
                writeBytes("#!/usr/bin/env bash\r\necho ready\r\n".toByteArray())
            }

            normalizeUnixGradleWrapper(wrapper)

            assertEquals("#!/usr/bin/env bash\necho ready\n", wrapper.readText())
        } finally {
            root.deleteRecursively()
        }
    }


    @Test
    fun `only the known transient Gradle cache handoff is eligible for one automatic retry`() {
        assertTrue(
            isTransientGradleCacheMoveFailure(
                "Could not move temporary workspace C:/cache/transforms/tmp to immutable location C:/cache/transforms/final",
            ),
        )
        assertFalse(isTransientGradleCacheMoveFailure("Compilation error: unresolved reference Elevator"))
        assertFalse(isTransientGradleCacheMoveFailure("Could not resolve org.aresfirst.ares:core:9.11.0"))
    }

    @Test
    fun `explicit isolated repository file URI decorates every nested Gradle command`() {
        val repository = Files.createTempDirectory("ares-release-repository").toFile()
        val service = ProcessManagerService(
            monitorAdbConnection = false,
            aresRepositoryUri = repository.toURI().toASCIIString(),
            aresVersion = "9.6.0-rc.guided1",
        )
        try {
            val expectedRepository = "-ParesRepository=${repository.canonicalFile.toURI().toASCIIString()}"
            val expectedVersion = "-ParesVersion=9.6.0-rc.guided1"
            assertEquals(
                repository.canonicalFile.toURI().toASCIIString(),
                service.configuredAresRepositoryEnvironmentForTest(),
                "Arbitrary child simulator commands must inherit the equivalent Gradle project property without shell mutation",
            )
            assertEquals("9.6.0-rc.guided1", service.configuredAresVersionEnvironmentForTest())
            val representativeCommands = listOf(
                listOf("gradlew.bat", ":TeamCode:assembleDebug"),
                listOf("java", "org.gradle.wrapper.GradleWrapperMain", "generateAresProject"),
                listOf("./gradlew", "simulateJava"),
            )

            representativeCommands.forEach { base ->
                val configured = service.configuredGradleCommandForTest(base)
                assertEquals(base, configured.take(base.size))
                assertTrue(configured.any { it.startsWith("-Porg.gradle.java.installations.paths=") })
                assertEquals(listOf(expectedRepository, expectedVersion), configured.takeLast(2))
                assertFalse(configured.any { it.contains("mavenLocal", ignoreCase = true) })
            }
        } finally {
            service.shutdown()
            repository.deleteRecursively()
        }
    }

    @Test
    fun `repository forwarding rejects non-file and missing locations`() {
        assertFailsWith<IllegalArgumentException> {
            ProcessManagerService(false, "https://repo.example/ares")
        }
        val missing = Files.createTempDirectory("missing-ares-repository").resolve("gone").toFile()
        assertFailsWith<IllegalArgumentException> {
            ProcessManagerService(false, missing.toURI().toASCIIString())
        }
        assertFailsWith<IllegalArgumentException> {
            ProcessManagerService(false, null, "9.6.0 invalid")
        }
    }

    @Test
    fun `normal installer command construction adds no implicit local repository`() {
        val service = ProcessManagerService(monitorAdbConnection = false)
        try {
            val configured = service.configuredGradleCommandForTest(listOf("./gradlew", "assemble"))
            assertEquals(listOf("./gradlew", "assemble"), configured.take(2))
            assertTrue(configured.single { it.startsWith("-Porg.gradle.java.installations.paths=") }.isNotBlank())
            assertEquals(null, service.configuredAresRepositoryEnvironmentForTest())
            assertEquals(null, service.configuredAresVersionEnvironmentForTest())
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `student build command verifies tests and packages without deployment`() {
        val service = ProcessManagerService(monitorAdbConnection = false)
        try {
            val ftc = service.verificationBuildCommandForTest(League.FTC, isWindows = true)
            val frc = service.verificationBuildCommandForTest(League.FRC, isWindows = false)

            assertTrue(":TeamCode:verifyAresProject" in ftc)
            assertTrue("generateAresProject" in ftc)
            assertTrue(":TeamCode:testDebugUnitTest" in ftc)
            assertTrue(":simulator:test" in ftc)
            assertTrue(":TeamCode:assembleDebug" in ftc)
            assertTrue(ftc.indexOf("generateAresProject") < ftc.indexOf(":TeamCode:verifyAresProject"))
            assertTrue("generateAresProject" in frc)
            assertTrue("verifyAresProject" in frc)
            assertTrue("test" in frc)
            assertTrue("build" in frc)
            assertTrue(frc.indexOf("generateAresProject") < frc.indexOf("verifyAresProject"))
            listOf(ftc, frc).forEach { command ->
                assertTrue("--no-parallel" in command)
                assertTrue("--no-daemon" in command)
                assertTrue("--console=plain" in command)
            }
            (ftc + frc)
                .filterNot { it.startsWith("-Porg.gradle.java.installations.paths=") }
                .forEach { argument ->
                assertFalse(argument.contains("adb", ignoreCase = true))
                assertFalse(argument.contains("deploy", ignoreCase = true))
                assertFalse(argument.contains("install", ignoreCase = true))
            }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `desktop simulator wrapper is isolated from ambient Gradle daemons`() {
        val service = ProcessManagerService(monitorAdbConnection = false)
        try {
            val ftc = service.simulationGradleCommandForTest(SimulationProductId.FTC_DESKTOP_OPMODE, isWindows = true)
            val frc = service.simulationGradleCommandForTest(SimulationProductId.FRC_WPILIB_DESKTOP, isWindows = false)

            assertEquals(listOf("cmd.exe", "/c", "gradlew.bat"), ftc.take(3))
            assertTrue(":TeamCode:runSim" in ftc)
            assertEquals("./gradlew", frc.first())
            assertTrue("simulateJava" in frc)
            assertTrue("-ParesFrcHalGui=false" in frc)
            assertFalse(ftc.any { it.startsWith("-ParesFrcHalGui=") })
            listOf(ftc, frc).forEach { command ->
                assertTrue("--no-parallel" in command)
                assertTrue("--no-daemon" in command)
                assertTrue("--console=plain" in command)
            }
            val frcJavaHome = ManagedToolchainPaths.resolveFrcSimulationJavaHome()
            if (frcJavaHome != null && File(frcJavaHome, "bin/java.exe").isFile) {
                val windowsFrc = service.simulationGradleCommandForTest(SimulationProductId.FRC_WPILIB_DESKTOP, isWindows = true)
                assertTrue(windowsFrc.any { it.startsWith("-ParesFrcJavaExecutable=") })
            }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `desktop authoring uses platform wrapper and fixed isolated arguments`() {
        val service = ProcessManagerService(monitorAdbConnection = false)
        try {
            val token = "a".repeat(64)
            val windows = service.authoringGradleCommandForTest(
                task = ":TeamCode:replaceSubsystemStarters",
                isWindows = true,
                confirmationToken = token,
            )
            val unix = service.authoringGradleCommandForTest(
                task = "generateSubsystemStarters",
                isWindows = false,
            )

            assertEquals(listOf("cmd.exe", "/c", "gradlew.bat"), windows.take(3))
            assertTrue(":TeamCode:replaceSubsystemStarters" in windows)
            assertTrue("-Pares.subsystemReplacementToken=$token" in windows)
            assertEquals("./gradlew", unix.first())
            assertTrue("generateSubsystemStarters" in unix)
            listOf(windows, unix).forEach { command ->
                assertFalse("org.gradle.wrapper.GradleWrapperMain" in command)
                assertTrue("--no-parallel" in command)
                assertTrue("--no-daemon" in command)
                assertTrue("--console=plain" in command)
            }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `confirmed deploy plan verifies before a target-scoped install`() {
        val service = ProcessManagerService(monitorAdbConnection = false)
        try {
            val ftc = service.ftcDeployBuildCommandForTest(isWindows = true)
            val frc = service.frcDeployBuildCommandForTest(isWindows = false)
            val install = service.adbInstallCommandForTest("adb", "robot.apk")

            assertTrue("generateAresProject" in ftc)
            assertTrue("verifyAresProject" in ftc)
            assertTrue(":TeamCode:testDebugUnitTest" in ftc)
            assertTrue(":simulator:test" in ftc)
            assertTrue(":TeamCode:assembleDebug" in ftc)
            assertTrue(ftc.indexOf(":TeamCode:testDebugUnitTest") < ftc.indexOf(":TeamCode:assembleDebug"))

            assertTrue("verifyAresProject" in frc)
            assertTrue("test" in frc)
            assertTrue("build" in frc)
            assertTrue("deploy" in frc)
            assertTrue(frc.indexOf("test") < frc.indexOf("deploy"))

            assertEquals(
                listOf("adb", "-s", "192.168.43.1:5555", "install", "-r", "-d", "robot.apk"),
                install,
                "FTC install must never target an arbitrary connected Android device",
            )
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `verification outcome retains selected project success and failure evidence`() = runBlocking {
        val service = ProcessManagerService(monitorAdbConnection = false)
        val project = Files.createTempDirectory("process-manager-build-result").toFile()
        val javaExecutable = File(
            System.getProperty("java.home"),
            "bin/java${if (System.getProperty("os.name").contains("win", ignoreCase = true)) ".exe" else ""}",
        )
        try {
            service.runVerificationProcessForTest(
                listOf(javaExecutable.absolutePath, "-version"),
                project.path,
                League.FTC,
            )
            withTimeout(5_000L) {
                while (service.buildExecutionState.value.phase == BuildExecutionPhase.IDLE) delay(10L)
            }
            service.awaitBuildIdleForTest()

            val success = service.buildExecutionState.value
            assertEquals(BuildExecutionPhase.SUCCEEDED, success.phase)
            assertEquals(project.absoluteFile.normalize().path, success.projectPath)
            assertEquals(League.FTC, success.league)
            assertEquals(0, success.exitCode)

            service.runVerificationProcessForTest(
                listOf(javaExecutable.absolutePath, "-cp", project.path, "MissingAresBuildMain"),
                project.path,
                League.FTC,
            )
            withTimeout(5_000L) {
                while (service.buildExecutionState.value.requestId == success.requestId) delay(10L)
            }
            service.awaitBuildIdleForTest()

            val failure = service.buildExecutionState.value
            assertEquals(BuildExecutionPhase.FAILED, failure.phase)
            assertTrue((failure.exitCode ?: 0) != 0)
            assertEquals(success.requestId + 1L, failure.requestId)
        } finally {
            withContext(Dispatchers.IO) { service.shutdown() }
            project.deleteRecursively()
        }
    }

    @Test
    fun `stopping verification records cancellation and kills its process`() = runBlocking {
        val service = ProcessManagerService(monitorAdbConnection = false)
        val project = Files.createTempDirectory("process-manager-build-cancel").toFile()
        val pidFile = File(project, "verification.pid")
        val neverReleased = File(project, "never-release")
        try {
            service.runVerificationProcessForTest(
                probeCommand("wait", pidFile.absolutePath, neverReleased.absolutePath),
                project.path,
                League.FRC,
            )
            val pid = awaitPid(pidFile)

            service.killActiveBuildAndJoin()

            awaitProcessExit(pid)
            val canceled = service.buildExecutionState.value
            assertEquals(BuildExecutionPhase.CANCELED, canceled.phase)
            assertEquals(League.FRC, canceled.league)
            assertTrue(canceled.message.contains("canceled", ignoreCase = true))
            assertTrue(canceled.message.contains("No deployment", ignoreCase = true))
        } finally {
            withContext(Dispatchers.IO) { service.shutdown() }
            project.deleteRecursively()
        }
    }

    @Test
    fun `starter preview token is hash-bound and stale apply is rejected before Gradle`() {
        val service = ProcessManagerService(monitorAdbConnection = false)
        val root = Files.createTempDirectory("process-manager-starter-plan").toFile()
        try {
            val document = SubsystemTemplates.createWithOwnership(
                SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
                "elevator",
                "Elevator",
                SubsystemPlatform.FTC,
                implementationKind = com.areslib.subsystem.SubsystemImplementationKind.GENERATED_STARTER,
            )
            root.resolve(".ares/subsystems").mkdirs()
            root.resolve(".ares/subsystems/elevator.aressubsystem").writeText(SubsystemDocumentCodec.encode(document))
            val generated = SubsystemKotlinGenerator.generate(
                document,
                SubsystemKotlinCodegenTarget(SubsystemPlatform.FTC, "org.firstinspires.ftc.teamcode.subsystems"),
            ).first { it.ownership == com.areslib.codegen.SubsystemArtifactOwnership.GENERATED_STARTER }
            val starter = root.resolve(
                "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/${generated.relativePath}"
            )
            starter.parentFile.mkdirs()
            starter.writeText(generated.content.lines().toMutableList().also {
                it[1] = "// reviewed local customization"
            }.joinToString("\n"))

            val preview = service.previewSubsystemStarters(root.path, League.FTC)
            assertTrue(preview.hasReplacements)
            assertTrue(preview.confirmationToken?.matches(Regex("[a-f0-9]{64}")) == true)
            assertFailsWith<IllegalArgumentException> {
                service.applySubsystemStarters(root.path, League.FTC, "0".repeat(64))
            }
        } finally {
            service.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun `replacement joins old generation and cannot clear the new process state`() = runBlocking {
        val service = ProcessManagerService(monitorAdbConnection = false)
        val directory = Files.createTempDirectory("process-manager-replacement").toFile()
        val oldParentPid = File(directory, "old-parent.pid")
        val oldChildPid = File(directory, "old-child.pid")
        val newPidFile = File(directory, "new.pid")
        val releaseNew = File(directory, "release-new")

        try {
            service.runManagedProcessForTest(
                probeCommand("tree", oldParentPid.absolutePath, oldChildPid.absolutePath),
                generationOperation = true
            )
            val oldParent = awaitPid(oldParentPid)
            val oldChild = awaitPid(oldChildPid)

            service.runManagedProcessForTest(
                probeCommand("wait", newPidFile.absolutePath, releaseNew.absolutePath)
            )
            val newPid = awaitPid(newPidFile)

            awaitProcessExit(oldParent)
            awaitProcessExit(oldChild)
            assertTrue(service.isBuildRunning.value, "old cleanup cleared the replacement's running state")
            assertTrue(isAlive(newPid), "replacement exited before the release signal")
            assertEquals(AresGenerationPhase.FAILED, service.aresGenerationState.value.phase)

            releaseNew.writeText("release")
            service.awaitBuildIdleForTest()
            awaitProcessExit(newPid)
            assertFalse(service.isBuildRunning.value)
        } finally {
            withContext(Dispatchers.IO) { service.shutdown() }
            directory.deleteRecursively()
        }
    }

    @Test
    fun `slow terminal collector cannot backpressure verbose build output`() = runBlocking {
        val service = ProcessManagerService(monitorAdbConnection = false)
        val slowCollector = launch {
            service.buildOutput.collect {
                delay(50L)
            }
        }
        try {
            service.runManagedProcessForTest(probeCommand("flood"))
            withTimeout(10_000L) { service.awaitBuildIdleForTest() }
            assertFalse(service.isBuildRunning.value)
        } finally {
            slowCollector.cancelAndJoin()
            withContext(Dispatchers.IO) { service.shutdown() }
        }
    }

    @Test
    fun `shutdown remains non-cancellable and kills the complete process tree`() = runBlocking {
        val service = ProcessManagerService(monitorAdbConnection = false)
        val directory = Files.createTempDirectory("process-manager-shutdown").toFile()
        val parentPidFile = File(directory, "parent.pid")
        val childPidFile = File(directory, "child.pid")

        try {
            service.runManagedProcessForTest(
                probeCommand("tree", parentPidFile.absolutePath, childPidFile.absolutePath)
            )
            val parentPid = awaitPid(parentPidFile)
            val childPid = awaitPid(childPidFile)

            val shutdown = launch(start = CoroutineStart.UNDISPATCHED) { service.shutdownAndJoin() }
            shutdown.cancelAndJoin()

            awaitProcessExit(parentPid)
            awaitProcessExit(childPid)
            assertFalse(service.isBuildRunning.value)
        } finally {
            withContext(Dispatchers.IO) { service.shutdown() }
            directory.deleteRecursively()
        }
    }

    private suspend fun awaitPid(file: File): Long = withTimeout(5_000L) {
        while (true) {
            val pid = runCatching { file.takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull() }
                .getOrNull()
            if (pid != null) return@withTimeout pid
            delay(10L)
        }
        error("unreachable")
    }

    private suspend fun awaitProcessExit(pid: Long) {
        withTimeout(5_000L) {
            while (isAlive(pid)) delay(10L)
        }
        assertFalse(isAlive(pid), "process $pid survived cleanup")
    }

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun probeCommand(mode: String, vararg arguments: String): List<String> {
        val javaExecutable = File(
            System.getProperty("java.home"),
            "bin/java${if (System.getProperty("os.name").contains("win", ignoreCase = true)) ".exe" else ""}"
        )
        return buildList {
            add(javaExecutable.absolutePath)
            add("-cp")
            add(compiledProcessProbe().absolutePath)
            add(PROCESS_PROBE_CLASS)
            add(mode)
            addAll(arguments)
        }
    }

    private fun compiledProcessProbe(): File = synchronized(PROBE_LOCK) {
        compiledProbeDirectory?.takeIf(File::isDirectory)?.let { return@synchronized it }
        val directory = Files.createTempDirectory("process-manager-probe").toFile().apply { deleteOnExit() }
        val source = File(directory, "$PROCESS_PROBE_CLASS.java").apply {
            writeText(
                """
                import java.nio.file.Files;
                import java.nio.file.Path;

                public final class $PROCESS_PROBE_CLASS {
                    public static void main(String[] args) throws Exception {
                        if ("child".equals(args[0])) {
                            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                            Thread.sleep(60_000L);
                            return;
                        }
                        if ("tree".equals(args[0])) {
                            String javaHome = System.getProperty("java.home");
                            String executable = Path.of(
                                javaHome,
                                "bin",
                                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java"
                            ).toString();
                            new ProcessBuilder(
                                executable,
                                "-cp",
                                System.getProperty("java.class.path"),
                                $PROCESS_PROBE_CLASS.class.getName(),
                                "child",
                                args[2]
                            ).start();
                            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                            Thread.sleep(60_000L);
                            return;
                        }
                        if ("flood".equals(args[0])) {
                            for (int index = 0; index < 10_000; index++) {
                                System.out.println("generated line " + index);
                            }
                            return;
                        }
                        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                        while (!Files.exists(Path.of(args[2]))) Thread.sleep(10L);
                    }
                }
                """.trimIndent()
            )
        }
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "Tests require a JDK compiler" }
        assertEquals(0, compiler.run(null, null, null, "-d", directory.absolutePath, source.absolutePath))
        compiledProbeDirectory = directory
        directory
    }

    private companion object {
        const val PROCESS_PROBE_CLASS = "ProcessManagerProbe"
        val PROBE_LOCK = Any()
        var compiledProbeDirectory: File? = null
    }
}
