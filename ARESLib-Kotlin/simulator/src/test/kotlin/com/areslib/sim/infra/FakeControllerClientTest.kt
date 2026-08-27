package com.areslib.sim.infra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FakeControllerClientTest {
    @Test
    fun `remote log names are restricted to supported basenames`() {
        assertEquals("run-001.jsonl", FakeControllerClient.safeLogBasename("run-001.jsonl"))
        assertEquals("run-002.csv", FakeControllerClient.safeLogBasename("run-002.csv"))
        assertEquals("downloaded-log.jsonl", FakeControllerClient.localLogFilename("run-001.jsonl"))
        assertEquals("downloaded-log.csv", FakeControllerClient.localLogFilename("run-002.csv"))

        listOf(
            "../auth.jsonl",
            "..\\auth.jsonl",
            "C:auth.jsonl",
            "/tmp/run.csv",
            "run.active",
            ""
        ).forEach { hostile ->
            assertThrows(hostile, IllegalArgumentException::class.java) {
                FakeControllerClient.safeLogBasename(hostile)
            }
        }
    }
}
