package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.service.SysIdLogParser

import com.ares.analytics.service.AlignedDataRow
import kotlin.test.*

class SysIdLogImportAuditTest {
    private fun parse(content: String) = SysIdLogParser.parse(content)

    @Test fun `malformed flattened values cannot shift voltage velocity or acceleration columns`() {
        assertTrue(parse("""{"SysId/Data":[0,6,"bad",2,3,4]}""").isEmpty())
    }

    @Test fun `explicit zero acceleration is preserved`() {
        val rows = parse("time,voltage,velocity,acceleration\n0,6,1,0\n20,6,3,0")
        assertEquals(listOf(0.0, 0.0), rows.map { it.accel })
    }

    @Test fun `missing acceleration is derived only where a timed predecessor exists`() {
        val rows = parse("time,voltage,velocity\n0,6,1\n20,6,3\n40,6,5")
        assertEquals(listOf(20L, 40L), rows.map { it.timestampMs })
        assertEquals(listOf(100.0, 100.0), rows.map { it.accel })
    }

    @Test fun `explicit seconds header is converted before differentiation`() {
        val rows = parse("Time (s),Voltage,Velocity\n0,6,1\n0.02,6,3\n0.04,6,5")
        assertEquals(listOf(20L, 40L), rows.map { it.timestampMs })
        assertEquals(listOf(100.0, 100.0), rows.map { it.accel })
    }

    @Test fun `missing or malformed timestamps are never replaced with row indices`() {
        assertTrue(parse("voltage,velocity,acceleration\n6,1,2\n6,2,3").isEmpty())
        assertTrue(parse("time,voltage,velocity,acceleration\nbad,6,1,2").isEmpty())
    }

    @Test fun `nonfinite numeric data is rejected in either format`() {
        for (value in listOf("NaN", "Infinity", "-Infinity")) {
            assertTrue(parse("time,voltage,velocity,acceleration\n0,$value,1,2").isEmpty())
            assertTrue(parse("""{"timestamp":0,"voltage":"$value","velocity":1,"acceleration":2}""").isEmpty())
        }
    }

    @Test fun `malformed supplied acceleration does not become missing or zero`() {
        assertTrue(parse("time,voltage,velocity,acceleration\n0,6,1,bad\n20,6,2,bad").isEmpty())
        assertTrue(parse("""{"timestamp":0,"voltage":6,"velocity":1,"acceleration":"bad"}""").isEmpty())
    }

    @Test fun `mixed provided and missing acceleration preserves provided values`() {
        val rows = parse("""
            {"timestamp":0,"voltage":6,"velocity":1,"acceleration":0}
            {"timestamp":20,"voltage":6,"velocity":3}
            {"timestamp":40,"voltage":6,"velocity":5,"acceleration":7}
        """.trimIndent())
        assertEquals(listOf(0.0,100.0,7.0), rows.map { it.accel })
    }

    @Test fun `rows are chronological even when acceleration is supplied`() {
        val rows = parse("time,voltage,velocity,acceleration\n40,6,5,2\n0,6,1,2\n20,6,3,2")
        assertEquals(listOf(0L,20L,40L),rows.map { it.timestampMs })
    }

    @Test fun `ambiguous duplicate timestamps are excluded`() {
        val rows = parse("time,voltage,velocity,acceleration\n0,6,1,2\n20,6,3,2\n20,5,9,2\n40,6,5,2")
        assertEquals(listOf(0L,40L),rows.map { it.timestampMs })
    }

    @Test fun `negative fractional and out of range millisecond timestamps are rejected`() {
        for (time in listOf("-1", "0.5", "999999999999999999999")) {
            assertTrue(parse("time,voltage,velocity,acceleration\n$time,6,1,2").isEmpty())
        }
    }

    @Test fun `flattened aliases preserve original field positions`() {
        for (key in listOf("SysId/Data","SysId_Data","sysid_data")) {
            assertEquals(listOf(AlignedDataRow(20,6.0,3.0,2.0)), parse("""{"$key":[20,6,100,3,2]}"""))
        }
    }

    @Test fun `malformed lines are isolated from valid neighboring rows`() {
        val rows = parse("""
            {"timestamp":0,"voltage":6,"velocity":1,"acceleration":2}
            broken json
            {"timestamp":20,"voltage":6,"velocity":2,"acceleration":3}
        """.trimIndent())
        assertEquals(listOf(0L,20L), rows.map { it.timestampMs })
        assertTrue(parse(" \n\t").isEmpty())
    }

    @Test fun `quoted commas and escaped quotes cannot shift numeric columns`() {
        val rows = parse("note,\"Time (s)\",voltage,velocity,acceleration\n\"alpha, \"\"beta\"\"\",0.02,6,3,2")
        assertEquals(listOf(AlignedDataRow(20,6.0,3.0,2.0)), rows)
        assertTrue(parse("time,voltage,velocity,acceleration\n\"20,6,3,2").isEmpty())
        assertTrue(parse("time,voltage,velocity,acceleration\n\"20\"bad,6,3,2").isEmpty())
    }

    @Test fun `microsecond headers are normalized to milliseconds`() {
        val rows = parse("time_us,voltage,velocity\n0,6,1\n20000,6,3")
        assertEquals(listOf(AlignedDataRow(20,6.0,3.0,100.0)), rows)
    }

    @Test fun `identical duplicate measurements count once`() {
        val rows = parse("time,voltage,velocity,acceleration\n0,6,1,2\n0,6,1,2\n20,6,3,2")
        assertEquals(listOf(0L,20L), rows.map { it.timestampMs })
    }

    @Test fun `null acceleration and blank fields remain missing but truncated rows are rejected`() {
        val rows = parse("""
            {"SysId/Data":[0,6,0,1,0]}
            {"SysId/Data":[20,6,0,3,null]}
        """.trimIndent())
        assertEquals(listOf(0.0,100.0), rows.map { it.accel })
        assertEquals(listOf(0.0,100.0), parse("time,voltage,velocity,acceleration\n0,6,1,0\n20,6,3,").map { it.accel })
        assertTrue(parse("time,voltage,velocity,acceleration\n20,6,3").isEmpty())
    }

    @Test fun `structured values do not escape as parser exceptions`() {
        for (row in listOf("""{"SysId/Data":{}}""", """{"SysId/Data":[0,6,{},1,2]}""",
            """{"timestamp":0,"voltage":{},"velocity":1,"acceleration":2}""")) {
            assertTrue(parse(row).isEmpty())
        }
    }

    @Test fun `unrepresentable derivatives are omitted while scaled finite derivatives survive`() {
        val maximum = Double.MAX_VALUE
        assertTrue(parse("time,voltage,velocity\n0,6,-$maximum\n20,6,$maximum").isEmpty())
        val rows = parse("time,voltage,velocity\n0,6,-$maximum\n1000000000,6,$maximum")
        assertEquals(1, rows.size)
        assertTrue(rows.single().accel.isFinite())
        assertEquals(2e-6, rows.single().accel / maximum, 1e-15)
    }

    @Test fun `byte order mark and scalar aliases are supported`() {
        assertEquals(listOf(AlignedDataRow(20,6.0,3.0,2.0)),
            parse("\uFEFF \t" + """{"TimestampMs":20,"Drive/Voltage":6,"Drive/Velocity":3,"Drive/Acceleration":2}"""))
    }
}
