package com.ares.analytics.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes project-mutating Gradle and device operations across dedicated services. */
internal class ProjectProcessGate {
    private val mutex = Mutex()

    suspend fun <T> runExclusive(operation: suspend () -> T): T = mutex.withLock { operation() }
}
