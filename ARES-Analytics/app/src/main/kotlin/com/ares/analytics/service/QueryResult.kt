package com.ares.analytics.service

/**
 * Tabular result set model encapsulated from arbitrary SQL queries executed against the DuckDB telemetry store.
 *
 * Provides a decoupled, structured representation of query output for custom database analytics, trajectory
 * visualization, and UI table rendering without binding UI screens directly to JDBC ResultSets.
 *
 * ### Data Representation:
 * - [columns]: Ordered list of column header string labels returned by SQL SELECT statements.
 * - [rows]: List of rows, where each row is a list of stringified cell values aligned index-by-index with [columns].
 * - [isTruncated]: Whether additional rows existed beyond the repository-enforced [rowLimit].
 *
 * ### Thread Safety & Performance Guarantees:
 * Immutable data structure. Safe for concurrent access across UI state flows and background IO dispatchers. Zero heap allocations
 * post-construction during table read rendering.
 *
 * @property columns Ordered column names matching the database query projection.
 * @property rows List of tabular data rows, formatted as stringified column cell values.
 * @property isTruncated `true` when the result contains only the first [rowLimit] rows.
 * @property rowLimit Repository row cap applied to this query, or `null` when no cap was needed.
 * @property truncatedCellCount Number of oversized cell values shortened before entering UI state.
 *
 * @see com.ares.analytics.service.DatabaseService
 * @see com.ares.analytics.service.db.MatchLogRepository
 */
data class QueryResult(
    val columns: List<String>,
    val rows: List<List<String>>,
    val isTruncated: Boolean = false,
    val rowLimit: Int? = null,
    val truncatedCellCount: Int = 0
) {
    companion object {
        /** Default maximum number of custom-query rows retained in desktop memory. */
        const val DEFAULT_RAW_QUERY_ROW_LIMIT = 1_000

        /** Upper bound accepted from any future caller that exposes a configurable limit. */
        const val MAX_RAW_QUERY_ROW_LIMIT = 5_000

        /** Protects the UI from a single unexpectedly large VARCHAR/BLOB representation. */
        const val MAX_CELL_CHARACTERS = 16_384

        /** Protects table rendering from pathological projections with thousands of columns. */
        const val MAX_RAW_QUERY_COLUMN_COUNT = 256
    }
}

