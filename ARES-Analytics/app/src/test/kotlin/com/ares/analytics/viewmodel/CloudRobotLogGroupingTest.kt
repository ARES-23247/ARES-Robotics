package com.ares.analytics.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CloudRobotLogGroupingTest {
    @Test
    fun `telemetry and action filenames group by shared run uuid`() {
        val runId = "123e4567-e89b-42d3-a456-426614174000"
        val telemetry = "ares_log_2026-08-20_12-30-00-100_TeleOp_run_$runId.csv.gz"
        val actions = "action_log_2026-08-20_12-30-00-121_${runId}_TeleOp.jsonl"

        assertEquals(robotLogRunKey(telemetry), robotLogRunKey(actions))
    }

    @Test
    fun `legacy files group only within their start second`() {
        val telemetry = "ares_log_2026-08-20_12-30-00-100_TeleOp.csv"
        val actions = "action_log_2026-08-20_12-30-00-900_old-run_TeleOp.jsonl"
        val later = "ares_log_2026-08-20_12-30-01-001_TeleOp.csv"

        assertEquals(robotLogRunKey(telemetry), robotLogRunKey(actions))
        assertNotEquals(robotLogRunKey(telemetry), robotLogRunKey(later))
    }
}
