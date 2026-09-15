package org.aresfirst.starter.frc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StarterResourceCleanupAuditTest {
    @Test
    fun `cleanup preserves the first failure deduplicates later failures and retains direct interruption`() {
        val first = IllegalStateException("first")
        val interrupted = InterruptedException("interrupted")
        var lastClosed = false
        val wasInterrupted = Thread.interrupted()
        try {
            val thrown = assertThrows(IllegalStateException::class.java) {
                closeStarterResources(listOf(
                    AutoCloseable { throw first }, AutoCloseable { throw interrupted },
                    AutoCloseable { throw first }, AutoCloseable { throw interrupted },
                    AutoCloseable { lastClosed = true },
                ))
            }
            assertSame(first, thrown)
            assertArrayEquals(arrayOf(interrupted), first.suppressed)
            assertTrue(lastClosed)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }
}
