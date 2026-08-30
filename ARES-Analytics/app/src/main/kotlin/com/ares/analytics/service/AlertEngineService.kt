package com.ares.analytics.service

import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.ThresholdRule
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * High-performance real-time **Emergency Fault Alert & Diagnostic Engine**.
 *
 * Continuously evaluates high-rate NetworkTables NT4 telemetry streams against multi-signal hardware diagnostic
 * rules. Automatically triggers pop-up overlays, persistent database records, and urgent dual-tone audio beeps
 * upon fault detection.
 *
 * ### Diagnostic Failure Equations & Thresholds:
 * - **1.0s Moving Average Current Window ($N = 20$ samples):**
 *   $$\bar{I}_{\text{avg}} = \frac{1}{N} \sum_{i=1}^{N} I_i \quad (N = 20 \text{ samples at } 20\text{ Hz})$$
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
 * - **Power ($P$):** Normalized motor duty cycle $[-1.0, 1.0]$ or Volts ($V$)
 * - **Current ($I$):** Amperes ($A$)
 * - **Velocity ($\omega$):** Encoder ticks/s or meters per second ($m/s$)
 * - **Temperature ($T$):** Degrees Celsius ($^\circ\text{C}$)
 * - **Loop Latency ($t_{\text{loop}}$):** Milliseconds ($ms$)
 * - **Control Flow:** Zero nested `if` statements enforced via clean, argument-less `when` expressions.
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
    private val thresholdsPath: String = AppDataPaths.file("thresholds.json").path
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    /** Rules are indexed by transport-normalized topic while preserving the configured key in alerts. */
    private val rules = ConcurrentHashMap<String, ThresholdRule>()
    /** Last values are isolated per recording so a new session cannot inherit stale hardware state. */
    private val recentValues = ConcurrentHashMap<String, ConcurrentHashMap<String, Double>>()
    /** One-second current windows, isolated by session and motor. */
    private val currentBuffers = ConcurrentHashMap<String, ArrayDeque<TimedCurrentSample>>()
    /** One-second loop-overrun windows, isolated by recording session. */
    private val loopTimeBuffers = ConcurrentHashMap<String, ArrayDeque<TimedLoopSample>>()
    private val motorNames = listOf("fl", "fr", "rl", "rr", "bl", "br")

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val audioMutex = kotlinx.coroutines.sync.Mutex()

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

    private var engineJob: Job? = null
    private var lastBeepTime = 0L

    init {
        loadRules()
        startEngine()
    }

    private fun loadRules() {
        val file = File(thresholdsPath)
        val defaultRules = listOf(
            ThresholdRule(TelemetryMetricCatalog.BATTERY_VOLTAGE.canonicalKey, "Low Battery Voltage (<10.5V)", minValue = 10.5, audibleAlert = true),
            ThresholdRule("Drive/EKF_Drift_X", "High EKF X Drift (>0.20m)", maxValue = 0.20, audibleAlert = true),
            ThresholdRule("Drive/EKF_Drift_Y", "High EKF Y Drift (>0.20m)", maxValue = 0.20, audibleAlert = true),
            ThresholdRule(TelemetryMetricCatalog.LOOP_TIME.canonicalKey, "Robot Loop Time Spike (>25ms)", maxValue = 25.0, audibleAlert = false),
            ThresholdRule("Hardware/I2C/Timeouts", "WARNING: FTC I2C / Lynx Bus Timeout!", maxValue = 0.5, audibleAlert = true)
        )

        val motorRules = motorNames.flatMap { motor ->
            listOf(
                ThresholdRule("Hardware/Motors/$motor/Stall", "CRITICAL: Motor '$motor' Mechanical Binding / Stall!", maxValue = 0.5, audibleAlert = true),
                ThresholdRule("Hardware/Motors/$motor/Disconnected", "WARNING: Motor '$motor' Cable Disconnected!", maxValue = 0.5, audibleAlert = true)
            )
        }

        val allDefaults = defaultRules + motorRules

        when {
            !file.exists() -> {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(allDefaults))
                allDefaults.forEach(::registerRule)
            }
            else -> {
                runCatching {
                    val loaded = json.decodeFromString<List<ThresholdRule>>(file.readText())
                    loaded.forEach(::registerRule)
                }.onFailure {
                    allDefaults.forEach(::registerRule)
                }
            }
        }
    }

    /**
     * Starts the non-blocking telemetry evaluation coroutine collector.
     */
    fun startEngine() {
        engineJob?.cancel()

        engineJob = serviceScope.launch {
            nt4ClientService.telemetryFlow.collect { frame ->
                val normalizedKey = normalizeTopic(frame.key)
                recentValues.getOrPut(frame.sessionId) { ConcurrentHashMap() }[normalizedKey] = frame.value
                evaluateFrame(frame)
                evaluateCompositeRules(frame, normalizedKey)
            }
        }
    }

    /**
     * Cancels the active telemetry evaluation coroutine job.
     */
    fun stop() {
        engineJob?.cancel()
    }

    /**
     * Final teardown — cancels the process-lifetime [serviceScope] (which also cancels
     * [engineJob] and any in-flight audible-alert coroutines). Use from
     * [com.ares.analytics.di.ServiceRegistry] shutdown; [stop] is for pause/restart since
     * it leaves [serviceScope] reusable.
     */
    fun dispose() {
        engineJob?.cancel()
        serviceScope.cancel()
    }

    /**
     * Single-key threshold rule evaluation using clean zero-nested `when` flow.
     *
     * @param frame Incoming telemetry frame containing topic key and double value.
     */
    private suspend fun evaluateFrame(frame: TelemetryFrame) {
        val normalizedKey = normalizeTopic(frame.key)
        // Loop timing needs temporal evidence; evaluating its ordinary max rule here would create
        // an intrusive banner for a single harmless scheduler/GC sample.
        if (normalizedKey in TelemetryMetricCatalog.LOOP_TIME.keys ||
            frame.key.trimStart('/') in TelemetryMetricCatalog.LOOP_TIME.keys
        ) return
        val rule = rules[normalizedKey] ?: return
        val value = frame.value

        val minVal = rule.minValue
        val maxVal = rule.maxValue
        val violatesMin = minVal != null && value < minVal
        val violatesMax = maxVal != null && value > maxVal
        val isViolating = violatesMin || violatesMax

        // Atomic lookup-and-update: the find + compute + commit happens inside a single
        // _alerts.update lambda so a concurrent triage/clear/resolve cannot interleave and
        // clobber a just-added or just-triaged alert.
        val outcome = commitAlertTransition { current ->
            val existingAlert = current.values.firstOrNull {
                it.sessionId == frame.sessionId && normalizeTopic(it.ruleKey) == normalizeTopic(rule.key) && !it.triaged
            }
            when {
                isViolating && existingAlert == null -> AlertOutcome(
                    alert = AlertRecord(
                        alertId = UUID.randomUUID().toString(),
                        sessionId = frame.sessionId,
                        ruleKey = rule.key,
                        triggerTimestampMs = frame.timestampMs,
                        peakValue = value,
                        triaged = false
                    ),
                    shouldBeep = rule.audibleAlert
                )
                isViolating && existingAlert?.resolveTimestampMs != null -> AlertOutcome(
                    alert = existingAlert.copy(
                        resolveTimestampMs = null,
                        durationMs = 0L,
                        peakValue = maxOf(existingAlert.peakValue, value)
                    ),
                    shouldBeep = rule.audibleAlert
                )
                isViolating && existingAlert != null -> AlertOutcome(
                    alert = existingAlert.copy(
                        peakValue = if (rule.maxValue != null) maxOf(existingAlert.peakValue, value) else minOf(existingAlert.peakValue, value)
                    ),
                    shouldBeep = false
                )
                !isViolating && existingAlert?.resolveTimestampMs == null && existingAlert != null -> AlertOutcome(
                    alert = existingAlert.copy(
                        resolveTimestampMs = frame.timestampMs,
                        durationMs = frame.timestampMs - existingAlert.triggerTimestampMs
                    ),
                    shouldBeep = false
                )
                else -> null
            }
        } ?: return
        persistAlert(outcome.alert)
        if (outcome.shouldBeep) triggerAudibleAlert()
    }

    /**
     * Multi-signal composite diagnostic evaluation (Stalls, Cable Disconnects, Over-Temp, CAN Errors, Vision Latency).
     *
     * @param frame Current telemetry frame being processed.
     */
    private suspend fun evaluateCompositeRules(frame: TelemetryFrame, normalizedFrameKey: String) {
        if (!isCompositeSignal(normalizedFrameKey)) return
        val ts = frame.timestampMs
        val sessionId = frame.sessionId
        val sessionValues = recentValues[sessionId] ?: return

        // 1. Motor Stalling & Disconnect Check across all motors using 1.0-second moving average
        if (normalizedFrameKey.startsWith("Hardware/Motors/")) motorNames.forEach { m ->
            val pwr = kotlin.math.abs(sessionValues["Hardware/Motors/$m/Power"] ?: sessionValues["Hardware/Motors/$m/Voltage"] ?: 0.0)
            val vel = kotlin.math.abs(sessionValues["Hardware/Motors/$m/Velocity"] ?: 0.0)
            val currentKey = "Hardware/Motors/$m/CurrentAmps"
            val current = sessionValues[currentKey] ?: 0.0

            val bufferKey = "$sessionId\u0000$m"
            val buf = currentBuffers.getOrPut(bufferKey) { ArrayDeque() }
            if (normalizedFrameKey == currentKey) {
                buf.addLast(TimedCurrentSample(ts, current))
            }
            while (buf.isNotEmpty() && ts - buf.first().timestampMs > CURRENT_WINDOW_MS) {
                buf.removeFirst()
            }
            val hasCurrentSample = buf.isNotEmpty()
            val avgCurrent = if (hasCurrentSample) buf.sumOf { it.amps } / buf.size else 0.0

            val stallKey = "Hardware/Motors/$m/Stall"
            val disconnectKey = "Hardware/Motors/$m/Disconnected"

            val isStalled = hasCurrentSample && pwr > 0.35 && vel < 5.0 && avgCurrent > 5.0
            val stallRule = rules.getOrPut(stallKey) { ThresholdRule(stallKey, "CRITICAL: Motor '$m' Mechanical Binding / Stall!", maxValue = 0.5, audibleAlert = true) }
            evaluateRuleState(stallKey, isStalled, if (isStalled) 1.0 else 0.0, ts, sessionId, stallRule)

            val isDisconnected = hasCurrentSample && pwr > 0.35 && vel < 5.0 && avgCurrent < 0.1 && avgCurrent >= 0.0
            val disconnectRule = rules.getOrPut(disconnectKey) { ThresholdRule(disconnectKey, "WARNING: Motor '$m' Cable Disconnected!", maxValue = 0.5, audibleAlert = true) }
            evaluateRuleState(disconnectKey, isDisconnected, if (isDisconnected) 1.0 else 0.0, ts, sessionId, disconnectRule)
        }

        // 2. CAN Bus Utilization & Error Check
        if ((normalizedFrameKey.startsWith("Diagnostics/CANBus/") && normalizedFrameKey.endsWith("/Utilization")) ||
            normalizedFrameKey == "Hardware/CAN/Utilization" || normalizedFrameKey == "CAN/Utilization"
        ) {
        val canEntry = sessionValues.entries
            .filter { it.key.startsWith("Diagnostics/CANBus/") && it.key.endsWith("/Utilization") }
            .maxByOrNull { it.value }
        val canKey = canEntry?.key ?: "Diagnostics/CANBus/Utilization"
        val canUtil = canEntry?.value
            ?: sessionValues["Hardware/CAN/Utilization"]
            ?: sessionValues["CAN/Utilization"]
            ?: 0.0
        val canThreshold = if (canUtil <= 1.5) 0.85 else 85.0
        val isCanHigh = canUtil > canThreshold
        val canRule = rules.getOrPut(canKey) {
            ThresholdRule(canKey, "CRITICAL: CAN Bus Utilization High!", maxValue = canThreshold, audibleAlert = true)
        }
        evaluateRuleState(canKey, isCanHigh, canUtil, ts, sessionId, canRule)
        }

        // 3. FTC I2C / Lynx Timeout Check
        if (normalizedFrameKey == "Hardware/I2C/Timeouts") {
        val i2cTimeouts = sessionValues["Hardware/I2C/Timeouts"] ?: 0.0
        val isI2cError = i2cTimeouts > 0.0
        val i2cRule = rules.getOrPut("Hardware/I2C/Timeouts") { ThresholdRule("Hardware/I2C/Timeouts", "WARNING: FTC I2C / Lynx Bus Timeout!", maxValue = 0.5, audibleAlert = true) }
        evaluateRuleState("Hardware/I2C/Timeouts", isI2cError, i2cTimeouts, ts, sessionId, i2cRule)
        }

        // 4. Over-Temperature Thermal Alert (>70C)
        if (normalizedFrameKey.startsWith("Hardware/Motors/")) motorNames.forEach { m ->
            val tempC = sessionValues["Hardware/Motors/$m/TempC"] ?: 0.0
            val isOverheat = tempC > 70.0
            val tempKey = "Hardware/Motors/$m/TempC"
            val tempRule = rules.getOrPut(tempKey) { ThresholdRule(tempKey, "WARNING: Motor '$m' Overheating (>70°C)!", maxValue = 70.0, audibleAlert = true) }
            evaluateRuleState(tempKey, isOverheat, tempC, ts, sessionId, tempRule)
        }

        // 5. Limelight Vision Frame Rate Stale Alert (<5 FPS)
        if (normalizedFrameKey == "Vision/Limelight/FPS") {
        val limelightFps = sessionValues["Vision/Limelight/FPS"] ?: 30.0
        val isVisionStale = limelightFps < 5.0
        val visionRule = rules.getOrPut("Vision/Limelight/FPS") { ThresholdRule("Vision/Limelight/FPS", "WARNING: Limelight Camera Frame Rate Low (<5 FPS)!", minValue = 5.0, audibleAlert = false) }
        evaluateRuleState("Vision/Limelight/FPS", isVisionStale, limelightFps, ts, sessionId, visionRule)
        }

        // 6. Control Loop Latency Alert (>25ms)
        if (normalizedFrameKey in TelemetryMetricCatalog.LOOP_TIME.keys) {
        val loopMs = frame.value
        val loopBuffer = loopTimeBuffers.getOrPut(sessionId) { ArrayDeque() }
        loopBuffer.addLast(TimedLoopSample(ts, loopMs))
        while (loopBuffer.isNotEmpty() && ts - loopBuffer.first().timestampMs > LOOP_OVERRUN_WINDOW_MS) {
            loopBuffer.removeFirst()
        }
        val overrunCount = loopBuffer.count { it.durationMs > LOOP_OVERRUN_THRESHOLD_MS }
        val peakLoopMs = loopBuffer.maxOfOrNull { it.durationMs } ?: loopMs
        val isLoopSlow = loopMs >= LOOP_SEVERE_THRESHOLD_MS || overrunCount >= LOOP_OVERRUN_SAMPLE_COUNT
        val loopKey = TelemetryMetricCatalog.LOOP_TIME.canonicalKey
        val loopRule = rules.getOrPut(loopKey) {
            ThresholdRule(
                loopKey,
                "WARNING: Repeated Control Loop Overruns (3 samples >25ms in 1s)!",
                maxValue = LOOP_OVERRUN_THRESHOLD_MS,
                audibleAlert = false,
            )
        }
        evaluateRuleState(loopKey, isLoopSlow, if (isLoopSlow) peakLoopMs else loopMs, ts, sessionId, loopRule)
        }
    }

    private fun isCompositeSignal(key: String): Boolean =
        key.startsWith("Hardware/Motors/") ||
            key.startsWith("Diagnostics/CANBus/") ||
            key == "Hardware/CAN/Utilization" ||
            key == "CAN/Utilization" ||
            key == "Hardware/I2C/Timeouts" ||
            key == "Vision/Limelight/FPS" ||
            key in TelemetryMetricCatalog.LOOP_TIME.keys

    /**
     * Pure zero-nested helper to transition custom alert state. Lookup + update are atomic.
     */
    private suspend fun evaluateRuleState(
        key: String,
        isViolating: Boolean,
        value: Double,
        ts: Long,
        sessionId: String,
        rule: ThresholdRule
    ) {
        val outcome = commitAlertTransition { current ->
            val existingAlert = current.values.firstOrNull {
                it.sessionId == sessionId && normalizeTopic(it.ruleKey) == normalizeTopic(key) && !it.triaged
            }
            when {
                isViolating && existingAlert == null -> AlertOutcome(
                    alert = AlertRecord(
                        alertId = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        ruleKey = key,
                        triggerTimestampMs = ts,
                        peakValue = value,
                        triaged = false
                    ),
                    shouldBeep = rule.audibleAlert
                )
                isViolating && existingAlert?.resolveTimestampMs != null -> AlertOutcome(
                    alert = existingAlert.copy(
                        resolveTimestampMs = null,
                        durationMs = 0L,
                        peakValue = maxOf(existingAlert.peakValue, value)
                    ),
                    shouldBeep = rule.audibleAlert
                )
                !isViolating && existingAlert?.resolveTimestampMs == null && existingAlert != null -> AlertOutcome(
                    alert = existingAlert.copy(
                        resolveTimestampMs = ts,
                        durationMs = ts - existingAlert.triggerTimestampMs
                    ),
                    shouldBeep = false
                )
                else -> null
            }
        } ?: return
        persistAlert(outcome.alert)
        if (outcome.shouldBeep) triggerAudibleAlert()
    }

    /**
     * The result of computing an alert transition inside the atomic [commitAlertTransition]
     * lambda: the new [alert] to store and whether an audible beep should fire after commit.
     */
    private class AlertOutcome(val alert: AlertRecord, val shouldBeep: Boolean)

    private data class TimedCurrentSample(val timestampMs: Long, val amps: Double)
    private data class TimedLoopSample(val timestampMs: Long, val durationMs: Double)

    /**
     * Atomically applies an alert transition. [compute] receives the current snapshot and
     * returns the new [AlertRecord] to put (plus beep intent), or null for no-op. The
     * lookup + map mutation happen inside a single [MutableStateFlow.update] CAS loop so
     * concurrent mutators (evaluate / triage / clear) cannot interleave.
     */
    private inline fun commitAlertTransition(compute: (Map<String, AlertRecord>) -> AlertOutcome?): AlertOutcome? {
        var outcome: AlertOutcome? = null
        _alerts.update { current ->
            val result = compute(current)
            if (result != null) {
                outcome = result
                current.toMutableMap().apply { put(result.alert.alertId, result.alert) }
            } else {
                current
            }
        }
        return outcome
    }

    private suspend fun persistAlert(alert: AlertRecord) {
        if (alert.sessionId != "live-telemetry") {
            databaseService.insertAlert(alert)
        }
    }

    /**
     * Marks an active alert as triaged/acknowledged by the driver or pit crew.
     *
     * @param alertId Unique UUID string of the target alert.
     */
    suspend fun triageAlert(alertId: String) {
        val triaged = commitAlertTransition { current ->
            val alert = current[alertId] ?: return@commitAlertTransition null
            AlertOutcome(alert = alert.copy(triaged = true), shouldBeep = false)
        } ?: return
        persistAlert(triaged.alert)
    }

    /**
     * Clears all triaged and resolved alerts from the active alert banner queue.
     */
    suspend fun clearAllResolvedAlerts() {
        _alerts.update { current ->
            current.filterValues { !it.triaged || it.resolveTimestampMs == null }
        }
    }

    private fun triggerAudibleAlert() {
        val now = System.currentTimeMillis()
        if (now - lastBeepTime > 1500) {
            lastBeepTime = now
            serviceScope.launch(Dispatchers.IO) {
                if (audioMutex.tryLock()) {
                    try {
                        runCatching {
                            playBeepTone(1000f, 100)
                            delay(50)
                            playBeepTone(1200f, 150)
                        }
                    } finally {
                        audioMutex.unlock()
                    }
                }
            }
        }
    }

    private fun playBeepTone(frequency: Float, durationMs: Int) {
        val sampleRate = 8000f
        val numSamples = (durationMs * sampleRate / 1000).toInt()
        val buf = ByteArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = i / (sampleRate / frequency) * 2.0 * Math.PI
            buf[i] = (Math.sin(angle) * 127.0).toInt().toByte()
        }
        val format = AudioFormat(sampleRate, 8, 1, true, true)
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format)
        line.start()
        line.write(buf, 0, buf.size)
        line.drain()
        line.close()
    }

    /**
     * Retrieves human-readable display name for a rule key.
     *
     * @param key NetworkTables rule topic key.
     * @return Human-readable display string.
     */
    fun getRuleDisplayName(key: String): String {
        return rules[normalizeTopic(key)]?.displayName ?: key
    }

    private fun registerRule(rule: ThresholdRule) {
        rules[normalizeTopic(rule.key)] = rule
    }

    private fun normalizeTopic(key: String): String = TelemetryMetricCatalog.normalizeTopic(key)

    private companion object {
        const val CURRENT_WINDOW_MS = 1_000L
        const val LOOP_OVERRUN_WINDOW_MS = 1_000L
        const val LOOP_OVERRUN_THRESHOLD_MS = 25.0
        const val LOOP_SEVERE_THRESHOLD_MS = 100.0
        const val LOOP_OVERRUN_SAMPLE_COUNT = 3
    }
}
