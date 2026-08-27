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
import kotlinx.serialization.json.*
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
                            val arr = dataBuffer.getOrPut(t) { DoubleArray(6) }
                            if (idx < arr.size) {
                                arr[idx] = frame.value
                            }
                            val expectedMaxIdx = when (_state.value.activeCalibration) {
                                "PINPOINT_SPIN", "VISION_CALIBRATION" -> 3
                                "LINEAR_DRIVE" -> 4
                                "TRACK_WIDTH_SPIN" -> 5
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

    fun parseLogFile(fileContent: String): List<AlignedDataRow> {
        val rows = mutableListOf<AlignedDataRow>()
        val lines = fileContent.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }

        if (lines.none()) return emptyList()
        val firstLine = lines.first()
        if (firstLine.startsWith("{")) {
            // JSONL Format
            for (line in lines) {
                try {
                    val element = Json.parseToJsonElement(line).jsonObject

                    // Scenario A: Check if the log contains the flattened SysId/Data array directly
                    val sysIdData = element["SysId/Data"] ?: element["SysId_Data"] ?: element["sysid_data"]
                    if (sysIdData != null) {
                        val arr = sysIdData.jsonArray.mapNotNull { it.jsonPrimitive.doubleOrNull }
                        if (arr.size >= 5) {
                            rows.add(AlignedDataRow(
                                timestampMs = arr[0].toLong(),
                                voltage = arr[1],
                                velocity = arr[3],
                                accel = arr[4]
                            ))
                            continue
                        }
                    }

                    // Scenario B: Check for individual fields
                    val t = element["TimestampMs"]?.jsonPrimitive?.longOrNull
                        ?: element["timestamp"]?.jsonPrimitive?.longOrNull
                        ?: element["time"]?.jsonPrimitive?.longOrNull
                        ?: continue
                    val volt = element["voltage"]?.jsonPrimitive?.doubleOrNull
                        ?: element["Voltage"]?.jsonPrimitive?.doubleOrNull
                        ?: element["Drive/Voltage"]?.jsonPrimitive?.doubleOrNull
                        ?: continue
                    val vel = element["velocity"]?.jsonPrimitive?.doubleOrNull
                        ?: element["Velocity"]?.jsonPrimitive?.doubleOrNull
                        ?: element["Drive/Velocity"]?.jsonPrimitive?.doubleOrNull
                        ?: continue
                    val accel = element["acceleration"]?.jsonPrimitive?.doubleOrNull
                        ?: element["Acceleration"]?.jsonPrimitive?.doubleOrNull
                        ?: element["Drive/Acceleration"]?.jsonPrimitive?.doubleOrNull
                        ?: 0.0

                    rows.add(AlignedDataRow(t, volt, vel, accel))
                } catch (_: Exception) {
                    // Skip an individual malformed JSON row and continue collecting usable samples.
                }
            }
        } else {
            // CSV Format
            val header = firstLine.split(",").map { it.trim().removeSurrounding("\"").lowercase() }
            val tCol = header.indexOfFirst { it.contains("time") || it == "t" || it == "ts" }
            val voltCol = header.indexOfFirst { it.contains("volt") || it == "v" || it == "u" }
            val velCol = header.indexOfFirst { it.contains("vel") || it.contains("speed") || it == "omega" }
            val accelCol = header.indexOfFirst { it.contains("accel") || it == "a" }

            if (voltCol != -1 && velCol != -1) {
                var index = 0
                for (line in lines.drop(1)) {
                    try {
                        val cols = line.split(",").map { it.trim().removeSurrounding("\"") }
                        val t = if (tCol != -1 && tCol < cols.size) cols[tCol].toLongOrNull() ?: index.toLong() else index.toLong()
                        val volt = cols[voltCol].toDoubleOrNull() ?: continue
                        val vel = cols[velCol].toDoubleOrNull() ?: continue
                        val accel = if (accelCol != -1 && accelCol < cols.size) cols[accelCol].toDoubleOrNull() ?: 0.0 else 0.0

                        rows.add(AlignedDataRow(t, volt, vel, accel))
                        index++
                    } catch (_: Exception) {
                        // Skip an individual malformed CSV row and continue collecting usable samples.
                    }
                }
            }
        }

        // If acceleration is missing (all 0.0), approximate as numerical derivative
        if (rows.isNotEmpty() && rows.all { it.accel == 0.0 }) {
            val approxRows = mutableListOf<AlignedDataRow>()
            val sorted = rows.sortedBy { it.timestampMs }
            for (i in 0 until sorted.size) {
                val current = sorted[i]
                val accel = if (i == 0) 0.0 else {
                    val prev = sorted[i - 1]
                    val dt = (current.timestampMs - prev.timestampMs) / 1000.0
                    if (dt > 1e-4) (current.velocity - prev.velocity) / dt else 0.0
                }
                approxRows.add(current.copy(accel = accel))
            }
            return approxRows
        }

        return rows
    }
}
