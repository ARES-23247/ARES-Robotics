package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RobotDeploymentServiceTest {
    @Test
    fun `invalid project fails inside deployment ownership without invoking a device`() = runBlocking {
        val missingProject = Files.createTempDirectory("robot-deployment-missing").resolve("gone").toFile()
        val service = RobotDeploymentService(
            aresRepositoryUri = null,
            adbPath = { error("ADB must not be resolved for an invalid project") },
        )
        try {
            service.deploy(missingProject.path, League.FTC)
            val result = withTimeout(5_000L) {
                while (service.state.value.phase == DeployExecutionPhase.IDLE) delay(10L)
                while (service.state.value.phase == DeployExecutionPhase.CONNECTING) delay(10L)
                service.state.value
            }

            assertEquals(DeployExecutionPhase.FAILED, result.phase)
            assertEquals(League.FTC, result.league)
            assertTrue(result.message.contains("does not exist", ignoreCase = true))
        } finally {
            service.shutdownAndJoin()
        }
    }

    @Test
    fun `stale project fails before FTC device discovery`() = runBlocking {
        val project = Files.createTempDirectory("robot-deployment-stale").toFile()
        project.resolve("gradle.properties").writeText("aresVersion=12.0.0\n")
        val service = RobotDeploymentService(
            aresRepositoryUri = null,
            aresVersion = "13.0.0",
            adbPath = { error("ADB must not be resolved for an incompatible project") },
        )
        try {
            service.deploy(project.path, League.FTC)
            val result = withTimeout(5_000L) {
                while (service.state.value.phase == DeployExecutionPhase.IDLE) delay(10L)
                while (service.state.value.phase == DeployExecutionPhase.CONNECTING) delay(10L)
                service.state.value
            }

            assertEquals(DeployExecutionPhase.FAILED, result.phase)
            assertTrue(result.message.contains("pins ARES 12.0.0"))
        } finally {
            service.shutdownAndJoin()
            project.deleteRecursively()
        }
    }
}
