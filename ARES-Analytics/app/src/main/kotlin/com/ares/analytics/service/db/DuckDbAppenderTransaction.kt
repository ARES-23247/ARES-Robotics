package com.ares.analytics.service.db

import java.sql.Connection

/** Activates the native transaction before appending; call under the repository write lock. */
internal inline fun <T> withDuckDbAppenderTransaction(connection: Connection, block: () -> T): T =
    withDuckDbTransaction(connection, activateNativeTransaction = true, block = block)
