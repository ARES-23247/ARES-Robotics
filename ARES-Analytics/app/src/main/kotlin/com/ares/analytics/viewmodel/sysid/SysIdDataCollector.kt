package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.service.AlignedDataRow
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.SysIdService
import com.ares.analytics.service.AutoTunerService
import com.ares.analytics.service.TuningApplyPhase
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.viewmodel.SysIdState
import com.areslib.control.assist.SysIdMechanism
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.TreeMap

/** Collects complete live samples; publishes bounded previews and a full snapshot at completion. */
class SysIdDataCollector(
    private val nt4ClientService: Nt4ClientService,
    private val sysIdService: SysIdService,
    private val autoTunerService: AutoTunerService,
    private val _state: MutableStateFlow<SysIdState>,
    private val scope: CoroutineScope,
    private val regressionSolver: SysIdRegressionSolver,
    private val maxSamples: Int = 20_000,
    private val previewLimit: Int = 1_000,
    private val onRoutineCompleted: suspend () -> Unit = {}
) {
    init { require(maxSamples > 0 && previewLimit > 0 && previewLimit <= maxSamples) }
    private val lock = Any()
    private val assembler = SysIdSampleAssembler()
    private val rows = TreeMap<Long, DoubleArray>()
    private var collectorJob: Job? = null
    private var runKind: String? = null
    private var runSession: String? = null
    private var runMechanism: SysIdMechanism? = null
    private var failed = false
    private var generation = 0L
    private var lastPreviewMs: Long? = null
    private data class Completed(val generation: Long, val kind: String, val rows: List<DoubleArray>,
        val samples: List<AlignedDataRow>, val mechanism: SysIdMechanism)

    fun startCollecting() = synchronized(lock) {
        if (collectorJob?.isActive == true) return@synchronized
        collectorJob = scope.launch {
            nt4ClientService.telemetryFlow.collect { frame ->
                when {
                    frame.key == "SysId/Status" -> handleStatus(frame)
                    frame.key == "SysId/Data" || frame.key.startsWith("SysId/Data/") -> {
                        val stopGeneration = synchronized(lock) { if (acceptData(frame)) generation else null }
                        if (stopGeneration != null) requestStop(stopGeneration)
                    }
                }
            }
        }
    }

    /** Called before requesting a new routine; invalidates analysis awaiting a stop callback. */
    fun clearBuffer() = synchronized(lock) {
        generation++
        assembler.clear(); rows.clear()
        runKind = null; runSession = null; runMechanism = null; failed = false; lastPreviewMs = null
        _state.update { it.copy(liveSamples=emptyList(), liveCalibrationData=emptyList(), summary=null, tuningRecommendation=null) }
    }

    private fun acceptData(frame: TelemetryFrame): Boolean {
        val state = _state.value
        if (failed || !state.isRobotConnected || !state.isRoutineRunning || nt4ClientService.isReplayActive.value) return false
        val kind = if (isGeometricCalibration(state.activeCalibration)) state.activeCalibration else "SYSID"
        if (runKind != null && runKind != kind) return fail("Calibration kind changed during collection; start a new run")
        if (runSession != null && runSession != frame.sessionId) return fail("Telemetry session changed during collection; start a new run")
        if (runMechanism != null && runMechanism != state.selectedMechanism) return fail("Mechanism changed during collection; start a new run")
        runKind = kind; runSession = frame.sessionId; runMechanism = state.selectedMechanism
        val row = assembler.accept(frame, kind) ?: return false
        val time = row[0].toLong()
        val existing = rows[time]
        if (existing != null) {
            if (!existing.contentEquals(row)) return fail("Conflicting SysId samples share a timestamp; collect a new run")
            return false
        }
        if (rows.size == maxSamples) return fail("SysId exceeded $maxSamples samples; run stopped without fitting truncated data")
        rows[time] = row
        val previousPreview = lastPreviewMs
        if (rows.size == 1 || previousPreview == null || frame.timestampMs - previousPreview >= 100) {
            lastPreviewMs = frame.timestampMs
            if (!isGeometricCalibration(kind)) {
                val preview = rows.descendingMap().values.take(previewLimit).asReversed().map(::motorSample)
                _state.update { it.copy(liveSamples=preview) }
            }
        }
        return false
    }

    private fun fail(message: String): Boolean {
        failed = true
        assembler.clear()
        _state.update { it.copy(errorMessage=message, isRoutineRunning=false, isLoading=false,
            summary=null, tuningRecommendation=null, recommendedPinpointXOffsetMm=null,
            recommendedPinpointYOffsetMm=null, recommendedTrackWidthMeters=null,
            recommendedVisionStdDevsX=null, recommendedVisionStdDevsY=null,
            recommendedVisionStdDevsHeading=null, recommendedTicksPerMeter=null) }
        return true
    }

    private suspend fun handleStatus(frame: TelemetryFrame) {
        val status = frame.stringValue ?: return
        val completed = synchronized(lock) {
            if (failed || !_state.value.isRobotConnected || nt4ClientService.isReplayActive.value) return@synchronized null
            if (runSession != null && runSession != frame.sessionId) return@synchronized null
            if (status != "NONE") {
                if (status != "QUASISTATIC" && status != "DYNAMIC" && !isGeometricCalibration(status)) return@synchronized null
                // A late status must not revive a locally stopped/disarmed run.
                if (_state.value.isRoutineRunning || _state.value.isLoading) {
                    _state.update { it.copy(isRoutineRunning=true, activeCalibration=status) }
                }
                return@synchronized null
            }
            val kind = runKind ?: if (_state.value.isRoutineRunning || _state.value.isLoading) {
                if (isGeometricCalibration(_state.value.activeCalibration)) _state.value.activeCalibration else "SYSID"
            } else return@synchronized null
            val snapshot = rows.values.toList() // Transfer ownership; publication below copies mutable arrays.
            val samples = if (isGeometricCalibration(kind)) emptyList() else snapshot.map(::motorSample)
            val result = Completed(generation, kind, snapshot, samples, runMechanism ?: _state.value.selectedMechanism)
            runKind = null; runSession = null; runMechanism = null; rows.clear(); assembler.clear(); lastPreviewMs = null
            _state.update { it.copy(isRoutineRunning=false, isLoading=false,
                liveSamples=samples,
                liveCalibrationData=if (isGeometricCalibration(kind)) snapshot.map { row -> row.clone() } else emptyList()) }
            result
        } ?: return
        if (!requestStop(completed.generation)) return
        synchronized(lock) {
            if (completed.generation != generation || completed.mechanism != _state.value.selectedMechanism) return
            if (isGeometricCalibration(completed.kind)) {
                regressionSolver.runCalibrationAnalysis(completed.kind, completed.rows)
            } else if (completed.rows.isNotEmpty()) {
                val samples = completed.samples
                val summary = sysIdService.analyzeRawData(samples)
                val recommendation = autoTunerService.analyzeSamples(completed.mechanism, samples, "live-nt4")
                _state.update { it.copy(summary=summary, tuningRecommendation=recommendation) }
            } else {
                _state.update { it.copy(errorMessage="No complete SysId samples were collected", summary=null, tuningRecommendation=null) }
            }
        }
        val recommendation = _state.value.tuningRecommendation
        if (recommendation != null && completed.generation == synchronized(lock) { generation } &&
            autoTunerService.applyState.value.phase == TuningApplyPhase.APPLIED_AWAITING_VALIDATION) {
            autoTunerService.validateOrRollback(recommendation)
        }
    }

    private suspend fun requestStop(expectedGeneration: Long): Boolean {
        if (synchronized(lock) { generation != expectedGeneration }) return false
        return try {
            onRoutineCompleted()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            synchronized(lock) {
                if (generation == expectedGeneration) {
                    _state.update { it.copy(errorMessage="Could not complete SysId stop: ${error.message}", isRoutineRunning=false, isLoading=false) }
                }
            }
            false
        }
    }

    private fun motorSample(row: DoubleArray) = AlignedDataRow(row[0].toLong(), row[1], row[3], row[4])
    fun parseLogFile(fileContent: String): List<AlignedDataRow> = SysIdLogParser.parse(fileContent)
}
