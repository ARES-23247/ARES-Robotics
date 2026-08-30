package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.areslib.simulation.SimulationProductId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
