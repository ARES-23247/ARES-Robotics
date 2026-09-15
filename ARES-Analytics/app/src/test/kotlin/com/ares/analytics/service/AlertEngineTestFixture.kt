package com.ares.analytics.service

import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.ThresholdRule
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mockito.Mockito.*
import java.nio.file.Files
import kotlin.test.assertTrue

/** Real publication/rule/persistence pipeline; unrelated storage, sockets and audio stay local fakes. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class AlertEngineTestFixture(
    private val scope: TestScope,
    val engine: AlertEngineService,
    private val store: TelemetryStore,
    val persisted: List<AlertRecord>,
) {
    val alerts get() = engine.alerts.value
    suspend fun emit(frame: TelemetryFrame) { store.accept(frame); scope.runCurrent() }
    suspend fun triage(alertId: String) { engine.triageAlert(alertId); scope.runCurrent() }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.withAlertEngine(
    rules: List<ThresholdRule>? = null,
    block: suspend AlertEngineTestFixture.() -> Unit,
) {
    val directory = Files.createTempDirectory("alert-engine-fixture")
    val thresholds = directory.resolve("thresholds.json")
    var engine: AlertEngineService? = null
    try {
        if (rules != null) Files.writeString(thresholds, Json.encodeToString(rules))
        val persisted = mutableListOf<AlertRecord>()
        val database = mock(DatabaseService::class.java, org.mockito.stubbing.Answer { call ->
            if (call.method.name == "insertAlert") persisted += call.getArgument<AlertRecord>(0)
            org.mockito.Answers.RETURNS_DEFAULTS.answer(call)
        })
        val store = TelemetryStore()
        val client = mock(Nt4ClientService::class.java)
        `when`(client.telemetryStore).thenReturn(store)
        `when`(client.telemetryFlow).thenReturn(store.updates)
        val activeEngine = AlertEngineService(database, client, thresholds.toString(), StandardTestDispatcher(testScheduler), audioPlayer = {})
        engine = activeEngine
        runCurrent()
        AlertEngineTestFixture(this, activeEngine, store, persisted).block()
    } finally {
        try {
            withContext(NonCancellable) {
                engine?.let {
                    try { assertTrue(it.disposeAndJoin(), "fixture must drain accepted alert writes") }
                    finally { it.dispose(); runCurrent() }
                }
            }
        } finally {
            try { Files.deleteIfExists(thresholds) }
            finally { Files.delete(directory) }
        }
    }
}
