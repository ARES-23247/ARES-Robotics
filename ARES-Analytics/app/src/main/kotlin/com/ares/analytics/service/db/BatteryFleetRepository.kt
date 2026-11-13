package com.ares.analytics.service.db

import com.ares.analytics.domain.pit.BatteryCheckinRecord
import com.ares.analytics.domain.pit.BatteryPoolStatus
import com.ares.analytics.domain.pit.BatterySpecRecord
import com.ares.analytics.domain.pit.normalizeBatteryId
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types

/**
 * Persists physical battery specs (`battery_fleet`) and bench / Battery Beak measurements
 * (`battery_checkins`) in DuckDB using the shared [DatabaseTransactionCoordinator].
 */
internal class BatteryFleetRepository(
    private val transactions: DatabaseTransactionCoordinator,
) {
    private val conn: Connection get() = transactions.writeConnection
    private val readConn: Connection get() = transactions.metadataReadConnection

    suspend fun upsertBatterySpec(spec: BatterySpecRecord) = transactions.write {
        val cleanId = normalizeBatteryId(spec.batteryId)
        require(cleanId.isNotEmpty()) { "Battery ID must not be blank." }
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO battery_fleet (
                team_id, season_id, robot_id, battery_id, label,
                nominal_voltage, capacity_ah, status, notes, created_at_ms, updated_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, spec.teamId.trim())
            ps.setString(2, spec.seasonId.trim())
            ps.setString(3, spec.robotId.trim())
            ps.setString(4, cleanId)
            ps.setString(5, spec.label.trim().ifBlank { cleanId }.take(64))
            ps.setDouble(6, spec.nominalVoltage.takeIf { it.isFinite() && it > 0.0 } ?: 12.0)
            ps.setDouble(7, spec.capacityAh.takeIf { it.isFinite() && it > 0.0 } ?: 3.0)
            ps.setString(8, spec.status.name)
            ps.setString(9, spec.notes.trim().take(512))
            ps.setLong(10, spec.createdAtMs.coerceAtLeast(0L))
            ps.setLong(11, spec.updatedAtMs.coerceAtLeast(0L))
            ps.executeUpdate()
        }
    }

    suspend fun updateBatteryPoolStatus(
        teamId: String,
        seasonId: String,
        robotId: String,
        batteryId: String,
        status: BatteryPoolStatus,
        updatedAtMs: Long,
    ) = transactions.write {
        val cleanId = normalizeBatteryId(batteryId)
        require(cleanId.isNotEmpty()) { "Battery ID must not be blank." }
        conn.prepareStatement(
            """
            UPDATE battery_fleet
            SET status = ?, updated_at_ms = ?
            WHERE team_id = ? AND season_id = ? AND robot_id = ? AND battery_id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, status.name)
            ps.setLong(2, updatedAtMs.coerceAtLeast(0L))
            ps.setString(3, teamId.trim())
            ps.setString(4, seasonId.trim())
            ps.setString(5, robotId.trim())
            ps.setString(6, cleanId)
            ps.executeUpdate()
        }
    }

    suspend fun listBatterySpecsForWorkspace(
        teamId: String,
        seasonId: String,
        robotId: String,
    ): List<BatterySpecRecord> = transactions.readMetadata {
        readConn.prepareStatement(
            """
            SELECT team_id, season_id, robot_id, battery_id, label,
                   nominal_voltage, capacity_ah, status, notes, created_at_ms, updated_at_ms
            FROM battery_fleet
            WHERE team_id = ? AND season_id = ? AND robot_id = ?
            ORDER BY battery_id ASC
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, teamId.trim())
            ps.setString(2, seasonId.trim())
            ps.setString(3, robotId.trim())
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toBatterySpecRecord())
                }
            }
        }
    }

    suspend fun deleteBatterySpecFromWorkspace(
        teamId: String,
        seasonId: String,
        robotId: String,
        batteryId: String,
    ) = transactions.write {
        val cleanId = normalizeBatteryId(batteryId)
        if (cleanId.isEmpty()) return@write
        withDuckDbTransaction(conn) {
            conn.prepareStatement(
                "DELETE FROM battery_checkins WHERE team_id = ? AND season_id = ? AND robot_id = ? AND battery_id = ?"
            ).use { ps ->
                ps.setString(1, teamId.trim())
                ps.setString(2, seasonId.trim())
                ps.setString(3, robotId.trim())
                ps.setString(4, cleanId)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "DELETE FROM battery_fleet WHERE team_id = ? AND season_id = ? AND robot_id = ? AND battery_id = ?"
            ).use { ps ->
                ps.setString(1, teamId.trim())
                ps.setString(2, seasonId.trim())
                ps.setString(3, robotId.trim())
                ps.setString(4, cleanId)
                ps.executeUpdate()
            }
        }
    }

    suspend fun recordBatteryCheckin(checkin: BatteryCheckinRecord) = transactions.write {
        val cleanId = normalizeBatteryId(checkin.batteryId)
        require(cleanId.isNotEmpty()) { "Battery ID must not be blank." }
        require(checkin.checkinId.isNotBlank()) { "Check-in ID must not be blank." }
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO battery_checkins (
                checkin_id, team_id, season_id, robot_id, battery_id,
                resting_voltage, beak_resistance_mohm, soc_percent,
                inspector_initials, notes, recorded_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, checkin.checkinId.trim())
            ps.setString(2, checkin.teamId.trim())
            ps.setString(3, checkin.seasonId.trim())
            ps.setString(4, checkin.robotId.trim())
            ps.setString(5, cleanId)
            ps.setNullableDouble(6, checkin.restingVoltage?.takeIf { it.isFinite() && it > 0.0 })
            ps.setNullableDouble(7, checkin.beakResistanceMilliohms?.takeIf { it.isFinite() && it > 0.0 })
            ps.setNullableDouble(8, checkin.stateOfChargePercent?.takeIf { it.isFinite() && it in 0.0..100.0 })
            ps.setString(9, checkin.inspectorInitials.trim().take(16))
            ps.setString(10, checkin.notes.trim().take(512))
            ps.setLong(11, checkin.recordedAtMs.coerceAtLeast(0L))
            ps.executeUpdate()
        }
    }

    suspend fun listBatteryCheckinsForWorkspace(
        teamId: String,
        seasonId: String,
        robotId: String,
        limit: Int = 500,
    ): List<BatteryCheckinRecord> = transactions.readMetadata {
        val bounded = limit.coerceIn(1, 2_000)
        readConn.prepareStatement(
            """
            SELECT checkin_id, team_id, season_id, robot_id, battery_id,
                   resting_voltage, beak_resistance_mohm, soc_percent,
                   inspector_initials, notes, recorded_at_ms
            FROM battery_checkins
            WHERE team_id = ? AND season_id = ? AND robot_id = ?
            ORDER BY recorded_at_ms DESC
            LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, teamId.trim())
            ps.setString(2, seasonId.trim())
            ps.setString(3, robotId.trim())
            ps.setInt(4, bounded)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toBatteryCheckinRecord())
                }
            }
        }
    }

    private fun ResultSet.toBatterySpecRecord(): BatterySpecRecord = BatterySpecRecord(
        teamId = getString("team_id"),
        seasonId = getString("season_id"),
        robotId = getString("robot_id"),
        batteryId = getString("battery_id"),
        label = getString("label"),
        nominalVoltage = getDouble("nominal_voltage"),
        capacityAh = getDouble("capacity_ah"),
        status = BatteryPoolStatus.fromStored(getString("status")),
        notes = getString("notes").orEmpty(),
        createdAtMs = getLong("created_at_ms"),
        updatedAtMs = getLong("updated_at_ms"),
    )

    private fun ResultSet.toBatteryCheckinRecord(): BatteryCheckinRecord = BatteryCheckinRecord(
        checkinId = getString("checkin_id"),
        teamId = getString("team_id"),
        seasonId = getString("season_id"),
        robotId = getString("robot_id"),
        batteryId = getString("battery_id"),
        restingVoltage = getNullableDouble("resting_voltage"),
        beakResistanceMilliohms = getNullableDouble("beak_resistance_mohm"),
        stateOfChargePercent = getNullableDouble("soc_percent"),
        inspectorInitials = getString("inspector_initials").orEmpty(),
        notes = getString("notes").orEmpty(),
        recordedAtMs = getLong("recorded_at_ms"),
    )

    private fun java.sql.PreparedStatement.setNullableDouble(index: Int, value: Double?) {
        if (value == null) setNull(index, Types.DOUBLE) else setDouble(index, value)
    }

    private fun ResultSet.getNullableDouble(column: String): Double? {
        val v = getDouble(column)
        return if (wasNull() || !v.isFinite()) null else v
    }
}
