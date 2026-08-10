package com.areslib.tuning

import com.areslib.state.RobotState
import com.areslib.Store
import com.areslib.state.TuningState
import com.areslib.telemetry.ITelemetry
import com.areslib.control.tuning.PIDFCoefficients
import com.areslib.control.tuning.SimpleFeedforwardCoeffs
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TuningManagerTest {

    // Simple mock telemetry for testing NT4 interactions
    class MockTelemetry : ITelemetry {
        val data = mutableMapOf<String, Double>()
        
        override fun putNumber(key: String, value: Double) {
            data[key] = value
        }
        override fun putBoolean(key: String, value: Boolean) {}
        override fun putString(key: String, value: String) {}
        override fun putDoubleArray(key: String, value: DoubleArray) {}
        
        override fun getNumber(key: String, defaultValue: Double): Double {
            return data[key] ?: defaultValue
        }
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
    }

    @Test
    fun `canonical nested PIDF topic updates state and persists`(@TempDir tempDir: File) {
        val store = Store(RobotState())
        val mockTelemetry = MockTelemetry()
        val saveFile = File(tempDir, "ares_tuning.json")
        
        val manager = TuningManager(store, mockTelemetry, saveFile)
        
        // Initial state
        val initialState = store.state.tuning
        assertEquals(2.0, initialState.pathTranslationGains.kP)
        
        // Simulate a dashboard modification via NT4 (user sets kP to 5.0)
        mockTelemetry.putNumber("Tuning/drive/pathTranslationGains/kP", 5.0)
        
        // Call update
        manager.update()
        
        // Verify state is updated in Redux
        val updatedState = store.state.tuning
        assertEquals(5.0, updatedState.pathTranslationGains.kP)
        assertEquals(0.0, updatedState.pathTranslationGains.kI) // Unchanged
        
        // Verify JSON was written
        assertTrue(saveFile.exists())
        val jsonContent = saveFile.readText()
        assertTrue(jsonContent.contains("\"kP\": 5.0"))
        
        // Now simulate a restart by creating a new manager with the same file
        val newStore = Store(RobotState()) // Back to default 2.0
        val newTelemetry = MockTelemetry()
        val newManager = TuningManager(newStore, newTelemetry, saveFile)
        assertNotNull(newManager)
        
        // The init block should have loaded the JSON
        val loadedState = newStore.state.tuning
        assertEquals(5.0, loadedState.pathTranslationGains.kP)
    }

    @Test
    fun `nonfinite dashboard tuning value is rejected and republished`(@TempDir tempDir: File) {
        val store = Store(RobotState())
        val telemetry = MockTelemetry()
        val manager = TuningManager(store, telemetry, File(tempDir, "ares_tuning.json"))
        val key = "Tuning/drive/pathTranslationGains/kP"

        telemetry.putNumber(key, Double.NaN)
        manager.update(timestampMs = 1_000L)

        assertEquals(2.0, store.state.tuning.pathTranslationGains.kP)
        assertEquals(2.0, telemetry.data[key])
    }

    @Test
    fun `corrupt primary tuning file recovers from validated backup`(@TempDir tempDir: File) {
        val saveFile = File(tempDir, "ares_tuning.json")
        saveFile.writeText("{not-json")
        File(tempDir, "ares_tuning.backup.json").writeText(
            """{"_schemaVersion":2,"drive":{"pathTranslationGains":{"kP":4.5}}}"""
        )

        val store = Store(RobotState())
        TuningManager(store, MockTelemetry(), saveFile)

        assertEquals(4.5, store.state.tuning.pathTranslationGains.kP)
    }

    @Test
    fun `nonfinite number in tuning file cannot poison redux state`(@TempDir tempDir: File) {
        val saveFile = File(tempDir, "ares_tuning.json")
        saveFile.writeText("""{"_schemaVersion":2,"drive":{"pathTranslationGains":{"kP":NaN}}}""")

        val store = Store(RobotState())
        TuningManager(store, MockTelemetry(), saveFile)

        assertEquals(2.0, store.state.tuning.pathTranslationGains.kP)
        assertTrue(store.state.tuning.pathTranslationGains.kP.isFinite())
    }

    @Test
    fun `schema v1 tuning file is rejected`(@TempDir tempDir: File) {
        val saveFile = File(tempDir, "ares_tuning.json")
        saveFile.writeText("""{"pathTranslationGains":{"kP":9.0}}""")

        val store = Store(RobotState())
        TuningManager(store, MockTelemetry(), saveFile)

        assertEquals(2.0, store.state.tuning.pathTranslationGains.kP)
    }

}
