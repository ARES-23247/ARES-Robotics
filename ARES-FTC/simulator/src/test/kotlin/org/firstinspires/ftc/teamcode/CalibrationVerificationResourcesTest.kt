package org.firstinspires.ftc.teamcode

import org.junit.Assert.*
import org.junit.Test

class CalibrationVerificationResourcesTest {
    @Test fun `partial startup failure closes acquired handles in reverse order`() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("startup")
        val observed = runCatching {
            CalibrationVerificationResources().use { resources ->
                resources.own(AutoCloseable { events.add("instance") })
                resources.own(AutoCloseable { events.add("publisher") })
                resources.beforeClose { events.add("STOP") }
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, observed)
        assertEquals(listOf("STOP", "publisher", "instance"), events)
    }

    @Test fun `stop and close failures do not skip later cleanup and repeat close does nothing`() {
        val scope = CalibrationVerificationResources()
        val events = mutableListOf<String>()
        val stopFailure = IllegalStateException("stop")
        val closeFailure = IllegalStateException("publisher")
        scope.own(AutoCloseable { events.add("instance") })
        scope.own(AutoCloseable { events.add("publisher"); throw closeFailure })
        scope.beforeClose { events.add("STOP"); throw stopFailure }
        scope.beforeClose { events.add("calibration STOP") }
        scope.beforeClose { events.add("flush") }
        assertSame(stopFailure, runCatching { scope.close() }.exceptionOrNull())
        assertEquals(listOf(closeFailure), stopFailure.suppressed.toList())
        assertEquals(listOf("STOP", "calibration STOP", "flush", "publisher", "instance"), events)
        scope.close()
        assertEquals(5, events.size)
    }

    @Test fun `body failure retains cleanup failure as suppressed`() {
        val primary = IllegalStateException("verification")
        val cleanup = IllegalStateException("cleanup")
        val observed = runCatching {
            CalibrationVerificationResources().use { resources ->
                resources.own(AutoCloseable { throw cleanup })
                throw primary
            }
        }.exceptionOrNull()
        assertSame(primary, observed)
        assertEquals(listOf(cleanup), primary.suppressed.toList())
    }
}
