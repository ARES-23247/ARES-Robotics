package com.ares.analytics

import com.ares.analytics.di.ServiceRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class ServiceRegistryShutdownAuditTest {
    @Test
    fun `cold shutdown never initializes unused services and can be repeated`() = runBlocking {
        val services = ServiceRegistry()
        forbidUnusedServiceInitialization(services)

        services.disposeAndJoin()
        services.disposeAndJoin()
    }

    @Test
    fun `partial startup shutdown disarms initialized controls without starting dependencies`() = runBlocking {
        val services = ServiceRegistry()
        services.keyboardDriveState.enabled = true
        services.keyboardDriveState.useGamepad = true
        services.keyboardDriveState.isWPressed = true
        forbidUnusedServiceInitialization(services)

        services.disposeAndJoin()

        assertFalse(services.keyboardDriveState.enabled)
        assertFalse(services.keyboardDriveState.useGamepad)
        assertFalse(services.keyboardDriveState.isWPressed)
    }

    @Test
    fun `a late bootstrap cannot reopen services after disposal`() = runBlocking<Unit> {
        val services = ServiceRegistry()
        forbidUnusedServiceInitialization(services)
        services.disposeAndJoin()
        assertFailsWith<CancellationException> { services.prepareForMainScreen() }
    }

    private fun forbidUnusedServiceInitialization(services: ServiceRegistry) {
        // Trap constructor calls instead of opening storage, native devices, or network clients.
        // Exercise real registry disposal, including its lazy-state checks and cleanup ordering.
        ServiceRegistry::class.java.declaredFields
            .filter { Lazy::class.java.isAssignableFrom(it.type) }
            .forEach { field ->
                field.isAccessible = true
                val delegate = field.get(services) as Lazy<*>
                if (!delegate.isInitialized()) {
                    field.set(services, lazy { error("Shutdown initialized unused service: ${field.name}") })
                }
            }
    }
}
