package com.ares.analytics.viewmodel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes layout disk writes and their corresponding in-memory state commit. */
internal class DashboardLayoutTransactionQueue {
    private val mutex = Mutex()

    suspend fun <T> transact(block: suspend () -> T): T = mutex.withLock { block() }
}
