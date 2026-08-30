package com.ares.analytics.service

import com.ares.analytics.service.log.*
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DSLogDecoderServiceTest class.
 */
class DSLogDecoderServiceTest {

    @Test
    fun `CTRE PDP currents include bits eight and nine of every ten bit field`() = runTest {
        val tempDb = File.createTempFile("dslog_ctre_bits_db", ".db")
        val databaseService = DatabaseService(tempDb.absolutePath)
        val batcher = FrameBatcher(databaseService, batchSize = 100)
        val bitBytes = ByteArray(21).apply {
            this[1] = 0x03 // channel 0 bits 8 and 9
            this[20] = 0x80.toByte() // channel 15 bit 9 (absolute bit 167)
        }
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(4)
                data.writeLong(3_801_026_800L)
                data.writeLong(0L)
                data.writeByte(100)
                data.writeByte(0)
                data.writeShort(3072)
                data.writeByte(80)
                data.writeByte(0xFF)
                data.writeByte(40)
                data.writeByte(50)
                data.writeShort(2560)
                data.write(byteArrayOf(0, 0, 0, 25))
                data.writeByte(0) // CAN ID
                data.write(bitBytes)
                data.write(byteArrayOf(0, 0, 0))
            }
        }.toByteArray()
        val log = File.createTempFile("ctre-pdp", ".dslog").apply { writeBytes(bytes) }
        try {
            DSLogDecoderService(databaseService).decode(log, "ctre-session", batcher)
            batcher.flush()

            assertEquals(
                96.0,
                databaseService.getTelemetryForKey(
                    "ctre-session",
                    "/DSLog/PowerDistributionCurrents[0]"
                ).single().value
            )
            assertEquals(
                64.0,
                databaseService.getTelemetryForKey(
                    "ctre-session",
                    "/DSLog/PowerDistributionCurrents[15]"
                ).single().value
            )
        } finally {
            databaseService.close()
            log.delete()
            tempDb.delete()
        }
    }

    @Test
    fun `truncated record is rejected before any fields are committed`() = runTest {
        val tempDb = File.createTempFile("dslog_truncated_db", ".db")
        val databaseService = DatabaseService(tempDb.absolutePath)
        val batcher = FrameBatcher(databaseService, batchSize = 100)
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(4)
                data.writeLong(1718200000L)
                data.writeLong(0L)
                data.writeByte(100)
                data.writeByte(5)
                data.writeShort(3072)
                data.writeByte(80)
                data.writeByte(0xFF)
                data.writeByte(40)
                data.writeByte(50)
                data.writeShort(2560)
                data.write(byteArrayOf(0, 0)) // partial four-byte PD header
            }
        }.toByteArray()
        val log = File.createTempFile("truncated", ".dslog").apply { writeBytes(bytes) }
        try {
            assertFailsWith<java.io.IOException> {
                DSLogDecoderService(databaseService).decode(log, "truncated-session", batcher)
            }
            batcher.flush()
            assertEquals(0, databaseService.countTelemetryFrames("truncated-session"))
        } finally {
            databaseService.close()
            log.delete()
            tempDb.delete()
        }
    }

    @Test
    /**
     * testParseDsLogNonePdType fun.
     */
    fun testParseDsLogNonePdType() = runTest {
        val tempDb = File.createTempFile("dslog_test_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val decoderService = DSLogDecoderService(databaseService)
        val batcher = FrameBatcher(databaseService, batchSize = 100)

        // Create mock binary dslog content
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // 1. Header
        dos.writeInt(4) // version
        dos.writeLong(3_801_026_800L) // LabVIEW-epoch seconds (2024-06-12 UTC)
        dos.writeLong(0L) // fractional

        // 2. Record 1: NONE PD Type
        dos.writeByte(100) // tripTimeByte
        dos.writeByte(5) // packetLossByte
        dos.writeShort(3072) // batteryVoltageShort (3072 / 256.0 = 12.0 V)
        dos.writeByte(80) // cpuUtilizationByte
        dos.writeByte(0xFF) // maskByte
        dos.writeByte(40) // canUtilizationByte
        dos.writeByte(50) // wifiDbByte
        dos.writeShort(2560) // wifiMbShort (2560 / 256.0 = 10.0 MB)

        // PD Header (4 bytes, last byte 0 = NONE)
        dos.write(byteArrayOf(0, 0, 0, 0))

        dos.flush()
        val tempDsLog = File.createTempFile("mock_dslog", ".dslog").apply { deleteOnExit() }
        FileOutputStream(tempDsLog).use { fos ->
            fos.write(baos.toByteArray())
        }
        val sessionId = "dslog-session-1"
        decoderService.decode(tempDsLog, sessionId, batcher)
        batcher.flush()

        // Verify the database has the telemetry frames
        val batteryFrames = databaseService.getTelemetryForKey(sessionId, "/DSLog/BatteryVoltage")
        assertEquals(1, batteryFrames.size)
        assertEquals(12.0, batteryFrames[0].value, 1e-6)
        val cpuFrames = databaseService.getTelemetryForKey(sessionId, "/DSLog/CPUUtilization")
        assertEquals(1, cpuFrames.size)
        assertEquals(0.4, cpuFrames[0].value, 1e-6)
        val tripTimeFrames = databaseService.getTelemetryForKey(sessionId, "/DSLog/TripTimeMS")
        assertEquals(1, tripTimeFrames.size)
        assertEquals(50.0, tripTimeFrames[0].value, 1e-6)

        tempDsLog.delete()
        tempDb.delete()
    }
}
