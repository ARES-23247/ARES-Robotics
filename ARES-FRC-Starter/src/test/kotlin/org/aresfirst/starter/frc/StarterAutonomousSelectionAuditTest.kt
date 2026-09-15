package org.aresfirst.starter.frc

import com.areslib.frc.runtime.FrcControllerPortSampler
import com.areslib.frc.runtime.FrcGeneratedProjectControlsRuntime
import com.areslib.input.InputFrame
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutinePose
import com.areslib.telemetry.ITelemetry
import com.areslib.util.RobotClock
import org.aresfirst.starter.frc.generated.GeneratedAresProject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StarterAutonomousSelectionAuditTest {
    private fun entry(id: String, order: Int = 0, enabled: Boolean = true) = AutonomousCatalogEntry(
        entryId = id, displayName = id, routineId = id, startingPose = RoutinePose(0.0, 0.0, 0.0),
        sortOrder = order, enabled = enabled)

    @Test fun `configured default fallback may select motion and explicit valid requests retain precedence`() {
        val selector = StarterFrcAutonomousSelector(listOf(entry("drive", 1), entry("do-nothing")), "drive")
        val fallback = selector.resolve(" deleted ")
        assertEquals("drive", fallback.entry.entryId)
        assertEquals("deleted", fallback.requestedId)
        assertTrue(fallback.usedFallback)
        assertEquals("drive", selector.resolve(" ").entry.entryId)
        val explicit = selector.resolve(" do-nothing ")
        assertEquals("do-nothing", explicit.entry.entryId)
        assertFalse(explicit.usedFallback)
    }

    @Test fun `fallback order excludes disabled entries and preserves deterministic sorting`() {
        val entries = listOf(entry("z-drive", 5), entry("a-drive", 5), entry("disabled", -1, false), entry("do-nothing", 10))
        for (default in listOf(null, "missing", "disabled")) {
            val selector = StarterFrcAutonomousSelector(entries, default)
            assertEquals(listOf("a-drive", "z-drive", "do-nothing"), selector.availableEntryIds)
            assertEquals("do-nothing", selector.resolve("disabled").entry.entryId)
        }
        val noSafeEntry = StarterFrcAutonomousSelector(entries.filter { it.entryId != "do-nothing" }, null)
        assertEquals("a-drive", noSafeEntry.resolve("missing").entry.entryId)
        for (empty in listOf(emptyList(), listOf(entry("disabled", enabled = false)))) {
            assertThrows(IllegalStateException::class.java) { StarterFrcAutonomousSelector(empty, null).resolve("anything") }
        }
    }

    @Test fun `selector owns a stable snapshot of its input catalog`() {
        val entries = mutableListOf(entry("do-nothing"), entry("drive"))
        val selector = StarterFrcAutonomousSelector(entries, "do-nothing")
        entries.clear()
        entries += entry("replacement")
        assertEquals(listOf("do-nothing", "drive"), selector.availableEntryIds)
        assertEquals("drive", selector.resolve("drive").entry.entryId)
    }

    private class Telemetry : ITelemetry {
        val strings = HashMap<String, String>()
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putNumber(key: String, value: Double) = Unit
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getString(key: String, defaultValue: String) = strings[key] ?: defaultValue
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
    }

    @Test fun `actual autonomous fallback publishes an accurate status and completes the no-motion entry`() {
        val wasMocked = RobotClock.isMocked
        val old = RobotClock.currentTimeMillis()
        RobotClock.useMockTime(1000L)
        val telemetry = Telemetry()
        val robot = StarterRobotRuntime(telemetry)
        val capabilities = StarterGeneratedCapabilities(robot, drivePermitted = false)
        val sampler = object : FrcControllerPortSampler {
            override fun prepare(port: Int) = Unit
            override fun sampleInto(port: Int, frame: InputFrame, nowNanos: Long) = error("No controller sampling expected")
        }
        val controls = FrcGeneratedProjectControlsRuntime(GeneratedAresProject.runtimeDefinition,
            { robot.store.state }, robot.store::dispatch, capabilities, sampler)
        val runtime = StarterFrcAutonomousRuntime(robot, StarterDriveSimulation(), controls, capabilities,
            isSimulation = true, selectionProvider = { "deleted-auto" })
        try {
            runtime.autonomousInit()
            assertEquals("Running fallback", telemetry.strings["ARES/Auto/Status"])
            assertEquals("do-nothing", runtime.selectedEntryIdForTest)
            runtime.autonomousPeriodic()
            assertEquals("Complete", telemetry.strings["ARES/Auto/Status"])
            assertEquals(0.0, robot.store.state.drive.xVelocityMetersPerSecond)
        } finally {
            try { runtime.stop("Test complete") } finally {
                robot.close()
                if (wasMocked) RobotClock.useMockTime(old) else RobotClock.useSystemTime()
            }
        }
    }
}
