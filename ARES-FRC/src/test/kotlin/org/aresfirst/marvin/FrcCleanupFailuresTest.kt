package org.aresfirst.marvin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrcCleanupFailuresTest {
    @Test fun `disable attempts all remaining safety work after every possible failed stage`() {
        for (failedStage in 0..5) {
            val attempted = mutableListOf<Int>()
            val original = IllegalStateException("failure at $failedStage")
            fun stage(index: Int) { attempted.add(index); if (index == failedStage) throw original }
            val actual = assertThrows(IllegalStateException::class.java) {
                runFrcDisableCleanup({ stage(0) }, { stage(1) }, { stage(2) }, { stage(3) }, { stage(4) }, { stage(5) })
            }
            assertSame(original, actual)
            assertEquals((0..5).toList(), attempted, "Failed stage $failedStage skipped later cleanup")
        }
    }

    @Test fun `same exception from two owners cannot abort remaining cleanup`() {
        val failures = FrcCleanupFailures()
        val original = IllegalStateException("shared native failure")
        val attempted = mutableListOf<Int>()
        failures.attempt { attempted.add(1); throw original }
        failures.attempt { attempted.add(2); throw original }
        failures.attempt { attempted.add(3) }
        assertEquals(listOf(1, 2, 3), attempted)
        assertSame(original, assertThrows(IllegalStateException::class.java) { failures.throwIfAny() })
        assertEquals(0, original.suppressed.size)
    }

    @Test fun `all cleanup steps and finally blocks run before the original error is rethrown`() {
        val failures = FrcCleanupFailures()
        val original = IllegalStateException("cancel failed")
        val secondary = AssertionError("neutralization failed")
        val attempted = mutableListOf<String>()
        failures.attempt { attempted.add("cancel"); throw original }
        failures.attempt { try { attempted.add("neutralize"); throw secondary } finally { attempted.add("clear owner") } }
        failures.attempt { attempted.add("close native") }
        assertEquals(listOf("cancel", "neutralize", "clear owner", "close native"), attempted)
        assertSame(original, assertThrows(IllegalStateException::class.java) { failures.throwIfAny() })
        assertEquals(listOf(secondary), original.suppressed.toList())
    }

    @Test fun `successful cleanup preserves action order and completes normally`() {
        val failures = FrcCleanupFailures()
        val attempted = mutableListOf<Int>()
        repeat(6) { index -> failures.attempt { attempted.add(index) } }
        failures.throwIfAny()
        assertEquals((0..5).toList(), attempted)
    }
}
