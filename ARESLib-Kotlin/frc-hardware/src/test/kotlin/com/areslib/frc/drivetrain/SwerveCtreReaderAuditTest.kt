package com.areslib.frc.drivetrain

import com.ctre.phoenix6.swerve.SwerveDrivetrain
import edu.wpi.first.math.kinematics.SwerveModuleState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import com.areslib.util.RobotClock

class SwerveCtreReaderAuditTest {
    @BeforeEach fun clock() { RobotClock.useMockTime(1000L) }
    @AfterEach fun restoreClock() { RobotClock.useSystemTime() }
    @Test fun `refresh failure revokes prior validity and finite cached measurements`() {
        val source = AuditCtreSource()
        val reader = SwerveCtreDrivetrainReader(source)
        reader.refresh()
        assertTrue(reader.currentMeasurementsValid)
        source.failure = IllegalStateException("refresh")
        assertSame(source.failure, assertThrows(IllegalStateException::class.java) { reader.refresh() })
        assertFalse(reader.currentMeasurementsValid)
        assertFalse(reader.encoderPositionsValid)
        val out = DoubleArray(4)
        reader.getCurrents(out)
        assertTrue(out.all { it.isNaN() })
    }

    @Test fun `stale successful status and nonfinite signal cannot become valid feedback`() {
        val source = AuditCtreSource()
        val reader = SwerveCtreDrivetrainReader(source)
        source.ages[0] = 20.0
        reader.refresh()
        assertFalse(reader.currentMeasurementsValid)
        source.ages[0] = 0.0
        source.values[4] = Double.NaN
        reader.refresh()
        assertFalse(reader.encoderPositionsValid)
    }

    @Test fun `one refresh owns acquisition and getters perform no further vendor reads`() {
        val source = AuditCtreSource()
        val reader = SwerveCtreDrivetrainReader(source)
        reader.refresh()
        val calls = source.stateCalls
        assertEquals(1, calls)
        val out = DoubleArray(4)
        reader.getModuleSpeeds(out)
        reader.read()
        assertEquals(calls, source.stateCalls)
        source.values[0] = 99.0
        reader.getCurrents(out)
        assertEquals(1.0, out[0])
    }
}

internal class AuditCtreSource : SwerveCtreReaderSource {
    val values = DoubleArray(36) { if (it < 4) 1.0 else 0.0 }
    val ages = DoubleArray(36)
    val stateValue = SwerveDrivetrain.SwerveDriveState().apply { ModuleStates = Array(4) { SwerveModuleState() } }
    var failure: Throwable? = null
    var stateCalls = 0
    val refreshCalls = IntArray(36)
    val valueCalls = IntArray(36)
    val statuses = BooleanArray(36) { true }
    val timestamps = BooleanArray(36) { true }
    var configured = true
    var configureCalls = 0
    var stateAge = 0.0
    var stateFailure: Throwable? = null
    var onRefresh: ((Int) -> Unit)? = null
    override fun configure(): Boolean { configureCalls++; return configured }
    override fun refresh(index: Int) { refreshCalls[index]++; onRefresh?.invoke(index); failure?.let { throw it } }
    override fun statusOk(index: Int) = statuses[index]
    override fun value(index: Int): Double { valueCalls[index]++; return values[index] }
    override fun latencySeconds(index: Int) = ages[index]
    override fun timestampValid(index: Int) = timestamps[index]
    override fun state(): SwerveDrivetrain.SwerveDriveState { stateCalls++; stateFailure?.let { throw it }; return stateValue }
    override fun stateAgeSeconds(state: SwerveDrivetrain.SwerveDriveState) = stateAge
}
