package com.ares.analytics.service.db

import java.sql.Connection

/** Called under the owning repository's write lock; joins an existing caller-owned transaction. */
internal inline fun <T> withDuckDbAppenderTransaction(connection: Connection, block: () -> T): T {
    val ownsTransaction = connection.autoCommit
    if (ownsTransaction) connection.autoCommit = false
    var primaryFailure: Throwable? = null
    var restoreAutoCommit = ownsTransaction
    try {
        // DuckDB JDBC starts transactions lazily through statements, not createAppender().
        // A statement activates that context even when autoCommit was already false.
        connection.createStatement().use { it.execute("SELECT 1") }
        val result = block()
        if (ownsTransaction) connection.commit()
        return result
    } catch (error: Throwable) {
        primaryFailure = error
        if (ownsTransaction) {
            try {
                connection.rollback()
            } catch (rollbackError: Throwable) {
                // Restoring auto-commit after a failed rollback could commit the partial batch.
                restoreAutoCommit = false
                error.addSuppressed(rollbackError)
                runCatching { connection.close() }.exceptionOrNull()?.let(error::addSuppressed)
            }
        }
        throw error
    } finally {
        if (restoreAutoCommit) {
            try {
                connection.autoCommit = true
            } catch (resetError: Throwable) {
                val primary = primaryFailure
                primary?.addSuppressed(resetError)
                val reported = primary ?: resetError
                runCatching { connection.close() }.exceptionOrNull()?.let(reported::addSuppressed)
                if (primary == null) throw resetError
            }
        }
    }
}
