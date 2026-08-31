package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.areslib.simulation.SimulationProductId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SimulatorProcessServiceTest {
    @Test
    fun `simulator lifecycle owns and clears only simulator state`() = runBlocking {
        val project = Files.createTempDirectory("simulator-process-service").toFile()
        val service = SimulatorProcessService(aresRepositoryUri = null)
        val longRunningCommand = if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
            "ping -n 30 127.0.0.1"
        } else {
            "sleep 30"
        }
        try {
            service.start(project.absolutePath, SimulationProductId.FTC_DESKTOP_OPMODE, longRunningCommand)
            val running = withTimeout(5_000L) { service.state.first { it.running } }

            assertEquals(project.canonicalPath, running.projectPath)
            assertEquals(League.FTC, running.league)
            withContext(Dispatchers.IO) { service.stop() }
            assertFalse(service.state.value.running)
        } finally {
            withContext(Dispatchers.IO) { service.shutdown() }
            project.deleteRecursively()
        }
    }

    @Test
    fun `stale project is rejected before a simulator process starts`() = runBlocking {
        val project = Files.createTempDirectory("simulator-process-stale-project").toFile()
        val service = SimulatorProcessService(aresRepositoryUri = null, aresVersion = "13.0.0")
        try {
            project.resolve("gradle.properties").writeText("aresVersion=12.0.0\n")

            service.start(
                project.absolutePath,
                SimulationProductId.FTC_DESKTOP_OPMODE,
                simulatorCommand = "this command must never execute",
            )
            val message = withTimeout(5_000L) {
                service.output.firstOrNull { it.contains("pins ARES 12.0.0") }
            }

            assertNotNull(message)
            assertTrue(message.contains("Simulation could not start"))
            assertFalse(service.state.value.running)
        } finally {
            withContext(Dispatchers.IO) { service.shutdown() }
            project.deleteRecursively()
        }
    }
}
