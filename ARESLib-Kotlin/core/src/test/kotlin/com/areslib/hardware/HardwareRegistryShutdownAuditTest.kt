package com.areslib.hardware

import com.areslib.telemetry.ITelemetry
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class HardwareRegistryShutdownAuditTest {
    @Test fun `device close precedes a potentially blocked service drain and shared resources close once`() {
        val registry=HardwareRegistry()
        var deviceCloses=0
        val device=object: LoggableDevice,AutoCloseable {
            override fun logTelemetry(telemetry: ITelemetry,prefix: String) = Unit
            override fun close() { deviceCloses++ }
        }
        val serviceEntered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>()
        registry.registerCloseable(AutoCloseable {
            serviceEntered.countDown()
            check(release.await(5,TimeUnit.SECONDS))
        })
        registry.registerCloseable(device)
        registry.registerDevice("owned-actuator",device)
        val closer=Thread({
            try { registry.closeAll() } catch(error: Throwable) { failure.set(error) }
        },"audit-owned-registry-close")
        try {
            closer.start()
            assertTrue(serviceEntered.await(2,TimeUnit.SECONDS))
            assertEquals(1,deviceCloses,"Hardware closure cannot wait behind a service's disk drain")
        } finally {
            release.countDown()
            closer.join(3000)
            check(!closer.isAlive)
        }
        failure.get()?.let { throw it }
        registry.closeAll()
        assertEquals(1,deviceCloses)
    }
}
