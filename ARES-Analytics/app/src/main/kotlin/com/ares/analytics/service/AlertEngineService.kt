package com.ares.analytics.service

import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.ThresholdRule
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.League
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance real-time **Emergency Fault Alert & Diagnostic Engine**.
 *
 * Continuously evaluates high-rate NetworkTables NT4 telemetry streams against multi-signal hardware diagnostic
 * rules. Automatically triggers pop-up overlays, persistent database records, and urgent dual-tone audio beeps
 * upon fault detection.
 *
 * ### Diagnostic Failure Equations & Thresholds:
 * - **1.0s sample-average current window (at most 512 retained samples):**
 *   $$\bar{I}_{\text{avg}} = \frac{1}{N} \sum_{i=1}^{N} I_i$$
 *
 * - **Motor Mechanical Binding / Loose Screw Stall:**
 *   $$\text{Stall} \iff |P| > 0.35 \;\land\; |\omega| < 5.0\text{ ticks/s} \;\land\; \bar{I}_{\text{avg}} > 5.0\text{ Amps}$$
 *
 * - **Motor Cable Disconnection / Blown Breaker:**
 *   $$\text{Disconnected} \iff |P| > 0.35 \;\land\; |\omega| < 5.0\text{ ticks/s} \;\land\; 0.0\text{A} \le \bar{I}_{\text{avg}} < 0.1\text{ Amps}$$
 *
 * - **Battery Brownout Risk:**
 *   $$\text{Brownout} \iff V_{\text{battery}} < 10.5\text{ Volts}$$
 *
 * - **Motor Over-Temperature Thermal Alert:**
 *   $$\text{Overheat} \iff T_{\text{motor}} > 70.0^\circ\text{C}$$
 *
 * - **Limelight Stale Vision Frame Rate Alert:**
 *   $$\text{VisionStale} \iff f_{\text{limelight}} < 5.0\text{ Hz}$$
 *
 * - **Control Loop Latency Overrun Alert:**
 *   $$\text{LoopOverrun} \iff (N_{t>25ms,1s} \ge 3) \lor (t_{\text{loop}} \ge 100ms)$$
 *   One scheduler/GC outlier is retained for analysis but does not interrupt the driver.
 *
 * ### Physical Units & Guarantees:
 * - **Power ($P$):** Normalized motor duty cycle $[-1.0, 1.0]$
 * - **Current ($I$):** Amperes ($A$)
 * - **Velocity ($\omega$):** Encoder ticks/s (legacy MotorIO topics)
 * - **Temperature ($T$):** Degrees Celsius ($^\circ\text{C}$)
 * - **Loop Latency ($t_{\text{loop}}$):** Milliseconds ($ms$)
 *
 * @param databaseService DuckDB persistent logging service for historical run analytics.
 * @param nt4ClientService Active NetworkTables NT4 websocket streaming client.
 * @param thresholdsPath File path to persistent JSON threshold configuration file.
 * @see Nt4ClientService
 * @see AlertRecord
 * @see ThresholdRule
 */
class AlertEngineService(
    private val databaseService: DatabaseService,
    private val nt4ClientService: Nt4ClientService,
    private val thresholdsPath: String = AppDataPaths.file("thresholds.json").path,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Rules are indexed by transport-normalized topic while preserving the configured key in alerts. */
    private val rules = ConcurrentHashMap<String, ThresholdRule>()
    /** Fixed-size loop evidence, isolated by recording and actual source key. */
    private val loopTimeBuffers = ConcurrentHashMap<RuleIdentity, LoopOverrunWindow>()
    private val motorNames = listOf("fl", "fr", "rl", "rr", "bl", "br")
    private val motorRoutes = buildMap {
        motorNames.forEach { motor ->
            put("Hardware/Motors/$motor/Power", motor to MotorFeedbackSignal.POWER)
            put("Hardware/Motors/$motor/Velocity", motor to MotorFeedbackSignal.VELOCITY)
            put("Hardware/Motors/$motor/CurrentAmps", motor to MotorFeedbackSignal.CURRENT)
        }
    }
    private val motorTemperatureKeys = motorNames.mapTo(HashSet()) { "Hardware/Motors/$it/TempC" }
    // These keys belong to locally derived diagnoses, never independent incoming measurements.
    private val motorDiagnosticKeys = buildSet {
        motorNames.forEach { motor ->
            add("Hardware/Motors/$motor/Stall")
            add("Hardware/Motors/$motor/Disconnected")
        }
    }
    private data class MotorBinding(val state: MotorDiagnosticState, val stall: ThresholdRule, val disconnected: ThresholdRule)
    private val motorDiagnostics = HashMap<RuleIdentity, MotorBinding>()

    private val serviceScope = CoroutineScope(dispatcher + SupervisorJob())
    private val audioNotifier = AlertAudioNotifier()
    private val persistence = AlertPersistenceWriter(serviceScope, databaseService::insertAlert)
    val persistenceStatus: StateFlow<AlertPersistenceStatus> = persistence.status
    @Volatile private var disposed = false

    // Active alert state: AlertId -> AlertRecord
    private val _alerts = MutableStateFlow<Map<String, AlertRecord>>(emptyMap())

    /**
     * Observable stream of active and historical [AlertRecord]s sorted descending by trigger timestamp.
     *
     * [distinctUntilChanged] suppresses identical sorted lists so AlertPanel /
     * CriticalAlertOverlay only recompose when the alert *content* actually changes — the
     * engine can mutate `_alerts` at tens of Hz (e.g. peak-value refreshes) but most frames
     * produce a structurally identical list.
     */
    val alerts: StateFlow<List<AlertRecord>> = _alerts
        .map { it.values.toList().sortedByDescending { r -> r.triggerTimestampMs } }
        .distinctUntilChanged()
        .stateIn(serviceScope, SharingStarted.Eagerly, emptyList())

    private val transitionMutex = Mutex()
    private data class RuleIdentity(val sessionId: String, val key: String)
    // Guard occurrence chronology, including healthy samples that do not create a record.
    private val lastEvaluationTimes = HashMap<RuleIdentity, Long>()
    private data class SourceOrder(val timestampUs: Long, val sampleOrder: Long)
    private val lastSourceOrders = HashMap<RuleIdentity, SourceOrder>()
    private var evaluationTargetEpoch: Long? = null
    private var engineJob: Job? = null
    private val platformThresholds = PlatformAlertThresholds()

    val configurationWarning: String?

    init {
        configurationWarning = loadRules()
        startEngine()
    }

    private fun loadRules(): String? {
        val defaultRules = listOf(
            ThresholdRule(TelemetryMetricCatalog.BATTERY_VOLTAGE.canonicalKey, "Low Battery Voltage (<10.5V)", minValue = 10.5, audibleAlert = true),
            ThresholdRule("Drive/EKF_Drift_X", "High EKF X Drift (>0.20m)", maxValue = 0.20, audibleAlert = true),
            ThresholdRule("Drive/EKF_Drift_Y", "High EKF Y Drift (>0.20m)", maxValue = 0.20, audibleAlert = true),
            ThresholdRule(TelemetryMetricCatalog.LOOP_TIME.canonicalKey, "Robot Loop Time Spike (>25ms)", maxValue = LoopOverrunWindow.MODERATE_THRESHOLD_MS, audibleAlert = false),
            ScalarDiagnosticRules.defaultRule(ScalarDiagnosticKind.I2C_TIMEOUTS, ScalarDiagnosticRules.I2C_KEY)
        )

        val motorRules = motorNames.flatMap { motor ->
            listOf(
                ThresholdRule("Hardware/Motors/$motor/Stall", "CRITICAL: Motor '$motor' Mechanical Binding / Stall!", maxValue = 0.5, audibleAlert = true),
                ThresholdRule("Hardware/Motors/$motor/Disconnected", "WARNING: Motor '$motor' Cable Disconnected!", maxValue = 0.5, audibleAlert = true)
            )
        }

        val allDefaults = defaultRules + motorRules

        val loaded = AlertRuleConfiguration.load(thresholdsPath, allDefaults)
        loaded.rules.forEach(::registerRule)
        return loaded.warning
    }

    /**
     * Starts the non-blocking telemetry evaluation coroutine collector.
     */
    fun startEngine() {
        if (disposed) return
        engineJob?.cancel()

        val store = nt4ClientService.telemetryStore
        val retained = IdentityHashMap<TelemetryPublication, Boolean>()
        store.publications.replayCache.forEach { retained[it] = true }
        engineJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineScope {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    store.targetEpochs.collect {
                        transitionMutex.withLock { selectTargetEpoch(store.currentTargetEpoch()) }
                    }
                }
                store.publications.collect { publication ->
                    transitionMutex.withLock {
                        ensureActive()
                        val epoch = store.currentTargetEpoch()
                        selectTargetEpoch(epoch)
                        val frame = publication.frame
                        if (publication.targetEpoch != epoch || retained.containsKey(publication)) return@withLock
                        val key = normalizeTopic(frame.key)
                        if (key in motorDiagnosticKeys) return@withLock
                        val scalarKind = ScalarDiagnosticRules.kind(key)
                        if (!rules.containsKey(key) && !isDiagnosticSignal(key, scalarKind)) return@withLock
                        val identity = RuleIdentity(frame.sessionId, key)
                        val previous = lastSourceOrders[identity]
                        if (previous != null && (frame.timestampUs < previous.timestampUs ||
                            (frame.timestampUs == previous.timestampUs && frame.sampleOrder <= previous.sampleOrder))) return@withLock
                        lastSourceOrders[identity] = SourceOrder(frame.timestampUs, frame.sampleOrder)
                        motorRoutes[key]?.let { evaluateMotorFrame(frame, it) }
                        if (key in motorTemperatureKeys) rules.getOrPut(key) {
                            ThresholdRule(key, "WARNING: Motor overheating (>70°C)!", maxValue = 70.0, audibleAlert = true)
                        }
                        if (frame.stringValue != null || !frame.value.isFinite()) return@withLock
                        if (scalarKind != null) {
                            if (!ScalarDiagnosticRules.accepts(scalarKind, frame.value)) return@withLock
                            rules.getOrPut(key) { ScalarDiagnosticRules.defaultRule(scalarKind, key) }
                        }
                        evaluateFrame(frame, key)
                        evaluateLoopFrame(frame, key, identity)
                    }
                }
            }
        }
    }

    /** Called while holding transitionMutex. Old persisted evidence remains in the database. */
    private fun selectTargetEpoch(epoch: Long) {
        if (evaluationTargetEpoch == epoch) return
        evaluationTargetEpoch = epoch
        motorDiagnostics.clear()
        loopTimeBuffers.clear()
        lastEvaluationTimes.clear()
        lastSourceOrders.clear()
        _alerts.value = emptyMap()
    }

    /** Serialize policy changes with evaluation; only fresh observations or target resets change evidence. */
    suspend fun configureRobotContext(league: League, xrpBrownoutThresholdVolts: Double? = null) {
        transitionMutex.withLock {
            if (!disposed) platformThresholds.configure(league, xrpBrownoutThresholdVolts)
        }
    }

    /**
     * Cancels the active telemetry evaluation coroutine job.
     */
    fun stop() {
        engineJob?.cancel()
    }

    /**
     * Immediate teardown — cancels [serviceScope], queued writes, [engineJob] and audio.
     * Normal application owners must use [disposeAndJoin] before closing storage.
     * This method is for emergency/disposable owners; [stop] supports pause/restart since
     * it leaves [serviceScope] reusable.
     */
    fun dispose() {
        disposed = true
        engineJob?.cancel()
        persistence.close()
        serviceScope.cancel()
    }

    /** Stop evaluation, drain accepted alert updates, then join before the database closes. */
    suspend fun disposeAndJoin(timeoutMs: Long = 5_000L): Boolean {
        require(timeoutMs > 0)
        transitionMutex.withLock { disposed = true }
        engineJob?.cancelAndJoin()
        if (!persistence.finish(timeoutMs)) return false
        serviceScope.coroutineContext[Job]?.cancelAndJoin()
        return true
    }

    /**
     * Single-key threshold rule evaluation using clean zero-nested `when` flow.
     *
     * @param frame Incoming telemetry frame containing topic key and double value.
     */
    private suspend fun evaluateFrame(frame: TelemetryFrame, normalizedKey: String) {
        // Loop timing needs temporal evidence; evaluating its ordinary max rule here would create
        // an intrusive banner for a single harmless scheduler/GC sample.
        if (normalizedKey in TelemetryMetricCatalog.LOOP_TIME.keys) return
        val value = frame.value
        if (normalizedKey in TelemetryMetricCatalog.BATTERY_VOLTAGE.keys && value < 0.0) return
        val rule = platformThresholds.effectiveRule(normalizedKey, rules[normalizedKey] ?: return)

        evaluateRuleState(rule.key, AlertRuleSemantics.violates(value, rule), value, frame.timestampMs, frame.sessionId, rule)
    }

    /** Loop diagnostics require temporal evidence; scalar source rules have already run once. */
    private suspend fun evaluateLoopFrame(frame: TelemetryFrame, normalizedFrameKey: String, sourceIdentity: RuleIdentity) {
        val ts = frame.timestampMs
        val sessionId = frame.sessionId
        // Loop aliases retain source provenance and cannot resolve or count for one another.
        if (normalizedFrameKey in TelemetryMetricCatalog.LOOP_TIME.keys) {
            val loopKey = normalizedFrameKey
            val loopRule = rules.getOrPut(loopKey) {
                rules[TelemetryMetricCatalog.LOOP_TIME.canonicalKey]?.copy(key = loopKey)
                    ?: ThresholdRule(
                        loopKey,
                        "WARNING: Repeated Control Loop Overruns (3 samples >25ms in 1s)!",
                        maxValue = LoopOverrunWindow.MODERATE_THRESHOLD_MS,
                        audibleAlert = false,
                    )
            }
            // Accepted loop configurations are the fixed policy or explicitly boundless/disabled.
            if (loopRule.maxValue == null) return
            val window = loopTimeBuffers.getOrPut(sourceIdentity) { LoopOverrunWindow() }
            if (!window.accept(frame.timestampUs, frame.value)) return
            evaluateRuleState(loopRule.key, window.isSlow, window.peakMs, ts, sessionId, loopRule)
        }
    }

    private suspend fun evaluateMotorFrame(frame: TelemetryFrame, route: Pair<String, MotorFeedbackSignal>) {
        val binding = motorDiagnostics.getOrPut(RuleIdentity(frame.sessionId, route.first)) {
            val stallKey = "Hardware/Motors/${route.first}/Stall"
            val disconnectedKey = "Hardware/Motors/${route.first}/Disconnected"
            MotorBinding(MotorDiagnosticState(),
                rules.getOrPut(stallKey) { ThresholdRule(stallKey, "CRITICAL: Motor '${route.first}' Mechanical Binding / Stall!", maxValue = 0.5, audibleAlert = true) },
                rules.getOrPut(disconnectedKey) { ThresholdRule(disconnectedKey, "WARNING: Motor '${route.first}' Cable Disconnected!", maxValue = 0.5, audibleAlert = true) })
        }
        val value = if (frame.stringValue == null) frame.value else Double.NaN
        if (!binding.state.accept(route.second, frame.timestampUs, value) || !binding.state.hasEvidence) return
        val state = binding.state
        val stallValue = if (state.isStalled) 1.0 else 0.0
        val disconnectedValue = if (state.isDisconnected) 1.0 else 0.0
        evaluateRuleState(binding.stall.key, AlertRuleSemantics.violates(stallValue, binding.stall), stallValue,
            state.timestampUs / 1_000L, frame.sessionId, binding.stall)
        evaluateRuleState(binding.disconnected.key, AlertRuleSemantics.violates(disconnectedValue, binding.disconnected), disconnectedValue,
            state.timestampUs / 1_000L, frame.sessionId, binding.disconnected)
    }

    private fun isDiagnosticSignal(key: String, scalarKind: ScalarDiagnosticKind?): Boolean =
        key in motorRoutes || key in motorTemperatureKeys || scalarKind != null ||
            key in TelemetryMetricCatalog.LOOP_TIME.keys

    /**
     * Transition one rule under transitionMutex, then enqueue persistence without waiting for IO.
     */
    private suspend fun evaluateRuleState(
        key: String,
        isViolating: Boolean,
        value: Double,
        ts: Long,
        sessionId: String,
        rule: ThresholdRule
    ) {
        currentCoroutineContext().ensureActive()
        if (evaluationTargetEpoch != nt4ClientService.telemetryStore.currentTargetEpoch()) return
        if (!value.isFinite() || ts < 0L) return
        val identity = RuleIdentity(sessionId, normalizeTopic(key))
        val previousTime = lastEvaluationTimes[identity]
        if (previousTime != null && ts < previousTime) return
        lastEvaluationTimes[identity] = ts
        val outcome = commitAlertTransition(_alerts) { current ->
            alertTransition(current, rule, key, sessionId, ts, value, isViolating)
        } ?: return
        persistAlert(outcome.alert)
        currentCoroutineContext().ensureActive()
        if (outcome.shouldBeep && evaluationTargetEpoch == nt4ClientService.telemetryStore.currentTargetEpoch()) triggerAudibleAlert()
    }

    private fun persistAlert(alert: AlertRecord) = persistence.submit(alert)

    /**
     * Marks an active alert as triaged/acknowledged by the driver or pit crew.
     *
     * @param alertId Unique UUID string of the target alert.
     */
    suspend fun triageAlert(alertId: String) {
        transitionMutex.withLock {
            if (disposed) return@withLock
            val triaged = commitAlertTransition(_alerts) { current ->
                val alert = current[alertId] ?: return@commitAlertTransition null
                AlertOutcome(alert = alert.copy(triaged = true), shouldBeep = false)
            } ?: return
            persistAlert(triaged.alert)
        }
    }

    /**
     * Clears all triaged and resolved alerts from the active alert banner queue.
     */
    suspend fun clearAllResolvedAlerts() {
        transitionMutex.withLock {
            _alerts.update { current ->
                current.filterValues { !it.triaged || it.resolveTimestampMs == null }
            }
        }
    }

    private fun triggerAudibleAlert() = audioNotifier.trigger(serviceScope)

    /**
     * Retrieves human-readable display name for a rule key.
     *
     * @param key NetworkTables rule topic key.
     * @return Human-readable display string.
     */
    fun getRuleDisplayName(key: String): String {
        val normalized = normalizeTopic(key)
        return rules[normalized]?.let { platformThresholds.effectiveRule(normalized, it).displayName } ?: key
    }

    private fun registerRule(rule: ThresholdRule) {
        rules[normalizeTopic(rule.key)] = rule
    }

    private fun normalizeTopic(key: String): String = TelemetryMetricCatalog.normalizeTopic(key)

}
