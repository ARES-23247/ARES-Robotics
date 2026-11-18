package com.ares.analytics.service.db

import java.sql.Connection

/** Caller owns the connection mutex throughout the read. */
internal inline fun <T> Connection.inReadSnapshot(read: () -> T): T {
    createStatement().use { it.execute("BEGIN TRANSACTION READ ONLY") }
    try {
        val result = read()
        createStatement().use { it.execute("COMMIT") }
        return result
    } catch (failure: Throwable) {
        runCatching { createStatement().use { it.execute("ROLLBACK") } }
        throw failure
    }
}
