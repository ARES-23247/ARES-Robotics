package com.areslib.action

import com.areslib.state.SubsystemState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class TimestampAuditState(val value: Int) : SubsystemState

class ReplayCodecBoundaryAuditTest {
    @TempDir lateinit var directory: File
    private fun reject(json: String) {
        val file = File(directory, "invalid.jsonl").also { it.writeText(json) }
        assertFailsWith<ActionReplayException> { ActionReplay.parseActions(file) }
    }

    @Test fun `missing timestamp does not invent a replay epoch`() {
        reject("""{"schema_version":1,"type":"SetAlliance","payload":{"alliance":"RED"}}""")
    }

    @Test fun `subsystem timestamps reject fractions and integer overflow`() {
        ActionReplay.registerSubsystemState(TimestampAuditState::class.java)
        for (number in listOf("1.5", "9223372036854775808", "-9223372036854775809")) {
            reject("""{"schema_version":1,"type":"UpdateSubsystemState","payload":{"timestampMs":$number,"state":{"value":1},"_ares_subsystem_state_type":"${TimestampAuditState::class.java.name}"}}""")
        }
    }

    @Test fun `duplicate envelope or payload members are not silently overwritten`() {
        reject("""{"schema_version":2,"schema_version":1,"type":"SetAlliance","payload":{"alliance":"RED","timestampMs":1}}""")
        reject("""{"schema_version":1,"type":"SetAlliance","payload":{"alliance":"RED","timestampMs":1,"timestampMs":2}}""")
    }

    @Test fun `non JSON syntax is rejected`() {
        reject("""{'schema_version':1,'type':'SetAlliance','payload':{'alliance':'RED','timestampMs':1}}""")
    }

    @Test fun `unknown enum does not become null inside a nonnullable action`() {
        reject("""{"schema_version":1,"type":"SetAlliance","payload":{"alliance":"INVALID","timestampMs":1}}""")
    }

    @Test fun `missing drive fields do not become zero commands`() {
        reject("""{"schema_version":1,"type":"JoystickDriveIntent","payload":{"timestampMs":1}}""")
    }

    @Test fun `numeric strings are not accepted as timestamp numbers`() {
        reject("""{"schema_version":1,"type":"SetAlliance","payload":{"alliance":"RED","timestampMs":"1"}}""")
    }

    @Test fun `nullable targets and negative zero retain their meaning`() {
        val actions = listOf(RobotAction.SetHeadingLockTarget(null, Long.MIN_VALUE),
            RobotAction.SetPositionLockTarget(null, null, 0L),
            RobotAction.SetHeadingLockTarget(-0.0, Long.MAX_VALUE))
        val gson = com.google.gson.Gson()
        val file = File(directory, "nullable.jsonl").also { target ->
            target.writeText(actions.joinToString("\n") {
                """{"schema_version":1,"type":"${it.javaClass.simpleName}","payload":${gson.toJson(it)}}"""
            })
        }
        val decoded = ActionReplay.parseActions(file)
        assertEquals(actions, decoded)
        assertEquals((-0.0).toBits(), (decoded.last() as RobotAction.SetHeadingLockTarget).targetRadians!!.toBits())
    }

    @Test fun `array and nested duplicate members are rejected before decoding`() {
        reject("""{"schema_version":1,"type":"StartCalibrationSweep","payload":{"startHeading":1e999,"cameraIndex":0,"timestampMs":1}}""")
        reject("""{"schema_version":1,"type":"CalibrationFrameLogged","payload":{"gyroHeading":0,"tagId":1,"cameraIndex":0,"cameraToTagTransform":{},"timestampMs":1}}""")
        reject("""{"schema_version":1,"type":"UpdateTuningState","payload":{"tuning":{"drive":{"trackWidthMeters":1,"trackWidthMeters":2}},"timestampMs":1}}""")
    }

    @Test fun `camera frame logging snapshots the caller owned transform array`() {
        val values = doubleArrayOf(1.0, -0.0, 3.0)
        val action = CalibrationFrameLogged(0.5, 7, 1, values, 100L)
        val encoded = ActionReplay.encodeForLog(action)
        values[0] = 9.0
        assertEquals(1.0, encoded.payload.getAsJsonArray("cameraToTagTransform")[0].asDouble)
        val file = File(directory, "frame.jsonl").also { it.writeText(
            """{"schema_version":1,"type":"${encoded.type}","payload":${encoded.payload}}""") }
        val decoded = ActionReplay.parseActions(file).single() as CalibrationFrameLogged
        assertTrue(decoded.cameraToTagTransform.contentEquals(doubleArrayOf(1.0, -0.0, 3.0)))
    }

    @Test fun `exact integer adapters reject nested fractions and accept integral exponent notation`() {
        ActionReplay.registerSubsystemState(TimestampAuditState::class.java)
        reject("""{"schema_version":1,"type":"UpdateSubsystemState","payload":{"timestampMs":1,"state":{"value":1.5},"_ares_subsystem_state_type":"${TimestampAuditState::class.java.name}"}}""")
        val file = File(directory, "exponent.jsonl").also { it.writeText(
            """{"schema_version":1,"type":"SetPrismDriver","payload":{"name":"prism","pulseWidthUs":1.5e3,"timestampMs":1e3}}""") }
        val decoded = ActionReplay.parseActions(file).single() as RobotAction.SetPrismDriver
        assertEquals(1500, decoded.pulseWidthUs)
        assertEquals(1000L, decoded.timestampMs)
    }
}
