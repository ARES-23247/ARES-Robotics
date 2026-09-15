package com.areslib.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoggingEnvironmentAuditTest {
    private fun withProperty(key: String, value: String, block: () -> Unit) {
        val previous = System.getProperty(key)
        try { System.setProperty(key, value); block() }
        finally { if (previous == null) System.clearProperty(key) else System.setProperty(key, previous) }
    }

    @Test fun `configured profiles select their documented sampling intervals and budgets`() {
        for ((profile, interval) in listOf(LoggingProfile.COMPETITION to 20L, LoggingProfile.SIMULATION to 50L, LoggingProfile.FORENSIC to 0L)) {
            withProperty("ares.logging.profile", " ${profile.name.lowercase()} ") {
                val policy = RobotLogEnvironment.loggingPolicy()
                assertEquals(profile, policy.profile)
                assertEquals(interval, policy.minFrameIntervalMs)
                assertTrue(policy.compress)
                assertTrue(policy.minRetainedFiles in 0..policy.maxCompletedFiles)
                assertTrue(policy.maxDirectoryBytes >= policy.maxFileBytes)
            }
        }
    }
    @Test fun `invalid explicit profile fails instead of silently changing fidelity`() {
        withProperty("ares.logging.profile", "competitionn") { assertFailsWith<IllegalArgumentException> { RobotLogEnvironment.loggingPolicy() } }
    }
    @Test fun `retention accepts documented boolean spellings and rejects ambiguous values`() {
        for (value in listOf("TRUE", " 1 ", "yes", "On")) {
            withProperty("ares.logging.retention.enabled", value) { assertTrue(RobotLogEnvironment.isRetentionEnabled()) }
        }
        for (value in listOf("false", "0", " No ", "OFF")) {
            withProperty("ares.logging.retention.enabled", value) { assertEquals(false, RobotLogEnvironment.isRetentionEnabled()) }
        }
        withProperty("ares.logging.retention.enabled", "sometimes") { assertFailsWith<IllegalArgumentException> { RobotLogEnvironment.isRetentionEnabled() } }
    }
    @Test fun `policy rejects invalid boundaries while allowing zero throttling and minimum count`() {
        val valid = testLoggingPolicy()
        val invalid = listOf<() -> LoggingPolicy>(
            { valid.copy(minFrameIntervalMs = -1) }, { valid.copy(maxFileBytes = 0) },
            { valid.copy(maxFileDurationMs = 0) }, { valid.copy(maxDirectoryBytes = valid.maxFileBytes - 1) },
            { valid.copy(minFreeSpaceBytes = -1) }, { valid.copy(maxCompletedFiles = 0) },
            { valid.copy(minRetainedFiles = -1) }, { valid.copy(maxCompletedFiles = 1, minRetainedFiles = 2) },
            { valid.copy(staleActiveAfterMs = 0) }
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { it() } }
        assertEquals(0, valid.minRetainedFiles)
        assertEquals(0L, valid.minFrameIntervalMs)
    }
}
