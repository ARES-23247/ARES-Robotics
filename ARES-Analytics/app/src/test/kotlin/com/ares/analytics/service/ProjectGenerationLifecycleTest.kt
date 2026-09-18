package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real service entrypoints with a controlled wrapper, not a Gradle/codegen integration test. */
class ProjectGenerationLifecycleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `starter wrapper preflight failure finalizes state and permits retry`() = runBlocking {
        val root = project("missing-wrapper")
        val service = ProjectBuildService(aresRepositoryUri = null)
        try {
            service.applySubsystemStarters(root.path, League.FTC)
            awaitFinished(service)
            assertEquals(AresGenerationPhase.FAILED, service.aresGenerationState.value.phase)
            assertTrue(service.aresGenerationState.value.message.contains("gradlew"))

            writeWrapper(root, wait = false)
            service.applySubsystemStarters(root.path, League.FTC)
            withTimeout(10_000) {
                while (service.aresGenerationState.value.phase != AresGenerationPhase.SUCCEEDED) delay(10)
            }
            awaitFinished(service)
        } finally {
            service.shutdownAndJoin()
        }
    }

    @Test
    fun `both generation entrypoints cancel a running wrapper and preserve canonical inputs`() = runBlocking {
        for (starters in listOf(false, true)) {
            val root = project("cancel-$starters")
            val canonical = File(root, ".ares/project.json").readBytes()
            writeWrapper(root, wait = true)
            val service = ProjectBuildService(aresRepositoryUri = null)
            try {
                if (starters) service.applySubsystemStarters(root.path, League.FTC)
                else service.generateAresProject(root.path, League.FTC)
                val pidFile = File(root, "generation.pid")
                val pid = withTimeout(15_000) {
                    var readyPid: Long? = null
                    while (readyPid == null) {
                        readyPid = if (pidFile.isFile) pidFile.readText().trim().toLongOrNull() else null
                        if (readyPid == null) delay(10)
                    }
                    readyPid
                }
                assertTrue(File(root, "partial-output.txt").isFile, "Wrapper must start and write before cancellation")
                assertEquals(AresGenerationPhase.RUNNING, service.aresGenerationState.value.phase)
                service.killActiveBuildAndJoin()
                withTimeout(5_000) {
                    while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10)
                }
                assertEquals(AresGenerationPhase.FAILED, service.aresGenerationState.value.phase)
                val expectedMessage = if (starters) "Subsystem starter generation was canceled." else "Project generation was canceled."
                assertEquals(expectedMessage, service.aresGenerationState.value.message)
                assertFalse(service.processState.value.buildRunning)
                assertTrue(canonical.contentEquals(File(root, ".ares/project.json").readBytes()))
            } finally {
                service.shutdownAndJoin()
            }
        }
    }

    private suspend fun awaitFinished(service: ProjectBuildService) = withTimeout(10_000) {
        while (service.aresGenerationState.value.phase == AresGenerationPhase.IDLE || service.processState.value.buildRunning) delay(10)
    }

    private fun project(name: String): File = temporaryFolder.newFolder(name).apply {
        File(this, ".ares").mkdirs()
        File(this, ".ares/project.json").writeText("{}")
    }

    private fun writeWrapper(root: File, wait: Boolean) {
        File(root, "gradle/wrapper/gradle-wrapper.jar").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf()) // Preflight presence only; the controlled wrapper never invokes Gradle.
        }
        if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
            File(root, "gradlew.bat").writeText(
                if (wait) "@echo off\r\npowershell.exe -NoProfile -Command \"'partial' | Set-Content partial-output.txt; ${'$'}PID | Set-Content generation.pid; Start-Sleep -Seconds 60\"\r\nexit /b %errorlevel%\r\n"
                else "@echo off\r\nexit /b 0\r\n",
            )
        } else {
            File(root, "gradlew").writeText(
                if (wait) "#!/bin/sh\nprintf partial > partial-output.txt\necho ${'$'}${'$'} > generation.pid\nsleep 60\n"
                else "#!/bin/sh\nexit 0\n",
            )
        }
    }
}
