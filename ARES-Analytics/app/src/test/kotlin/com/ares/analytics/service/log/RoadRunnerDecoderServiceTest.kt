package com.ares.analytics.service.log

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.FrameBatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoadRunnerDecoderServiceTest {

    @Test
    fun `string and enum schemas flatten into string values`() = runTest {
        withDecoderDatabase { database, tempDir ->
            val log = tempDir.resolve("text.log")
            log.writeBytes(textAndEnumLog())
            val batcher = FrameBatcher(database)

            RoadRunnerDecoderService().decode(log, "session", batcher)
            batcher.flush()

            assertEquals("ready", database.getTelemetryForKey("session", "label").single().stringValue)
            assertEquals("RUNNING", database.getTelemetryForKey("session", "state").single().stringValue)
        }
    }

    @Test
    fun `struct pose is flattened and inches are converted to meters`() = runTest {
        withDecoderDatabase { database, tempDir ->
            val log = tempDir.resolve("pose.log")
            log.writeBytes(validPoseLog(xInches = 10.0, yInches = -20.0, heading = 1.25))
            val batcher = FrameBatcher(database)

            RoadRunnerDecoderService().decode(log, "session", batcher)
            batcher.flush()

            assertEquals(0.254, database.getTelemetryForKey("session", "pose/x").single().value, 1e-12)
            assertEquals(-0.508, database.getTelemetryForKey("session", "pose/y").single().value, 1e-12)
            assertEquals(1.25, database.getTelemetryForKey("session", "pose/heading").single().value)
        }
    }

    @Test
    fun `truncated key declaration cannot emit a partial frame or stall`() = runTest {
        withDecoderDatabase { database, tempDir ->
            val log = tempDir.resolve("truncated.log")
            val bytes = ByteArrayOutputStream().also { output ->
                DataOutputStream(output).use { data ->
                    data.writeBytes("RR")
                    data.writeShort(1)
                    data.writeInt(0)
                    data.writeInt(Int.MAX_VALUE)
                }
            }.toByteArray()
            log.writeBytes(bytes)
            val batcher = FrameBatcher(database)

            assertFailsWith<IllegalArgumentException> {
                withTimeout(1_000) {
                    RoadRunnerDecoderService().decode(log, "session", batcher)
                }
            }
            batcher.flush()

            assertEquals(0, database.countTelemetryFrames("session"))
        }
    }

    @Test
    fun `concatenated segments retain a strictly increasing timeline`() = runTest {
        withDecoderDatabase { database, tempDir ->
            val log = tempDir.resolve("concatenated.log")
            log.writeBytes(timestampedSegment(1_000_000_000L) + timestampedSegment(5_000_000_000L))
            val batcher = FrameBatcher(database)

            RoadRunnerDecoderService().decode(log, "session", batcher)
            batcher.flush()

            assertEquals(
                listOf(0L, 5L, 6L, 11L),
                database.getTelemetryForKey("session", "value").map { it.timestampMs }
            )
        }
    }

    private fun validPoseLog(xInches: Double, yInches: Double, heading: Double): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeBytes("RR")
            data.writeShort(1)

            data.writeInt(0) // key/schema declaration
            data.writeUtf8String("pose")
            data.writeInt(0) // struct schema
            data.writeInt(3)
            listOf("x", "y", "heading").forEach { field ->
                data.writeUtf8String(field)
                data.writeInt(3) // double schema
            }

            data.writeInt(1) // value update
            data.writeInt(0) // first declared key ID
            data.writeDouble(xInches)
            data.writeDouble(yInches)
            data.writeDouble(heading)
        }
        return output.toByteArray()
    }

    private fun textAndEnumLog(): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeBytes("RR")
            data.writeShort(1)

            data.writeInt(0)
            data.writeUtf8String("label")
            data.writeInt(4) // string schema

            data.writeInt(0)
            data.writeUtf8String("state")
            data.writeInt(6) // enum schema
            data.writeInt(2)
            data.writeUtf8String("IDLE")
            data.writeUtf8String("RUNNING")

            data.writeInt(1)
            data.writeInt(0)
            data.writeUtf8String("ready")

            data.writeInt(1)
            data.writeInt(1)
            data.writeInt(1)
        }
        return output.toByteArray()
    }

    private fun timestampedSegment(originNanos: Long): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeBytes("RR")
            data.writeShort(1)

            data.writeInt(0)
            data.writeUtf8String("TIMESTAMP")
            data.writeInt(2) // long schema
            data.writeInt(0)
            data.writeUtf8String("value")
            data.writeInt(3) // double schema

            data.writeInt(1)
            data.writeInt(0)
            data.writeLong(originNanos)
            data.writeInt(1)
            data.writeInt(1)
            data.writeDouble(1.0)
            data.writeInt(1)
            data.writeInt(0)
            data.writeLong(originNanos + 5_000_000L)
            data.writeInt(1)
            data.writeInt(1)
            data.writeDouble(2.0)
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.writeUtf8String(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private suspend fun withDecoderDatabase(
        block: suspend (DatabaseService, java.io.File) -> Unit
    ) {
        val tempDir = Files.createTempDirectory("ares-roadrunner-decoder").toFile()
        val database = DatabaseService(tempDir.resolve("telemetry.duckdb").absolutePath)
        try {
            block(database, tempDir)
        } finally {
            database.close()
            tempDir.deleteRecursively()
        }
    }
}
