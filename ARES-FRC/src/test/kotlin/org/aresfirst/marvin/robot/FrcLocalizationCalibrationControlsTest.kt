package org.aresfirst.marvin.robot

import com.areslib.Store
import com.areslib.frc.vision.FrcLocalizationCalibrationSession
import com.areslib.telemetry.ITelemetry
import edu.wpi.first.hal.HAL
import edu.wpi.first.wpilibj.XboxController
import edu.wpi.first.wpilibj.simulation.DriverStationSim
import edu.wpi.first.wpilibj.simulation.XboxControllerSim
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FrcLocalizationCalibrationControlsTest {
    @TempDir lateinit var directory: Path
    private lateinit var controller: XboxController
    private lateinit var input: XboxControllerSim
    private lateinit var session: FrcLocalizationCalibrationSession
    private lateinit var controls: FrcLocalizationCalibrationControls

    private val telemetry = object : ITelemetry {
        override fun putNumber(key: String, value: Double) = Unit
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putString(key: String, value: String) = Unit
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
    }

    @BeforeEach fun setup() {
        assertTrue(HAL.initialize(500, 0))
        DriverStationSim.resetData()
        DriverStationSim.setDsAttached(true)
        controller = XboxController(0)
        input = XboxControllerSim(controller)
        session = FrcLocalizationCalibrationSession(Store(), null, { emptyList() }, directory.toFile())
        controls = FrcLocalizationCalibrationControls(session) { 1000L }
    }

    @AfterEach fun cleanup() {
        try { if (::session.isInitialized) session.close() }
        finally { DriverStationSim.resetData() }
    }

    private fun frame(homing: Boolean = false) {
        DriverStationSim.notifyNewData()
        controls.update(controller, null, telemetry, homing)
    }

    @Test fun `held Back after homing requires release and repress before zeroing truth`() {
        session.adjustTruth(deltaX = 2.0)
        input.setBackButton(true)
        input.setStartButton(true)
        frame(homing = true)
        input.setStartButton(false)
        frame()
        assertEquals(2.0, session.truthX)
        frame()
        assertEquals(2.0, session.truthX)
        input.setBackButton(false)
        frame()
        input.setBackButton(true)
        frame()
        assertEquals(0.0, session.truthX)
    }

    @Test fun `held Start after homing cannot queue a pose seed`() {
        input.setBackButton(true)
        input.setStartButton(true)
        frame(homing = true)
        input.setBackButton(false)
        frame()
        assertFalse(session.actionPending)
        input.setStartButton(false)
        frame()
        input.setStartButton(true)
        frame()
        assertTrue(session.actionPending)
    }

    @Test fun `held buttons and dpad edit once per press and truth edits cancel checkpoints`() {
        input.setAButton(true)
        input.setPOV(90)
        frame()
        frame()
        assertTrue(session.continuousRecording)
        assertEquals(0.05, session.truthX)
        input.setAButton(false)
        input.setPOV(-1)
        frame()
        input.setXButton(true)
        frame()
        assertTrue(session.actionPending)
        input.setPOV(0)
        frame()
        assertFalse(session.actionPending)
        assertEquals(0.05, session.truthY)
        input.setAButton(true)
        frame()
        assertFalse(session.continuousRecording)
    }
}
