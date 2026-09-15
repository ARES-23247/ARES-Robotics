package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.*
import com.ares.analytics.viewmodel.*
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.ares.biobuzz.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BiobuzzDashboardTelemetryTest {
    private class Client : Nt4ClientService(org.mockito.Mockito.mock(DatabaseService::class.java)) {
        val connection = MutableStateFlow(true)
        override val isConnected get() = connection
    }

    private suspend fun publish(client: Nt4ClientService, value: String, time: Long = 100000) {
        client.handleIncomingText("""[{"method":"announce","params":{"name":"/ARES/BIOBUZZ/State","id":51,"type":"string"}}]""", "t","s","r")
        client.handleIncomingText("""[{"topic":51,"time":$time,"value":${Gson().toJson(value)}}]""", "t","s","r")
    }

    private fun frame() = BiobuzzFrame(1, 8, "ftc-2026-2027-biobuzz", 0,
        BiobuzzSnapshot(1.0, 0.0, 0.5, true, listOf(BallKind.POLLEN), emptyList(),
            List(4) { FlowerView(0.0, it * 0.1, listOf(BallKind.RED_NECTAR, BallKind.POLLEN)) },
            listOf(HiveView(0.0,0.3,true,0.5,1,true,2,listOf(emptyList(),listOf(BallKind.POLLEN))),
                HiveView(0.0,-0.3,false,-0.5,0,false,0,listOf(emptyList(),emptyList()))), 5,5))

    @Test fun `actual NT4 string snapshot updates existing field subscriber and replay preserves containers`() = runTest {
        val database = DatabaseService(File.createTempFile("biobuzz-telemetry", ".duckdb").absolutePath)
        val nt4 = Nt4ClientService(database)
        try {
            val live = MutableStateFlow(LivePoseState())
            FieldTopicSubscriber(nt4, backgroundScope, MutableStateFlow(FieldViewerState()), live, UnconfinedTestDispatcher(testScheduler))
            runCurrent()
            val json = BiobuzzTelemetry.encode(frame())
            nt4.handleIncomingText("""[{"method":"announce","params":{"name":"/ARES/BIOBUZZ/State","id":51,"type":"string"}}]""", "t","s","r")
            nt4.handleIncomingText("""[{"topic":51,"time":100000,"value":${Gson().toJson(json)}}]""", "t","s","r")
            runCurrent()
            assertEquals(frame(), live.value.biobuzz)
            assertEquals(frame(), ReplayFrame(100, emptyMap(), mapOf(BiobuzzTelemetry.TOPIC to json)).toReplayPoseState().biobuzz)
        } finally { nt4.disposeAndJoin(); database.close() }
    }

    @Test fun `invalid complete snapshots never leak a partially decoded hive or inventory`() {
        val valid = BiobuzzTelemetry.encode(frame())
        assertNotNull(BiobuzzTelemetry.decode(valid))
        for (json in listOf("{}", "null", "x".repeat(64_001),
            valid.replace("\"upward\":1", "\"upward\":9"),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"POLLEN\"", "\"UNKNOWN\""))) assertNull(BiobuzzTelemetry.decode(json))
    }

    @Test fun `reopened dashboard restores game state after unrelated telemetry overflow and clears malformed replacement`() = runTest {
        val client = Client()
        try {
            publish(client, BiobuzzTelemetry.encode(frame()))
            repeat(5000) { index ->
                client.telemetryStore.accept(com.ares.analytics.shared.models.TelemetryFrame(
                    100, "live", "Unrelated/Noise", index.toDouble(), null, 100000))
            }
            val live = MutableStateFlow(LivePoseState())
            FieldTopicSubscriber(client, backgroundScope, MutableStateFlow(FieldViewerState()), live,
                UnconfinedTestDispatcher(testScheduler))
            runCurrent()
            assertEquals(frame(), live.value.biobuzz)
            publish(client, "{}", 200000)
            runCurrent()
            assertNull(live.value.biobuzz)
        } finally { client.disposeAndJoin() }
    }

    @Test fun `game state clears on disconnect and target switch and needs a new observation after reconnect`() = runTest {
        val client = Client()
        try {
            val live = MutableStateFlow(LivePoseState())
            FieldTopicSubscriber(client, backgroundScope, MutableStateFlow(FieldViewerState()), live,
                UnconfinedTestDispatcher(testScheduler))
            publish(client, BiobuzzTelemetry.encode(frame())); runCurrent()
            assertEquals(frame(), live.value.biobuzz)
            client.connection.value = false; runCurrent()
            assertNull(live.value.biobuzz)
            client.connection.value = true; runCurrent()
            assertNull(live.value.biobuzz)
            publish(client, BiobuzzTelemetry.encode(frame().copy(sequence = 9)), 200000); runCurrent()
            assertEquals(9L, live.value.biobuzz?.sequence)
            client.isReplayActive.value = true; runCurrent()
            assertNull(live.value.biobuzz)
            client.isReplayActive.value = false; runCurrent()
            assertEquals(9L, live.value.biobuzz?.sequence)
            client.clearLiveTargetState(); runCurrent()
            assertNull(live.value.biobuzz)
        } finally { client.disposeAndJoin() }
    }
}
