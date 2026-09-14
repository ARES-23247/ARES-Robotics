package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.service.*
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.viewmodel.*
import com.areslib.control.assist.SysIdMechanism
import com.areslib.control.assist.SysIdRoutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.mockito.Mockito
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SysIdAcknowledgementAuditTest {
    private class Fixture(val scope: TestScope) {
        val connected = MutableStateFlow(true)
        val replay = MutableStateFlow(false)
        val store = TelemetryStore()
        val transport = Transport()
        var connectionEpoch = 1L
        var clockReady = true
        val client = Mockito.mock(Nt4ClientService::class.java).also {
            Mockito.`when`(it.isConnected).thenReturn(connected)
            Mockito.`when`(it.isReplayActive).thenReturn(replay)
            Mockito.`when`(it.telemetryStore).thenReturn(store)
            Mockito.`when`(it.telemetryFlow).thenReturn(store.updates)
            Mockito.`when`(it.controlConnectionEpoch).thenAnswer { connectionEpoch }
            Mockito.`when`(it.tuningConnectionId).thenAnswer { if (clockReady) connectionEpoch else null }
        }
        val tuner = Mockito.mock(AutoTunerService::class.java).also {
            Mockito.`when`(it.applyState).thenReturn(MutableStateFlow(TuningApplyState()))
        }
        val vm = SysIdViewModel(Mockito.mock(DatabaseService::class.java), Mockito.mock(SysIdService::class.java),
            Mockito.mock(DriverAnalysisService::class.java), tuner, client, scope.backgroundScope,
            calibrationTransport = transport)
        suspend fun frame(key: String, value: Double = 0.0, text: String? = null) {
            store.accept(TelemetryFrame(1, "live-telemetry", key, value, text))
            scope.runCurrent()
        }
        suspend fun arm() {
            frame("SysId/ModeEnabled", 1.0)
            frame("SysId/SupportedMechanisms", text = "LINEAR")
            vm.onIntent(SysIdIntent.ArmCalibration); scope.runCurrent()
        }
        suspend fun acknowledge() { arm(); frame("SysId/Armed", 1.0) }
    }
    private fun checkState(block: suspend Fixture.() -> Unit) = runTest {
        val fixture = Fixture(this); runCurrent(); fixture.block()
    }
    private fun readyState() = MutableStateFlow(SysIdState(isRobotConnected = true, calibrationModeEnabled = true,
        capabilitiesKnown = true, supportedMechanisms = setOf(SysIdMechanism.LINEAR),
        armPhase = CalibrationArmPhase.ARMED, robotCalibrationArmed = true))
    private class Transport : CalibrationCommandTransport {
        val strings = mutableListOf<Pair<Int, String>>()
        val doubles = mutableListOf<Pair<Int, Double>>()
        var beforeString: suspend (Int, String) -> Unit = { _, _ -> }
        var beforeDouble: suspend (Int, Double) -> Unit = { _, _ -> }
        var stringsReady = true
        override suspend fun publishString(pubuid: Int, value: String): Boolean {
            strings += pubuid to value; beforeString(pubuid, value); return stringsReady
        }
        override suspend fun publishDouble(pubuid: Int, value: Double): Boolean {
            doubles += pubuid to value; beforeDouble(pubuid, value); return true
        }
    }

    @Test fun `NaN mode does not enable calibration`() = checkState {
        frame("SysId/ModeEnabled", Double.NaN)
        assertFalse(vm.state.value.calibrationModeEnabled)
    }
    @Test fun `nonbinary mode does not enable calibration`() = checkState {
        frame("SysId/ModeEnabled", 2.0)
        assertFalse(vm.state.value.calibrationModeEnabled)
    }
    @Test fun `text mode does not enable calibration through its numeric sentinel`() = checkState {
        frame("SysId/ModeEnabled", 1.0, "true")
        assertFalse(vm.state.value.calibrationModeEnabled)
    }
    @Test fun `unsolicited armed telemetry cannot establish a local calibration lease`() = checkState {
        frame("SysId/ModeEnabled", 1.0)
        frame("SysId/Armed", 1.0)
        assertFalse(vm.state.value.robotCalibrationArmed)
        assertEquals(CalibrationArmPhase.DISARMED, vm.state.value.armPhase)
    }
    @Test fun `replay cannot advertise live motion capabilities`() = checkState {
        replay.value = true; scope.runCurrent()
        frame("SysId/SupportedMechanisms", text = "LINEAR,ANGULAR")
        assertFalse(vm.state.value.capabilitiesKnown)
        assertTrue(vm.state.value.supportedMechanisms.isEmpty())
    }
    @Test fun `disconnect clears the previous calibration mode observation`() = checkState {
        frame("SysId/ModeEnabled", 1.0)
        connected.value = false; scope.runCurrent()
        assertFalse(vm.state.value.calibrationModeEnabled)
    }
    @Test fun `target epoch changes invalidate live mode and capabilities without a connection pulse`() = checkState {
        frame("SysId/ModeEnabled", 1.0)
        frame("SysId/SupportedMechanisms", text = "LINEAR")
        store.clear(); scope.runCurrent()
        assertFalse(vm.state.value.calibrationModeEnabled)
        assertFalse(vm.state.value.capabilitiesKnown)
        assertTrue(vm.state.value.supportedMechanisms.isEmpty())
    }
    @Test fun `queued old-target mode frames cannot reenable calibration after clear`() = checkState {
        store.accept(TelemetryFrame(1, "live-telemetry", "SysId/ModeEnabled", 1.0))
        store.clear(); scope.runCurrent()
        assertFalse(vm.state.value.calibrationModeEnabled)
    }
    @Test fun `state arm flags without an owned lease cannot authorize a command`() = checkState {
        val transport = Transport(); val generator = SysIdSignalGenerator(client, readyState(), scope.backgroundScope, transport)
        assertFailsWith<IllegalStateException> { generator.startRoutine(SysIdMechanism.LINEAR, SysIdRoutine.DYNAMIC) }
        assertTrue(transport.strings.isEmpty())
    }
    @Test fun `mode loss blocks commands even while an old local lease still exists`() = checkState {
        val state = readyState(); val transport = Transport()
        val generator = SysIdSignalGenerator(client, state, scope.backgroundScope, transport)
        generator.arm()
        state.value = state.value.copy(armPhase = CalibrationArmPhase.ARMED, robotCalibrationArmed = true, calibrationModeEnabled = false)
        assertFailsWith<IllegalStateException> { generator.startRoutine(SysIdMechanism.LINEAR, SysIdRoutine.DYNAMIC) }
        assertTrue(transport.strings.none { it.second.startsWith("START_") })
    }
    @Test fun `replay blocks motion even for a platform without a network arm lease`() = checkState {
        val state = readyState(); state.value = state.value.copy(requiresNetworkArm = false)
        val transport = Transport(); val generator = SysIdSignalGenerator(client, state, scope.backgroundScope, transport)
        replay.value = true
        assertFailsWith<IllegalStateException> { generator.startRoutine(SysIdMechanism.LINEAR, SysIdRoutine.DYNAMIC) }
        assertTrue(transport.strings.isEmpty())
    }
    @Test fun `disarm revokes local authorization before waiting for the STOP write`() = checkState {
        val state = readyState(); val transport = Transport()
        val generator = SysIdSignalGenerator(client, state, scope.backgroundScope, transport)
        generator.arm(); state.value = state.value.copy(armPhase = CalibrationArmPhase.ARMED, robotCalibrationArmed = true)
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        transport.beforeString = { _, _ -> entered.complete(Unit); release.await() }
        val stopping = scope.backgroundScope.async { generator.disarm("operator stop") }
        try {
            scope.runCurrent(); assertTrue(entered.isCompleted)
            assertFalse(state.value.robotCalibrationArmed)
            assertEquals(CalibrationArmPhase.DISARMED, state.value.armPhase)
        } finally { release.complete(Unit); stopping.await() }
    }
    @Test fun `connection loss during arm publication cannot restart lease renewal`() = checkState {
        val state = readyState(); val transport = Transport()
        val generator = SysIdSignalGenerator(client, state, scope.backgroundScope, transport)
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        transport.beforeString = { _, _ -> entered.complete(Unit); release.await() }
        val arming = scope.backgroundScope.async { generator.arm() }
        scope.runCurrent(); assertTrue(entered.isCompleted)
        generator.connectionLost(); release.complete(Unit); arming.await()
        scope.advanceTimeBy(400); scope.runCurrent()
        assertTrue(transport.doubles.isEmpty())
        assertTrue(transport.strings.none { it.first == 1016 })
    }
    @Test fun `a fresh valid acknowledgement permits one requested routine and renews its lease`() = checkState {
        acknowledge()
        assertEquals(CalibrationArmPhase.ARMED, vm.state.value.armPhase)
        vm.onIntent(SysIdIntent.StartRoutine(SysIdRoutine.DYNAMIC)); scope.runCurrent()
        assertTrue(vm.state.value.isRoutineRunning)
        assertEquals(1, transport.strings.count { it.second == "START_LINEAR_DYNAMIC" })
        scope.advanceTimeBy(200); scope.runCurrent()
        assertEquals(listOf(1.0, 2.0), transport.doubles.map { it.second })
    }
    @Test fun `a pending lease alone does not permit motion`() = checkState {
        arm()
        assertEquals(CalibrationArmPhase.ARMING, vm.state.value.armPhase)
        vm.onIntent(SysIdIntent.StartRoutine(SysIdRoutine.DYNAMIC)); scope.runCurrent()
        assertFalse(vm.state.value.isRoutineRunning)
        assertTrue(transport.strings.none { it.second.startsWith("START_") })
    }
    @Test fun `retained pre-arm truth cannot acknowledge a new request`() = checkState {
        frame("SysId/Armed", 1.0)
        arm()
        assertEquals(CalibrationArmPhase.ARMING, vm.state.value.armPhase)
        assertFalse(vm.state.value.robotCalibrationArmed)
        frame("SysId/Armed", 1.0)
        assertTrue(vm.state.value.robotCalibrationArmed)
    }
    @Test fun `invalid armed feedback revokes an established lease and sends STOP`() = checkState {
        acknowledge()
        frame("SysId/Armed", Double.NaN)
        assertFalse(vm.state.value.robotCalibrationArmed)
        assertEquals(CalibrationArmPhase.DISARMED, vm.state.value.armPhase)
        assertEquals(listOf(1015 to "STOP", 1016 to ""), transport.strings.takeLast(2))
        val count = transport.doubles.size
        scope.advanceTimeBy(400); scope.runCurrent()
        assertEquals(count, transport.doubles.size)
    }
    @Test fun `entering replay revokes arm mode capabilities and lease renewal`() = checkState {
        acknowledge(); val count = transport.doubles.size
        replay.value = true; scope.runCurrent()
        assertFalse(vm.state.value.robotCalibrationArmed)
        assertFalse(vm.state.value.calibrationModeEnabled)
        assertFalse(vm.state.value.capabilitiesKnown)
        scope.advanceTimeBy(400); scope.runCurrent()
        assertEquals(count, transport.doubles.size)
    }
    @Test fun `target replacement revokes an active lease and requires fresh observations`() = checkState {
        acknowledge(); val count = transport.doubles.size
        store.clear(); scope.runCurrent()
        assertFalse(vm.state.value.robotCalibrationArmed)
        assertFalse(vm.state.value.calibrationModeEnabled)
        assertFalse(vm.state.value.capabilitiesKnown)
        scope.advanceTimeBy(400); scope.runCurrent()
        assertEquals(count, transport.doubles.size)
    }
    @Test fun `acknowledgement arriving during final lease publication is reconciled when it succeeds`() = checkState {
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        transport.beforeDouble = { _, _ -> entered.complete(Unit); release.await() }
        arm(); assertTrue(entered.isCompleted)
        frame("SysId/Armed", 1.0)
        assertFalse(vm.state.value.robotCalibrationArmed)
        release.complete(Unit); scope.runCurrent()
        assertTrue(vm.state.value.robotCalibrationArmed)
    }
    @Test fun `publication exceptions leave calibration disarmed without a renewal job`() = checkState {
        transport.beforeString = { _, _ -> throw java.io.IOException("injected publish failure") }
        arm()
        assertFalse(vm.state.value.robotCalibrationArmed)
        assertEquals(CalibrationArmPhase.DISARMED, vm.state.value.armPhase)
        scope.advanceTimeBy(400); scope.runCurrent()
        assertTrue(transport.doubles.isEmpty())
        assertTrue(vm.state.value.errorMessage.orEmpty().contains("injected publish failure"))
    }
    @Test fun `renewal exceptions immediately revoke local authorization`() = checkState {
        acknowledge()
        transport.beforeDouble = { _, _ -> throw java.io.IOException("injected renewal failure") }
        scope.advanceTimeBy(200); scope.runCurrent()
        assertFalse(vm.state.value.robotCalibrationArmed)
        assertEquals(CalibrationArmPhase.DISARMED, vm.state.value.armPhase)
        assertTrue(vm.state.value.errorMessage.orEmpty().contains("injected renewal failure"))
    }
    @Test fun `a connected non-replay FRC platform retains robot-side authorization`() = checkState {
        vm.onIntent(SysIdIntent.ConfigurePlatform(false)); scope.runCurrent()
        frame("SysId/SupportedMechanisms", text = "LINEAR")
        vm.onIntent(SysIdIntent.StartRoutine(SysIdRoutine.QUASISTATIC)); scope.runCurrent()
        assertTrue(vm.state.value.isRoutineRunning)
        assertEquals(CalibrationArmPhase.NOT_REQUIRED, vm.state.value.armPhase)
        assertTrue(transport.strings.any { it.second == "START_LINEAR_QUASISTATIC" })
        assertTrue(transport.doubles.isEmpty())
    }
    @Test fun `clock synchronization does not discard capabilities received on the same socket`() = checkState {
        clockReady = false
        frame("SysId/ModeEnabled", 1.0)
        frame("SysId/SupportedMechanisms", text = "LINEAR")
        clockReady = true
        vm.onIntent(SysIdIntent.ArmCalibration); scope.runCurrent()
        assertTrue(vm.state.value.capabilitiesKnown)
        assertTrue(vm.state.value.calibrationModeEnabled)
        assertEquals(CalibrationArmPhase.ARMING, vm.state.value.armPhase)
    }
    @Test fun `a conflated reconnect invalidates observations even without a disconnected state event`() = checkState {
        acknowledge()
        connectionEpoch++
        frame("SysId/Armed", 1.0)
        assertFalse(vm.state.value.robotCalibrationArmed)
        assertFalse(vm.state.value.capabilitiesKnown)
        assertFalse(vm.state.value.calibrationModeEnabled)
    }
    @Test fun `late START completion cannot revive a routine after operator stop`() = checkState {
        acknowledge()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        transport.beforeString = { _, value -> if (value.startsWith("START_")) { entered.complete(Unit); release.await() } }
        vm.onIntent(SysIdIntent.StartRoutine(SysIdRoutine.DYNAMIC)); scope.runCurrent()
        assertTrue(entered.isCompleted)
        vm.onIntent(SysIdIntent.StopRoutine); scope.runCurrent()
        release.complete(Unit); scope.runCurrent()
        assertFalse(vm.state.value.isRoutineRunning)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.robotCalibrationArmed)
    }
    @Test fun `late geometric calibration completion cannot revive an aborted run`() = checkState {
        acknowledge()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        transport.beforeString = { _, value -> if (value.startsWith("START_")) { entered.complete(Unit); release.await() } }
        vm.onIntent(SysIdIntent.StartCalibration("LINEAR_DRIVE")); scope.runCurrent()
        assertTrue(entered.isCompleted)
        vm.onIntent(SysIdIntent.StopCalibration); scope.runCurrent()
        release.complete(Unit); scope.runCurrent()
        assertFalse(vm.state.value.isRoutineRunning)
        assertEquals("NONE", vm.state.value.activeCalibration)
    }
    @Test fun `operator stop revokes before suspended IO and publishes STOP only once`() = checkState {
        acknowledge()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val count = transport.strings.count { it.second == "STOP" }
        transport.beforeString = { _, value -> if (value == "STOP") { entered.complete(Unit); release.await() } }
        vm.onIntent(SysIdIntent.StopRoutine); scope.runCurrent()
        try {
            assertTrue(entered.isCompleted)
            assertFalse(vm.state.value.robotCalibrationArmed)
            assertFalse(vm.state.value.isRoutineRunning)
        } finally { release.complete(Unit); scope.runCurrent() }
        assertEquals(count + 1, transport.strings.count { it.second == "STOP" })
    }
    @Test fun `rejected STOP is reported while local authorization stays revoked`() = checkState {
        acknowledge(); transport.stringsReady = false
        vm.onIntent(SysIdIntent.StopRoutine); scope.runCurrent()
        assertFalse(vm.state.value.robotCalibrationArmed)
        assertTrue(vm.state.value.errorMessage.orEmpty().contains("not ready"))
        assertEquals(1016 to "", transport.strings.last())
    }
    @Test fun `STOP failure during feedback revocation does not kill the telemetry collector`() = checkState {
        acknowledge()
        transport.beforeString = { _, _ -> throw java.io.IOException("injected feedback STOP failure") }
        frame("SysId/Armed", 0.0)
        assertFalse(vm.state.value.robotCalibrationArmed)
        assertTrue(vm.state.value.errorMessage.orEmpty().contains("injected feedback STOP failure"))
        transport.beforeString = { _, _ -> }
        arm()
        assertEquals(CalibrationArmPhase.ARMING, vm.state.value.armPhase)
        frame("SysId/Armed", 1.0)
        assertTrue(vm.state.value.robotCalibrationArmed)
    }
    @Test fun `unrelated telemetry does not perform control identity work`() = checkState {
        Mockito.clearInvocations(client)
        frame("Drive/Velocity", 1.0)
        Mockito.verify(client, Mockito.never()).controlConnectionEpoch
    }
}
