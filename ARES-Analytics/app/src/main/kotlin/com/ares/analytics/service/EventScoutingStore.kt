package com.ares.analytics.service

import com.ares.analytics.domain.strategy.EventMatchResult
import com.ares.analytics.domain.strategy.EventScoutingBundle
import com.ares.analytics.domain.strategy.EventScoutingCodecAndExporters
import com.ares.analytics.domain.strategy.MergeScoutingSummary
import com.ares.analytics.domain.strategy.PicklistWeightPreset
import com.ares.analytics.domain.strategy.PitTeamProfile
import com.ares.analytics.domain.strategy.StandMatchObservation
import com.ares.analytics.domain.strategy.TeamPicklistOverride
import com.ares.analytics.domain.strategy.normalizeTeamNumber
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/** Converts an [EventMatchResult] into a schedule [MatchInfo] row when backfilling local schedules. */
fun EventMatchResult.toMatchInfo(): MatchInfo = MatchInfo(
    matchNumber = matchNumber,
    compLevel = compLevel,
    redAlliance = redTeams,
    blueAlliance = blueTeams,
    scheduledTime = scheduledTimeMs,
)

/**
 * Offline-first atomic JSON repository for tournament match results, pit scouting profiles,
 * multi-scout stand observations, and Alliance Selection picklist overrides.
 */
class EventScoutingStore(
    private val baseDir: File = AppDataPaths.file("scouting"),
) {
    private val ioLock = Any()

    fun scoutingFileFor(workspace: WorkspaceConfig): File {
        val rawWs = workspace.id.ifBlank {
            "${workspace.league}-${workspace.teamId}-${workspace.robotId}-${workspace.seasonId}"
        }
        val eventPart = workspace.eventCode?.trim()?.takeIf(String::isNotBlank) ?: "local-event"
        val safeName = "${rawWs}__$eventPart"
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "default-scouting" }
        return File(baseDir, "$safeName.json")
    }

    suspend fun loadBundle(workspace: WorkspaceConfig): EventScoutingBundle = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            loadLocked(workspace)
        }
    }

    suspend fun saveBundle(
        workspace: WorkspaceConfig,
        bundle: EventScoutingBundle,
    ): EventScoutingBundle = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            saveLocked(workspace, bundle)
        }
    }

    suspend fun upsertMatchResults(
        workspace: WorkspaceConfig,
        incomingResults: List<EventMatchResult>,
    ): EventScoutingBundle = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            val current = loadLocked(workspace)
            val incoming = EventScoutingBundle(
                eventCode = workspace.eventCode.orEmpty(),
                league = workspace.league,
                matchResults = incomingResults,
                updatedAtMs = System.currentTimeMillis(),
            )
            val (merged, _) = EventScoutingCodecAndExporters.mergeBundles(current, incoming)
            saveLocked(workspace, merged)
        }
    }

    suspend fun upsertPitProfile(
        workspace: WorkspaceConfig,
        profile: PitTeamProfile,
    ): EventScoutingBundle = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            val current = loadLocked(workspace)
            val clean = normalizeTeamNumber(profile.teamNumber)
            require(clean.isNotBlank()) { "Team number is required." }
            val updatedProfile = profile.copy(
                teamNumber = clean,
                updatedAtMs = profile.updatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
            val updatedList = current.pitProfiles.filterNot { normalizeTeamNumber(it.teamNumber) == clean } + updatedProfile
            saveLocked(
                workspace,
                current.copy(
                    pitProfiles = updatedList.sortedBy { it.teamNumber.toIntOrNull() ?: Int.MAX_VALUE },
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun upsertStandObservation(
        workspace: WorkspaceConfig,
        observation: StandMatchObservation,
    ): EventScoutingBundle = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            val current = loadLocked(workspace)
            val clean = normalizeTeamNumber(observation.teamNumber)
            require(clean.isNotBlank()) { "Team number is required." }
            val normalizedObs = observation.copy(
                teamNumber = clean,
                scoutAuthor = observation.scoutAuthor.trim().ifBlank { "Scout" },
                updatedAtMs = observation.updatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
            val incoming = EventScoutingBundle(
                eventCode = workspace.eventCode.orEmpty(),
                league = workspace.league,
                standObservations = listOf(normalizedObs),
                updatedAtMs = normalizedObs.updatedAtMs,
            )
            val (merged, _) = EventScoutingCodecAndExporters.mergeBundles(current, incoming)
            saveLocked(workspace, merged)
        }
    }

    suspend fun upsertPicklistOverride(
        workspace: WorkspaceConfig,
        override: TeamPicklistOverride,
    ): EventScoutingBundle = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            val current = loadLocked(workspace)
            val clean = normalizeTeamNumber(override.teamNumber)
            require(clean.isNotBlank()) { "Team number is required." }
            val updated = override.copy(
                teamNumber = clean,
                updatedAtMs = override.updatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
            val nextList = current.picklistOverrides.filterNot { normalizeTeamNumber(it.teamNumber) == clean } + updated
            saveLocked(
                workspace,
                current.copy(
                    picklistOverrides = nextList.sortedBy { it.teamNumber.toIntOrNull() ?: Int.MAX_VALUE },
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun updateWeightPreset(
        workspace: WorkspaceConfig,
        preset: PicklistWeightPreset,
    ): EventScoutingBundle = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            val current = loadLocked(workspace)
            saveLocked(workspace, current.copy(selectedPreset = preset, updatedAtMs = System.currentTimeMillis()))
        }
    }

    suspend fun importAndMergeFile(
        workspace: WorkspaceConfig,
        file: File,
    ): Pair<EventScoutingBundle, MergeScoutingSummary> = withContext(Dispatchers.IO) {
        require(file.isFile) { "Scouting file does not exist: ${file.name}" }
        val text = file.readText(StandardCharsets.UTF_8)
        val trimmed = text.trimStart('\uFEFF').trimStart()
        val incoming = if (
            file.extension.equals("json", ignoreCase = true) ||
            trimmed.startsWith("{") ||
            trimmed.startsWith("[")
        ) {
            EventScoutingCodecAndExporters.decodeBundleOrExternalJson(
                rawText = text,
                fallbackLeague = workspace.league,
                fallbackEventCode = workspace.eventCode.orEmpty(),
            )
        } else {
            EventScoutingCodecAndExporters.parseScoutingCsv(text, workspace.league)
        }
        synchronized(ioLock) {
            val current = loadLocked(workspace)
            val (merged, summary) = EventScoutingCodecAndExporters.mergeBundles(current, incoming)
            val saved = saveLocked(workspace, merged)
            saved to summary
        }
    }

    suspend fun exportBundleFile(workspace: WorkspaceConfig, destination: File) = withContext(Dispatchers.IO) {
        val encoded = synchronized(ioLock) {
            EventScoutingCodecAndExporters.encodeBundleJson(loadLocked(workspace))
        }
        writeFileAtomically(destination) { tmp ->
            tmp.writeText(encoded, StandardCharsets.UTF_8)
        }
    }

    private fun loadLocked(workspace: WorkspaceConfig): EventScoutingBundle {
        val file = scoutingFileFor(workspace)
        if (!file.isFile) {
            return EventScoutingBundle(
                eventCode = workspace.eventCode.orEmpty(),
                league = workspace.league,
            )
        }
        val raw = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull().orEmpty()
        return runCatching {
            EventScoutingCodecAndExporters.decodeBundleOrExternalJson(
                rawText = raw,
                fallbackLeague = workspace.league,
                fallbackEventCode = workspace.eventCode.orEmpty(),
            )
        }.getOrElse {
            EventScoutingBundle(
                eventCode = workspace.eventCode.orEmpty(),
                league = workspace.league,
            )
        }
    }

    private fun saveLocked(
        workspace: WorkspaceConfig,
        bundle: EventScoutingBundle,
    ): EventScoutingBundle {
        val normalized = bundle.copy(
            eventCode = bundle.eventCode.ifBlank { workspace.eventCode.orEmpty() },
            league = workspace.league,
        )
        val encoded = EventScoutingCodecAndExporters.encodeBundleJson(normalized)
        val file = scoutingFileFor(workspace)
        writeFileAtomically(file) { tmp ->
            tmp.writeText(encoded, StandardCharsets.UTF_8)
        }
        return normalized
    }
}
