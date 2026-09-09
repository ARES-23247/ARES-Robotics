package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.service.AlignedDataRow
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.SysIdService
import com.ares.analytics.service.AutoTunerService
import com.ares.analytics.service.TuningApplyPhase
import com.ares.analytics.viewmodel.SysIdState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Collects live or imported SysId samples and normalizes them into regression-ready rows. */
class SysIdDataCollector(
    private val nt4ClientService: Nt4ClientService,
    private val sysIdService: SysIdService,
    private val autoTunerService: AutoTunerService,
    private val _state: MutableStateFlow<SysIdState>,
    private val scope: CoroutineScope,
    private val regressionSolver: SysIdRegressionSolver,
    private val onRoutineCompleted: suspend () -> Unit = {}
) {
    private val dataBuffer = ConcurrentHashMap<Long, DoubleArray>()

    fun startCollecting() {
        // Collect live streaming data from the robot
        scope.launch {
            nt4ClientService.telemetryFlow.collect { frame ->
                when {
                    frame.key == "SysId/Status" -> {
                        val status = frame.stringValue ?: ""
                        val wasRunning = _state.value.isRoutineRunning
                        val isRunning = status.isNotEmpty() && status != "NONE"
                        val prevCalibration = _state.value.activeCalibration

                        _state.update {
                            it.copy(
                                isRoutineRunning = isRunning,
                                activeCalibration = if (isRunning) status else it.activeCalibration
                            )
                        }

                        if (wasRunning && !isRunning) {
                            onRoutineCompleted()
                            // Routine/Calibration just completed!
                            val finalCalibration = prevCalibration
                            _state.update { it.copy(isLoading = false) }
                            if (finalCalibration == "PINPOINT_SPIN" || finalCalibration == "TRACK_WIDTH_SPIN" ||
                                finalCalibration == "VISION_CALIBRATION" || finalCalibration == "LINEAR_DRIVE") {
                                regressionSolver.runCalibrationAnalysis(finalCalibration, _state.value.liveCalibrationData)
                            } else {
                                val samples = _state.value.liveSamples
                                if (samples.isNotEmpty()) {
                                    val summary = sysIdService.analyzeRawData(samples)
                                    val recommendation = autoTunerService.analyzeSamples(
                                        mechanism = _state.value.selectedMechanism,
                                        samples = samples,
                                        source = "live-nt4"
                                    )
                                    _state.update { it.copy(summary = summary, tuningRecommendation = recommendation) }
                                    if (recommendation != null &&
                                        autoTunerService.applyState.value.phase == TuningApplyPhase.APPLIED_AWAITING_VALIDATION
                                    ) {
                                        autoTunerService.validateOrRollback(recommendation)
                                    }
                                }
                            }
                        }
                    }
                    frame.key.startsWith("SysId/Data/") -> {
                        val idx = frame.key.removePrefix("SysId/Data/").toIntOrNull()
                        if (idx != null) {
                            val t = frame.timestampMs
                            val arr = dataBuffer.getOrPut(t) { DoubleArray(7) }
                            if (idx in arr.indices) {
                                arr[idx] = frame.value
                            }
                            val expectedMaxIdx = when (_state.value.activeCalibration) {
                                "PINPOINT_SPIN", "VISION_CALIBRATION" -> 3
                                "LINEAR_DRIVE" -> 4
                                "TRACK_WIDTH_SPIN" -> 6
                                else -> 4
                            }

                            if (idx == expectedMaxIdx) {
                                val completedArr = dataBuffer[t]
                                if (completedArr != null) {
                                    val sample = AlignedDataRow(
                                        timestampMs = completedArr[0].toLong(),
                                        voltage = completedArr[1],
                                        velocity = completedArr[3],
                                        accel = completedArr[4]
                                    )
                                    _state.update {
                                        it.copy(
                                            liveSamples = it.liveSamples + sample,
                                            liveCalibrationData = it.liveCalibrationData + listOf(completedArr.clone())
                                        )
                                    }
                                    if (dataBuffer.size > 500) {
                                        val minT = dataBuffer.keys.minOrNull() ?: 0L
                                        dataBuffer.remove(minT)
                                    }
                                }
                            }
                        }
                    }
                    frame.key == "SysId/Data" -> {
                        val stringVal = frame.stringValue
                        if (stringVal != null) {
                            val parts = stringVal.split("|").mapNotNull { it.toDoubleOrNull() }
                            if (parts.size >= 5) {
                                val sample = AlignedDataRow(
                                    timestampMs = parts[0].toLong(),
                                    voltage = parts[1],
                                    velocity = parts[3],
                                    accel = parts[4]
                                )
                                _state.update { it.copy(liveSamples = it.liveSamples + sample) }
                            }
                        }
                    }
                }
            }
        }
    }

    fun clearBuffer() {
        dataBuffer.clear()
    }

    fun parseLogFile(fileContent: String): List<AlignedDataRow> = SysIdLogParser.parse(fileContent)
}
