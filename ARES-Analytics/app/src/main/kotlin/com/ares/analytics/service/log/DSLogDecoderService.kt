package com.ares.analytics.service.log

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.FrameBatcher
import com.ares.analytics.shared.models.SessionAnnotation
import com.ares.analytics.shared.models.TelemetryFrame
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID

/**
 * Decodes FRC Driver Station binary log files (`.dslog` version 4 format) into normalized telemetry frames.
 *
 * Extracts 50Hz driver station diagnostic records, converting LabVIEW epoch timestamps into millisecond time-series values.
 * Parses RIO CPU utilization, CAN bus utilization, battery voltage, round-trip packet latency, packet loss rates, and PDP/PDH motor currents.
 *
 * ### Binary File Layout & Conversion Math:
 * - Version Header: 4-byte INT (must equal 4)
 * - LabVIEW Epoch Offset: `convertLVTime(seconds, fractional)` $\to$ Start Timestamp in $ms$
 * - Sampling Period: Fixed 20 ms per record ($50\text{ Hz}$)
 * - Battery Voltage: $V_{\text{batt}} = \frac{\text{raw\_uint16}}{256.0}\text{ Volts}$
 * - Trip Time: $t_{\text{trip}} = \text{raw\_uint8} \cdot 0.5\text{ ms}$
 * - PDP/PDH Motor Currents: Fixed-point scaling to Amperes ($A$)
 *
 * ### Thread Safety & Performance Guarantees:
 * Runs asynchronously on `Dispatchers.IO`. Streams binary file sequential bytes via [DataInputStream] into [FrameBatcher], avoiding full memory buffer allocation.
 *
 * @param databaseService Primary database service for persisting session metadata and annotations.
 *
 * @see BaseLogDecoder
 * @see WpiLogDecoder
 */
class DSLogDecoderService(private val databaseService: DatabaseService) : BaseLogDecoder() {

    /**
     * Identifies the physical power distribution module type logged within the Driver Station binary file.
     */
    enum class PowerDistributionType {
        REV, CTRE, NONE
    }

    /**
     * Decodes a binary `.dslog` file into 50Hz telemetry frame series and generates session event annotations.
     *
     * @param file Target `.dslog` file.
     * @param sessionId Session identifier string.
     * @param batcher Destination telemetry frame batcher buffer.
     */
    override suspend fun decode(
        file: File,
        sessionId: String,
        batcher: FrameBatcher
    ) {
        val dslogFile = file
        if (!dslogFile.exists()) return
        var startTimeMs = 0.0
        var recordCount = 0

        FileInputStream(dslogFile).use { fis ->
            DataInputStream(fis).use { dis ->
                val version = dis.readInt()
                if (version != 4) {
                    throw IllegalArgumentException("Unsupported dslog version: $version")
                }
                val seconds = dis.readLong()
                val fractional = dis.readLong()
                startTimeMs = convertLVTime(seconds, fractional)
                var lastBatteryVolts = 0.0

                while (dis.available() > 0) {
                    try {
                        // Read 10-byte DS status record
                        val tripTimeByte = dis.readUnsignedByte()
                        val packetLossByte = dis.readByte()
                        val batteryVoltageShort = dis.readUnsignedShort()
                        val cpuUtilizationByte = dis.readUnsignedByte()
                        val maskByte = dis.readUnsignedByte()
                        val canUtilizationByte = dis.readUnsignedByte()
                        val wifiDbByte = dis.readUnsignedByte()
                        val wifiMbShort = dis.readUnsignedShort()
                        val timestampMs = (startTimeMs + recordCount * 20).toLong()
                        val tripTimeMs = tripTimeByte * 0.5
                        val packetLoss = Math.min(Math.max(packetLossByte * 4 * 0.01, 0.0), 1.0)
                        var batteryVolts = batteryVoltageShort.toDouble() / 256.0
                        if (batteryVolts > 20.0) {
                            batteryVolts = lastBatteryVolts
                        } else {
                            lastBatteryVolts = batteryVolts
                        }
                        val cpuUtilization = cpuUtilizationByte * 0.5 * 0.01
                        val brownout = (maskByte and (1 shl 7)) == 0
                        val watchdog = (maskByte and (1 shl 6)) == 0
                        val dsTeleop = (maskByte and (1 shl 5)) == 0
                        val dsDisabled = (maskByte and (1 shl 3)) == 0
                        val robotTeleop = (maskByte and (1 shl 2)) == 0
                        val robotAuto = (maskByte and (1 shl 1)) == 0
                        val robotDisabled = (maskByte and 1) == 0
                        val canUtilization = canUtilizationByte * 0.5 * 0.01
                        val wifiDb = wifiDbByte * 0.5
                        val wifiMb = wifiMbShort.toDouble() / 256.0

                        // Power Distribution Header (4 bytes)
                        val pdHeader = ByteArray(4)
                        dis.readFully(pdHeader)
                        val pdTypeByte = pdHeader[3].toInt() and 0xFF
                        val pdType = getPDType(pdTypeByte)

                        val currents = when {
                            pdType == PowerDistributionType.REV -> {
                                dis.readUnsignedByte() // skip CAN ID
                                val bitBytes = ByteArray(27)
                                dis.readFully(bitBytes)
                                val revBooleans = BooleanArray(216)
                                var bitIdx = 0
                                for (b in bitBytes) {
                                    val byteVal = b.toInt() and 0xFF
                                    for (i in 0 until 8) {
                                        revBooleans[bitIdx++] = (byteVal and (1 shl i)) != 0
                                    }
                                }
                                val decodedCurrents = mutableListOf<Double>()
                                for (i in 0 until 20) {
                                    val readPosition = (i / 3) * 32 + (i % 3) * 10
                                    var value = 0
                                    for (j in 0 until 10) {
                                        if (revBooleans[readPosition + j]) {
                                            value = value or (1 shl j)
                                        }
                                    }
                                    decodedCurrents.add(value.toDouble() / 8.0)
                                }
                                val extraBytes = ByteArray(4)
                                dis.readFully(extraBytes)
                                for (i in 0 until 4) {
                                    decodedCurrents.add((extraBytes[i].toInt() and 0xFF).toDouble() / 16.0)
                                }

                                dis.readUnsignedByte() // skip last byte

                                decodedCurrents
                            }
                            pdType == PowerDistributionType.CTRE -> {
                                dis.readUnsignedByte() // skip CAN ID
                                val bitBytes = ByteArray(21)
                                dis.readFully(bitBytes)
                                val ctreBooleans = BooleanArray(168)
                                var bitIdx = 0
                                for (b in bitBytes) {
                                    val byteVal = b.toInt() and 0xFF
                                    for (i in 0 until 8) {
                                        ctreBooleans[bitIdx++] = (byteVal and (1 shl i)) != 0
                                    }
                                }
                                val decodedCurrents = mutableListOf<Double>()
                                for (i in 0 until 16) {
                                    val readPosition = (i / 6) * 64 + (i % 6) * 10
                                    var value = 0
                                    for (j in 0 until 10) {
                                        if (ctreBooleans[readPosition + j]) {
                                            value = value or (1 shl j)
                                        }
                                    }
                                    decodedCurrents.add(value.toDouble() / 8.0)
                                }

                                require(dis.skipBytes(3) == 3) { "Truncated CTRE power-distribution payload" }

                                decodedCurrents
                            }
                            else -> emptyList()
                        }

                        // Commit a record only after its complete variable-length PD payload was
                        // decoded. A truncated tail can no longer leave a valid-looking partial row.
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/TripTimeMS", tripTimeMs))
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/PacketLoss", packetLoss))
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/BatteryVoltage", batteryVolts))
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/CPUUtilization", cpuUtilization))
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/Status/Brownout", if (brownout) 1.0 else 0.0))
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/Status/Watchdog", if (watchdog) 1.0 else 0.0))
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/Status/DSTeleop", if (dsTeleop) 1.0 else 0.0))
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/Status/DSDisabled", if (dsDisabled) 1.0 else 0.0))
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/Status/RobotTeleop", if (robotTeleop) 1.0 else 0.0))
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/Status/RobotAuto", if (robotAuto) 1.0 else 0.0))
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/Status/RobotDisabled", if (robotDisabled) 1.0 else 0.0))
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/CANUtilization", canUtilization))
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/WifiDb", wifiDb))
                        batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/WifiMb", wifiMb))
                        currents.forEachIndexed { index, current ->
                            batcher.add(TelemetryFrame(timestampMs, sessionId, "/DSLog/PowerDistributionCurrents[$index]", current))
                        }

                        recordCount++
                    } catch (error: EOFException) {
                        throw IOException("Truncated Driver Station record $recordCount", error)
                    }
                }
            }
        }

        // Try parsing matching .dsevents file if it exists
        val eventsFile = File(dslogFile.parentFile, dslogFile.nameWithoutExtension + ".dsevents")
        if (eventsFile.exists()) {
            parseDsEvents(eventsFile, sessionId)
        }
    }

    private suspend fun parseDsEvents(
        dseventsFile: File,
        sessionId: String
    ) {
        FileInputStream(dseventsFile).use { fis ->
            DataInputStream(fis).use { dis ->
                val version = dis.readInt()
                require(version == 4) { "Unsupported dsevents version: $version" }
                val seconds = dis.readLong()
                val fractional = dis.readLong()
                val fileStartTimeMs = convertLVTime(seconds, fractional)

                while (dis.available() > 0) {
                    try {
                        val recSeconds = dis.readLong()
                        val recFractional = dis.readLong()
                        val eventTimeMs = convertLVTime(recSeconds, recFractional)
                        val length = dis.readInt()
                        if (length < 0 || length > MAX_EVENT_TEXT_BYTES || length > dis.available()) {
                            throw IOException("Invalid Driver Station event text length: $length")
                        }
                        if (length == 0) continue
                        val textBytes = ByteArray(length)
                        dis.readFully(textBytes)
                        var text = String(textBytes, Charsets.UTF_8)

                        // Filter XML tags using ARESLib DsEventLogParser
                        text = com.areslib.logging.DsEventLogParser.cleanXmlTags(text)
                        val relativeSec = (eventTimeMs - fileStartTimeMs) / 1000.0
                        val annotationIdentity = "$sessionId\u0000${eventTimeMs.toLong()}\u0000$text"
                        val annotation = SessionAnnotation(
                            annotationId = UUID.nameUUIDFromBytes(annotationIdentity.toByteArray(Charsets.UTF_8)).toString(),
                            sessionId = sessionId,
                            text = "[Event at +${String.format("%.2f", relativeSec)}s] $text",
                            createdAt = eventTimeMs.toLong(),
                            authorId = "Driver Station"
                        )
                        databaseService.insertAnnotation(annotation)
                    } catch (error: EOFException) {
                        throw IOException("Truncated Driver Station event record", error)
                    }
                }
            }
        }
    }

    private fun convertLVTime(seconds: Long, fractional: Long): Double {
        var time = -2082826800L // 1904/1/1
        time += seconds
        val fracDouble = fractional.toULong().toDouble() / Math.pow(2.0, 64.0)
        return (time.toDouble() + fracDouble) * 1000.0
    }

    private fun getPDType(id: Int): PowerDistributionType {
        return when (id) {
            33 -> PowerDistributionType.REV
            25 -> PowerDistributionType.CTRE
            else -> PowerDistributionType.NONE
        }
    }

    private companion object {
        const val MAX_EVENT_TEXT_BYTES = 1024 * 1024
    }
}
