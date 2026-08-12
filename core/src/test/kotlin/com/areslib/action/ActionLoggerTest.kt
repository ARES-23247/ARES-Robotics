package com.areslib.action

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation3d
import com.areslib.pathing.Path
import com.areslib.pathing.PathPoint
import com.areslib.state.VisionMeasurement
import com.areslib.util.RobotClock
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ActionLoggerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `mutable action is snapshotted and file becomes visible only after drain`() {
        val logger = ActionLogger(
            runId = "run\"quoted",
            robotId = "robot",
            mode = "Tele/op",
            logDirectory = tempDir
        )
        val source = RobotAction.JoystickDriveIntent(
            targetXVelocity = 1.0,
            targetYVelocity = 2.0,
            targetAngularVelocity = 3.0,
            timestampMs = 123L
        )

        logger.logAction(source)
        source.targetXVelocity = 99.0

        val active = tempDir.listFiles()?.singleOrNull { it.name.endsWith(".jsonl.active") }
        assertNotNull(active)
        assertFalse(tempDir.listFiles().orEmpty().any { it.extension == "jsonl" })

        logger.stop()

        assertFalse(active.exists())
        val completed = tempDir.listFiles()?.singleOrNull { it.extension == "jsonl" }
        assertNotNull(completed)
        val root = JsonParser.parseString(completed.readText().trim()).asJsonObject
        assertEquals("run\"quoted", root["run_id"].asString)
        assertEquals("Tele/op", root["op_mode"].asString)
        assertEquals(ActionReplay.SCHEMA_VERSION, root["schema_version"].asInt)
        assertEquals(1.0, root["payload"].asJsonObject["targetXVelocity"].asDouble, 1e-9)
        assertEquals(0L, logger.droppedActionCount)
        assertTrue(completed.name.contains("Tele_op"))
        assertTrue(completed.name.contains("run_quoted"))
    }

    @Test
    fun `blocked writer persists enqueue-time vision and path payloads`() {
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val blockOnce = AtomicBoolean(true)
        val logger = ActionLogger(runId = "snapshot", logDirectory = tempDir)
        logger.beforeWriteForTest = {
            if (blockOnce.compareAndSet(true, false)) {
                writerEntered.countDown()
                releaseWriter.await()
            }
        }

        val measurement = VisionMeasurement(
            timestampMs = 41L,
            targetPose = Pose3d(translation = Translation3d(1.0, 2.0, 3.0)),
            tagId = 7,
            ambiguity = 0.05
        )
        val measurements = mutableListOf(measurement)
        val point = PathPoint(Pose2d(4.0, 5.0, Rotation2d(0.6)), 2.0)
        val points = mutableListOf(point)

        logger.logAction(RobotAction.VisionMeasurementsReceived(measurements, 42L))
        logger.logAction(RobotAction.SwitchPath(Path(points), timestampMs = 43L))
        assertTrue(writerEntered.await(5, TimeUnit.SECONDS))

        measurement.tagId = 99
        measurement.targetPose.translation.x = 88.0
        measurements.clear()
        point.pose = Pose2d(77.0, 5.0, Rotation2d())
        point.velocityMps = 66.0
        points.clear()
        releaseWriter.countDown()
        logger.stop()

        val completed = tempDir.listFiles().orEmpty().single { it.extension == "jsonl" }
        val records = completed.readLines().map { JsonParser.parseString(it).asJsonObject }
        val vision = records[0]["payload"].asJsonObject["measurements"].asJsonArray[0].asJsonObject
        assertEquals(7, vision["tagId"].asInt)
        assertEquals(1.0, vision["targetPose"].asJsonObject["translation"].asJsonObject["x"].asDouble)
        val loggedPoint = records[1]["payload"].asJsonObject["path"].asJsonObject["points"].asJsonArray[0].asJsonObject
        assertEquals(4.0, loggedPoint["pose"].asJsonObject["x"].asDouble)
        assertEquals(2.0, loggedPoint["velocityMps"].asDouble)
    }

    @Test
    fun `same epoch run and mode reserve distinct completed files without overwrite`() {
        RobotClock.useMockTime(0L)
        try {
            val first = ActionLogger(runId = "run/id", mode = "Tele/op", logDirectory = tempDir)
            val second = ActionLogger(runId = "run/id", mode = "Tele/op", logDirectory = tempDir)
            first.logAction(RobotAction.SetAlliance(com.areslib.state.Alliance.RED, 1L))
            second.logAction(RobotAction.SetAlliance(com.areslib.state.Alliance.BLUE, 2L))
            first.stop()
            second.stop()

            val completed = tempDir.listFiles().orEmpty().filter { it.extension == "jsonl" }
            assertEquals(2, completed.size)
            assertNotEquals(completed[0].name, completed[1].name)
            assertTrue(completed.all { it.name.contains("run_id") && it.name.contains("Tele_op") })
            assertEquals(
                setOf(1L, 2L),
                completed.map { file ->
                    JsonParser.parseString(file.readText().trim()).asJsonObject
                        .getAsJsonObject("payload")["timestampMs"].asLong
                }.toSet()
            )
            assertFalse(tempDir.listFiles().orEmpty().any { it.name.endsWith(".active") })
        } finally {
            RobotClock.useSystemTime()
        }
    }
}
