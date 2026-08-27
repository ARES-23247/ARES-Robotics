package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.viewmodel.CalibrationArmPhase
import com.ares.analytics.viewmodel.SysIdState
import com.areslib.control.assist.SysIdMechanism
import com.areslib.control.assist.SysIdRoutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SysIdSignalGeneratorTest {
    private val databaseFile = File.createTempFile("sysid-arm", ".duckdb")
    private val database = DatabaseService(databaseFile.absolutePath)
    private val nt4 = Nt4ClientService(database)

    @AfterTest
    fun close() = runTest {
        nt4.stop()
        database.close()
        databaseFile.delete()
    }

    @Test
    fun `arm publishes stop fresh token and renewable lease before motion is allowed`() = runTest {
        val state = MutableStateFlow(
            SysIdState(
                isRobotConnected = true,
                calibrationModeEnabled = true,
                capabilitiesKnown = true,
                supportedMechanisms = setOf(SysIdMechanism.LINEAR),
                requiresNetworkArm = true
            )
        )
        val transport = RecordingCalibrationTransport()
        val generator = SysIdSignalGenerator(nt4, state, this, transport)

        generator.arm()

        assertEquals(CalibrationArmPhase.ARMING, state.value.armPhase)
        assertEquals(1015 to "STOP", transport.strings[0])
        assertEquals(1016, transport.strings[1].first)
        assertTrue(transport.strings[1].second.startsWith("ares-"))
        assertEquals(1017 to 1.0, transport.doubles.single())
        assertFailsWith<IllegalStateException> {
            generator.startRoutine(SysIdMechanism.LINEAR, SysIdRoutine.QUASISTATIC)
        }

        state.value = state.value.copy(
            armPhase = CalibrationArmPhase.ARMED,
            robotCalibrationArmed = true
        )
        generator.startRoutine(SysIdMechanism.LINEAR, SysIdRoutine.QUASISTATIC)
        assertEquals(1015 to "START_LINEAR_QUASISTATIC", transport.strings.last())

        advanceTimeBy(200)
        runCurrent()
        assertEquals(1017 to 2.0, transport.doubles.last())
        generator.disarm("Test complete")
    }

    @Test
    fun `disarm revokes token stops renewal and blocks later motion`() = runTest {
        val state = MutableStateFlow(
            SysIdState(
                isRobotConnected = true,
                calibrationModeEnabled = true,
                capabilitiesKnown = true,
                supportedMechanisms = setOf(SysIdMechanism.LINEAR),
                requiresNetworkArm = true
            )
        )
        val transport = RecordingCalibrationTransport()
        val generator = SysIdSignalGenerator(nt4, state, this, transport)
        generator.arm()
        state.value = state.value.copy(
            armPhase = CalibrationArmPhase.ARMED,
            robotCalibrationArmed = true
        )

        generator.disarm("Left tuning")
        val leaseCount = transport.doubles.size
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(CalibrationArmPhase.DISARMED, state.value.armPhase)
        assertFalse(state.value.robotCalibrationArmed)
        assertEquals(leaseCount, transport.doubles.size)
        assertEquals(listOf(1015 to "STOP", 1016 to ""), transport.strings.takeLast(2))
        assertFailsWith<IllegalStateException> {
            generator.startCalibration("LINEAR_DRIVE")
        }
    }

    @Test
    fun `live motion fails closed when runtime omits or rejects mechanism capability`() = runTest {
        val state = MutableStateFlow(
            SysIdState(
                isRobotConnected = true,
                requiresNetworkArm = false,
                capabilitiesKnown = true,
                supportedMechanisms = setOf(SysIdMechanism.ANGULAR),
            )
        )
        val transport = RecordingCalibrationTransport()
        val generator = SysIdSignalGenerator(nt4, state, this, transport)

        assertFailsWith<IllegalStateException> {
            generator.startRoutine(SysIdMechanism.LINEAR, SysIdRoutine.DYNAMIC)
        }
        assertTrue(transport.strings.isEmpty())

        state.value = state.value.copy(capabilitiesKnown = false)
        assertFailsWith<IllegalStateException> {
            generator.startRoutine(SysIdMechanism.ANGULAR, SysIdRoutine.DYNAMIC)
        }
        assertTrue(transport.strings.isEmpty())
    }

    private class RecordingCalibrationTransport : CalibrationCommandTransport {
        val strings = mutableListOf<Pair<Int, String>>()
        val doubles = mutableListOf<Pair<Int, Double>>()

        override suspend fun publishString(pubuid: Int, value: String): Boolean {
            strings += pubuid to value
            return true
        }

        override suspend fun publishDouble(pubuid: Int, value: Double): Boolean {
            doubles += pubuid to value
            return true
        }
    }
}
