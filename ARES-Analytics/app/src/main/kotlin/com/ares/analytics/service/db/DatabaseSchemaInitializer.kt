package com.ares.analytics.service.db

import java.sql.Connection

/**
 * Initializes the one current DuckDB telemetry schema.
 *
 * Configures relational tables for main persistent database connections and temporary in-memory
 * connection instances, establishing primary keys, indexed metrics, and default values for robot performance metrics.
 *
 * ### Database Tables & Schemas:
 * - `sessions`: `(session_id VARCHAR PRIMARY KEY, team_id, season_id, robot_id, created_at BIGINT, duration_ms BIGINT, tags VARCHAR, match_number BIGINT, alliance_color VARCHAR, import_state VARCHAR)`
 * - `session_summaries`: Aggregate performance KPIs (`min_battery_voltage` V, `max_ekf_drift` m, `avg_loop_time_ms` ms, `p95_loop_time_ms` ms, `vision_acceptance_rate` %, `avg_cross_track_error` m)
 * - `telemetry_frames`: Time-series data points `(timestamp_ms BIGINT, session_id VARCHAR, key VARCHAR, value DOUBLE, string_value VARCHAR)`
 * - `analysis_diagnostics`: Replaceable derived metrics keyed by `(session_id, key)`
 * - `session_import_reports`: Machine-readable source/import evidence retained with cloud bundles
 * - `session_annotations`, `alerts`, `console_messages`, `cached_topologies`, `robot_actions`
 *
 * ### Thread Safety & Performance Guarantees:
 * Must be executed sequentially during system startup before telemetry frame ingestion begins.
 * Uses atomic DDL statements (`CREATE TABLE IF NOT EXISTS`).
 *
 * @param conn Primary DuckDB JDBC connection bound to persistent storage on disk.
 * @param ephemeralConn Temporary in-memory DuckDB JDBC connection used for fast buffer filtering.
 *
 * @see DatabaseBackupExporter
 * @see DatabaseTransactionCoordinator
 */
class DatabaseSchemaInitializer(
    private val conn: Connection,
    private val ephemeralConn: Connection
) {
    /** Initializes persistent and in-memory schemas before any repository is used. */
    fun initialize() {
        createSchemaSync(conn)
        recoverInterruptedImports()
        createSchemaSync(ephemeralConn)
    }

    /**
     * Executes DDL `CREATE TABLE IF NOT EXISTS` queries synchronously on the specified JDBC connection.
     *
     * @param targetConn The active DuckDB connection to initialize.
     */
    private fun createSchemaSync(targetConn: Connection) {
        targetConn.createStatement().use { st ->
            st.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id VARCHAR PRIMARY KEY,
                    team_id VARCHAR NOT NULL,
                    season_id VARCHAR NOT NULL,
                    robot_id VARCHAR NOT NULL,
                    created_at BIGINT NOT NULL,
                    duration_ms BIGINT NOT NULL DEFAULT 0,
                    tags VARCHAR NOT NULL DEFAULT '[]',
                    match_number BIGINT,
                    alliance_color VARCHAR,
                    import_state VARCHAR NOT NULL DEFAULT 'COMPLETE'
                );

                CREATE TABLE IF NOT EXISTS session_summaries (
                    session_id VARCHAR PRIMARY KEY,
                    team_id VARCHAR NOT NULL,
                    season_id VARCHAR NOT NULL,
                    robot_id VARCHAR NOT NULL,
                    created_at BIGINT NOT NULL,
                    duration_ms BIGINT NOT NULL DEFAULT 0,
                    min_battery_voltage DOUBLE NOT NULL DEFAULT 0.0,
                    max_ekf_drift DOUBLE NOT NULL DEFAULT 0.0,
                    avg_loop_time_ms DOUBLE NOT NULL DEFAULT 0.0,
                    p95_loop_time_ms DOUBLE NOT NULL DEFAULT 0.0,
                    motor_current_averages VARCHAR NOT NULL DEFAULT '{}',
                    vision_acceptance_rate DOUBLE NOT NULL DEFAULT 0.0,
                    avg_cross_track_error DOUBLE NOT NULL DEFAULT 0.0,
                    avg_battery_resistance DOUBLE NOT NULL DEFAULT 0.0,
                    max_motor_temps VARCHAR NOT NULL DEFAULT '{}',
                    avg_vision_latency_ms DOUBLE NOT NULL DEFAULT 0.0,
                    tags VARCHAR NOT NULL DEFAULT '[]',
                    match_number BIGINT,
                    alliance_color VARCHAR
                );

                CREATE TABLE IF NOT EXISTS telemetry_frames (
                    timestamp_ms BIGINT NOT NULL,
                    session_id VARCHAR NOT NULL,
                    key VARCHAR NOT NULL,
                    value DOUBLE NOT NULL,
                    string_value VARCHAR,
                    timestamp_us BIGINT NOT NULL,
                    sample_order BIGINT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS analysis_diagnostics (
                    session_id VARCHAR NOT NULL,
                    key VARCHAR NOT NULL,
                    value DOUBLE NOT NULL,
                    string_value VARCHAR,
                    PRIMARY KEY (session_id, key)
                );

                CREATE TABLE IF NOT EXISTS session_import_reports (
                    session_id VARCHAR NOT NULL,
                    source_sha256 VARCHAR NOT NULL,
                    source_name VARCHAR NOT NULL,
                    report_json VARCHAR NOT NULL,
                    PRIMARY KEY (session_id, source_sha256, source_name)
                );

                CREATE TABLE IF NOT EXISTS session_annotations (
                    annotation_id VARCHAR PRIMARY KEY,
                    session_id VARCHAR NOT NULL,
                    text VARCHAR NOT NULL,
                    created_at BIGINT NOT NULL,
                    author_id VARCHAR
                );

                CREATE TABLE IF NOT EXISTS alerts (
                    alert_id VARCHAR PRIMARY KEY,
                    session_id VARCHAR NOT NULL,
                    rule_key VARCHAR NOT NULL,
                    trigger_timestamp_ms BIGINT NOT NULL,
                    resolve_timestamp_ms BIGINT,
                    duration_ms BIGINT NOT NULL DEFAULT 0,
                    peak_value DOUBLE NOT NULL DEFAULT 0.0,
                    triaged BIGINT NOT NULL DEFAULT 0
                );

                CREATE TABLE IF NOT EXISTS console_messages (
                    timestamp_ms BIGINT NOT NULL,
                    session_id VARCHAR NOT NULL,
                    text VARCHAR NOT NULL,
                    severity VARCHAR NOT NULL,
                    PRIMARY KEY (session_id, timestamp_ms, text)
                );

                CREATE TABLE IF NOT EXISTS cached_topologies (
                    robot_id VARCHAR PRIMARY KEY,
                    topology_json VARCHAR NOT NULL
                );

                CREATE TABLE IF NOT EXISTS robot_actions (
                    timestamp_ms BIGINT NOT NULL,
                    session_id VARCHAR NOT NULL,
                    run_id VARCHAR NOT NULL,
                    robot_id VARCHAR NOT NULL,
                    match_number INTEGER NOT NULL DEFAULT 0,
                    alliance VARCHAR NOT NULL DEFAULT 'UNKNOWN',
                    action_type VARCHAR NOT NULL,
                    payload_json VARCHAR NOT NULL
                );

                CREATE TABLE IF NOT EXISTS integration_events (
                    event_id VARCHAR PRIMARY KEY,
                    schema_version INTEGER NOT NULL,
                    event_type VARCHAR NOT NULL,
                    occurred_at_ms BIGINT NOT NULL,
                    aggregate_id VARCHAR NOT NULL,
                    team_id VARCHAR NOT NULL,
                    season_id VARCHAR NOT NULL,
                    robot_id VARCHAR NOT NULL,
                    payload_json VARCHAR NOT NULL,
                    content_hash VARCHAR NOT NULL
                );

                CREATE TABLE IF NOT EXISTS integration_deliveries (
                    event_id VARCHAR NOT NULL,
                    provider_id VARCHAR NOT NULL,
                    state VARCHAR NOT NULL,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at_ms BIGINT NOT NULL,
                    lease_owner VARCHAR,
                    lease_expires_at_ms BIGINT,
                    last_error_kind VARCHAR,
                    last_error_message VARCHAR,
                    receipt_json VARCHAR,
                    updated_at_ms BIGINT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS engineering_notebook_entries (
                    entry_id VARCHAR NOT NULL,
                    revision INTEGER NOT NULL,
                    schema_version INTEGER NOT NULL,
                    entry_type VARCHAR NOT NULL,
                    team_id VARCHAR NOT NULL,
                    season_id VARCHAR NOT NULL,
                    robot_id VARCHAR NOT NULL,
                    markdown_body VARCHAR NOT NULL,
                    evidence_json VARCHAR NOT NULL,
                    visibility VARCHAR NOT NULL,
                    review_state VARCHAR NOT NULL,
                    human_author_id VARCHAR,
                    human_reviewer_id VARCHAR,
                    ai_provenance_json VARCHAR,
                    content_hash VARCHAR NOT NULL,
                    created_at_ms BIGINT NOT NULL,
                    updated_at_ms BIGINT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS notebook_publication_receipts (
                    entry_id VARCHAR NOT NULL,
                    revision INTEGER NOT NULL,
                    publisher_id VARCHAR NOT NULL,
                    content_hash VARCHAR NOT NULL,
                    remote_id VARCHAR NOT NULL,
                    receipt_json VARCHAR NOT NULL,
                    accepted_at_ms BIGINT NOT NULL,
                    PRIMARY KEY (entry_id, revision, publisher_id, remote_id)
                );

            """.trimIndent())
            // Telemetry is an immutable, append-only analytical fact table. DuckDB creates an ART
            // index for every primary/unique constraint and explicit CREATE INDEX. Those indexes
            // add no useful ordering guarantee here (sample_order already preserves source order),
            // and WAL replay against tens of millions of indexed rows can turn a small recovery
            // log into a many-minute cold start. Session-grouped ingestion plus DuckDB's automatic
            // zonemaps serve the bounded session/key/time scans without a second copy of every key.
            st.execute("CREATE INDEX IF NOT EXISTS idx_analysis_diagnostics_session ON analysis_diagnostics(session_id)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_session_import_reports_session ON session_import_reports(session_id)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_session_import_reports_sha ON session_import_reports(source_sha256)")
            st.execute(
                "CREATE INDEX IF NOT EXISTS idx_integration_events_aggregate " +
                    "ON integration_events(aggregate_id, occurred_at_ms)"
            )
        }
        requireCurrentColumns(
            targetConn,
            "sessions",
            setOf(
                "session_id", "team_id", "season_id", "robot_id", "created_at", "duration_ms",
                "tags", "match_number", "alliance_color", "import_state",
            ),
        )
        requireCurrentColumns(
            targetConn,
            "telemetry_frames",
            setOf("timestamp_ms", "session_id", "key", "value", "string_value", "timestamp_us", "sample_order"),
        )
    }

    private fun requireCurrentColumns(connection: Connection, table: String, required: Set<String>) {
        val actual = connection.prepareStatement(
            "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = ?"
        ).use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1).lowercase()) }
            }
        }
        val missing = required - actual
        require(missing.isEmpty()) {
            "Database table '$table' is not the current ARES schema; missing ${missing.sorted().joinToString()}. " +
                "Create a new telemetry database before continuing."
        }
    }

    /**
     * Removes data owned by an import that did not reach its atomic completion marker.
     *
     * Decoders intentionally stream large inputs in bounded batches, so one giant DuckDB
     * transaction would be both expensive and fragile. An IMPORTING session is the durable
     * staging owner. Only the final metadata/report transaction changes it to COMPLETE. If the
     * process loses power first, the next startup removes that staging owner and every row it
     * owns before any screen can list it.
     */
    private fun recoverInterruptedImports() {
        conn.createStatement().use { statement ->
            statement.execute("BEGIN TRANSACTION")
            try {
                val ownedTables = listOf(
                    "session_summaries",
                    "telemetry_frames",
                    "analysis_diagnostics",
                    "session_import_reports",
                    "session_annotations",
                    "alerts",
                    "console_messages",
                    "robot_actions",
                )
                ownedTables.forEach { table ->
                    statement.execute(
                        "DELETE FROM $table WHERE session_id IN " +
                            "(SELECT session_id FROM sessions WHERE import_state = 'IMPORTING')"
                    )
                }
                statement.execute("DELETE FROM sessions WHERE import_state = 'IMPORTING'")

                statement.execute("COMMIT")
            } catch (failure: Throwable) {
                runCatching { statement.execute("ROLLBACK") }
                throw failure
            }
        }
    }

}
