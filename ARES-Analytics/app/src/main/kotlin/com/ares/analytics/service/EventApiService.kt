package com.ares.analytics.service

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ares.analytics.domain.strategy.EventMatchResult
import com.ares.analytics.domain.strategy.EventScoutingCodecAndExporters
import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Tournament match schedule and alliance configuration retrieved from official event APIs (The Blue Alliance / FIRST API).
 *
 * @property matchNumber Match sequence number (e.g., 12).
 * @property compLevel Competition level identifier string (`"qm"`, `"qf"`, `"sf"`, `"f"`).
 * @property redAlliance Ordered list of team key strings on the Red Alliance.
 * @property blueAlliance Ordered list of team key strings on the Blue Alliance.
 * @property scheduledTime Optional Unix timestamp ($ms$) of match start.
 */
@Serializable
data class MatchInfo(
    val matchNumber: Int,
    val compLevel: String,
    val redAlliance: List<String>,
    val blueAlliance: List<String>,
    val scheduledTime: Long? = null
)

/**
 * REST API client integration for fetching tournament event schedules and team alliance match pairings.
 *
 * Interoperates with FIRST FTC Events API and The Blue Alliance (TBA) REST endpoints over HTTP/Ktor Client,
 * mapping tournament match schedules to local telemetry session records.
 *
 * ### Thread Safety & Performance Guarantees:
 * All HTTP requests execute asynchronously on `Dispatchers.IO` using non-blocking Ktor client engines.
 *
 * @param httpClient Configured Ktor HTTP client instance with JSON content negotiation.
 *
 * @see MatchInfo
 * @see TeamApiService
 */
class EventApiService(
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(AppJson)
        }
    }
) {
    // Queries TBA (The Blue Alliance) API for FRC match schedules
    suspend fun fetchFrcEventSchedule(eventKey: String, apiKey: String): List<MatchInfo> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext emptyList()
        try {
            httpClient.prepareGet("https://www.thebluealliance.com/api/v3/event/$eventKey/matches/simple") {
                header("X-TBA-Auth-Key", apiKey)
            }.execute { response ->
                if (response.status == HttpStatusCode.OK) {
                    val matchesJson = response.body<JsonArray>()
                    matchesJson.mapNotNull { element ->
                        try {
                            val obj = element.jsonObject
                            val compLevel = obj["comp_level"]?.jsonPrimitive?.content ?: "qm"
                            val matchNumber = obj["match_number"]?.jsonPrimitive?.int ?: 0
                            val alliances = obj["alliances"]?.jsonObject
                            val redAllianceObj = alliances?.get("red")?.jsonObject
                            val redTeams = redAllianceObj?.get("team_keys")?.jsonArray?.map { it.jsonPrimitive.content.removePrefix("frc") } ?: emptyList()
                            val blueAllianceObj = alliances?.get("blue")?.jsonObject
                            val blueTeams = blueAllianceObj?.get("team_keys")?.jsonArray?.map { it.jsonPrimitive.content.removePrefix("frc") } ?: emptyList()
                            val time = obj["time"]?.jsonPrimitive?.longOrNull?.let { it * 1000 } // convert to ms

                            MatchInfo(
                                matchNumber = matchNumber,
                                compLevel = if (compLevel == "qm") "quals" else "elims",
                                redAlliance = redTeams,
                                blueAlliance = blueTeams,
                                scheduledTime = time
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }.sortedBy { it.matchNumber }
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // Queries TOA (The Orange Alliance) or FIRST FTC Events v2 API for FTC match schedules.
    suspend fun fetchFtcEventSchedule(
        eventKey: String,
        apiKey: String,
        seasonYear: Int? = null,
    ): List<MatchInfo> = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty()) return@withContext emptyList()
        if (apiKey.contains(":")) {
            val year = seasonYear ?: java.time.Year.now(java.time.ZoneOffset.UTC).value
            val official = fetchFtcOfficialSchedule(year, eventKey, apiKey)
            if (official.isNotEmpty()) return@withContext official
        }
        try {
            httpClient.prepareGet("https://theorangealliance.org/api/event/$eventKey/matches") {
                header("X-TOA-ApiKey", apiKey)
                header("X-Application-Name", "ARES-Analytics")
            }.execute { response ->
                if (response.status == HttpStatusCode.OK) {
                    val matchesJson = response.body<JsonArray>()
                    matchesJson.mapNotNull { element ->
                        try {
                            val obj = element.jsonObject
                            val matchNumber = obj["match_number"]?.jsonPrimitive?.int ?: 0
                            val name = obj["match_name"]?.jsonPrimitive?.content ?: ""
                            val compLevel = if (name.contains("Quals", ignoreCase = true) || name.contains("Qualification", ignoreCase = true)) "quals" else "elims"
                            val participants = obj["participants"]?.jsonArray ?: JsonArray(emptyList())
                            val redTeams = mutableListOf<String>()
                            val blueTeams = mutableListOf<String>()

                            participants.forEach { partElement ->
                                val pObj = partElement.jsonObject
                                val teamKey = pObj["team_key"]?.jsonPrimitive?.content ?: ""
                                val station = pObj["station"]?.jsonPrimitive?.int ?: 0
                                val isRed = station in 11..19
                                if (isRed) {
                                    redTeams.add(teamKey)
                                } else {
                                    blueTeams.add(teamKey)
                                }
                            }
                            val scheduledTimeStr = obj["scheduled_time"]?.jsonPrimitive?.contentOrNull
                            val timeMs = scheduledTimeStr?.let {
                                try {
                                    java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli()
                                } catch (e: Exception) {
                                    null
                                }
                            }

                            MatchInfo(
                                matchNumber = matchNumber,
                                compLevel = compLevel,
                                redAlliance = redTeams,
                                blueAlliance = blueTeams,
                                scheduledTime = timeMs
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }.sortedBy { it.matchNumber }
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Queries FIRST FTC Events API v2 (`https://ftc-api.firstinspires.org/v2.0/.../schedule/...`)
     * accepting either `username:token` or pre-encoded Base64 Basic authorization tokens.
     */
    suspend fun fetchFtcOfficialSchedule(
        seasonYear: Int,
        eventCode: String,
        rawAuth: String,
    ): List<MatchInfo> = withContext(Dispatchers.IO) {
        val cleanCode = eventCode.trim()
        val cleanAuth = rawAuth.trim()
        if (cleanCode.isEmpty() || cleanAuth.isEmpty()) return@withContext emptyList()
        val basicToken = if (cleanAuth.contains(":")) {
            java.util.Base64.getEncoder().encodeToString(cleanAuth.toByteArray(Charsets.UTF_8))
        } else {
            cleanAuth
        }
        val combined = mutableListOf<MatchInfo>()
        for (level in listOf("qual" to "quals", "playoff" to "elims")) {
            runCatching {
                httpClient.prepareGet("https://ftc-api.firstinspires.org/v2.0/$seasonYear/schedule/$cleanCode?tournamentLevel=${level.first}") {
                    header("Authorization", "Basic $basicToken")
                    header("Accept", "application/json")
                }.execute { response ->
                    if (response.status != HttpStatusCode.OK) return@execute
                    val root = response.body<JsonObject>()
                    val array = root["schedule"]?.jsonArray ?: JsonArray(emptyList())
                    val parsed = com.ares.analytics.domain.pit.EventScheduleCodec.parseJson(array.toString())
                    parsed.forEach { item ->
                        combined += MatchInfo(
                            matchNumber = item.matchNumber,
                            compLevel = level.second,
                            redAlliance = item.redAlliance,
                            blueAlliance = item.blueAlliance,
                            scheduledTime = item.scheduledTimeMs,
                        )
                    }
                }
            }
        }
        combined.distinctBy { "${it.compLevel}:${it.matchNumber}" }
    }

    /**
     * Fetches official FRC match scores & optional phase/penalty breakdowns from The Blue Alliance
     * (`/matches` with fallback to `/matches/simple`).
     */
    suspend fun fetchFrcEventMatchResults(eventKey: String, apiKey: String): List<EventMatchResult> =
        withContext(Dispatchers.IO) {
            val cleanKey = eventKey.trim()
            val cleanAuth = apiKey.trim()
            if (cleanKey.isEmpty() || cleanAuth.isEmpty()) return@withContext emptyList()
            val urls = listOf(
                "https://www.thebluealliance.com/api/v3/event/$cleanKey/matches",
                "https://www.thebluealliance.com/api/v3/event/$cleanKey/matches/simple",
            )
            for (url in urls) {
                val parsed = runCatching {
                    httpClient.prepareGet(url) {
                        header("X-TBA-Auth-Key", cleanAuth)
                        header("Accept", "application/json")
                    }.execute { response ->
                        if (response.status != HttpStatusCode.OK) emptyList()
                        else EventScoutingCodecAndExporters.parseExternalMatchResultsJson(response.bodyAsText(), League.FRC)
                    }
                }.getOrDefault(emptyList())
                if (parsed.isNotEmpty()) return@withContext parsed
            }
            emptyList()
        }

    /**
     * Fetches official FTC match scores from FIRST FTC Events v2 (`username:token`) or
     * The Orange Alliance (`/api/event/{key}/matches`).
     */
    suspend fun fetchFtcEventMatchResults(
        eventKey: String,
        apiKey: String,
        seasonYear: Int? = null,
    ): List<EventMatchResult> = withContext(Dispatchers.IO) {
        val cleanKey = eventKey.trim()
        val cleanAuth = apiKey.trim()
        if (cleanKey.isEmpty() || cleanAuth.isEmpty()) return@withContext emptyList()
        if (cleanAuth.contains(":")) {
            val year = seasonYear ?: java.time.Year.now(java.time.ZoneOffset.UTC).value
            val official = fetchFtcOfficialMatchResults(year, cleanKey, cleanAuth)
            if (official.isNotEmpty()) return@withContext official
        }
        runCatching {
            httpClient.prepareGet("https://theorangealliance.org/api/event/$cleanKey/matches") {
                header("X-TOA-ApiKey", cleanAuth)
                header("X-Application-Name", "ARES-Analytics")
                header("Accept", "application/json")
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) emptyList()
                else EventScoutingCodecAndExporters.parseExternalMatchResultsJson(response.bodyAsText(), League.FTC)
            }
        }.getOrDefault(emptyList())
    }

    /** Queries FIRST FTC Events API v2 `/v2.0/{seasonYear}/matches/{eventCode}` for qual and playoff results. */
    suspend fun fetchFtcOfficialMatchResults(
        seasonYear: Int,
        eventCode: String,
        rawAuth: String,
    ): List<EventMatchResult> = withContext(Dispatchers.IO) {
        val cleanCode = eventCode.trim()
        val cleanAuth = rawAuth.trim()
        if (cleanCode.isEmpty() || cleanAuth.isEmpty()) return@withContext emptyList()
        val basicToken = if (cleanAuth.contains(":")) {
            java.util.Base64.getEncoder().encodeToString(cleanAuth.toByteArray(Charsets.UTF_8))
        } else {
            cleanAuth
        }
        val combined = mutableListOf<EventMatchResult>()
        for (level in listOf("qual" to "quals", "playoff" to "elims")) {
            runCatching {
                httpClient.prepareGet("https://ftc-api.firstinspires.org/v2.0/$seasonYear/matches/$cleanCode?tournamentLevel=${level.first}") {
                    header("Authorization", "Basic $basicToken")
                    header("Accept", "application/json")
                }.execute { response ->
                    if (response.status != HttpStatusCode.OK) return@execute
                    val parsed = EventScoutingCodecAndExporters.parseExternalMatchResultsJson(response.bodyAsText(), League.FTC)
                    parsed.forEach { item -> combined += item.copy(compLevel = level.second) }
                }
            }
        }
        combined.distinctBy { it.normalizedKey() }
    }

    /** Convenience dispatcher syncing official match results for the active workspace configuration. */
    suspend fun fetchWorkspaceEventMatchResults(workspace: WorkspaceConfig): List<EventMatchResult> {
        val code = workspace.eventCode?.trim().orEmpty()
        if (code.isEmpty()) return emptyList()
        return when (workspace.league) {
            League.FRC -> fetchFrcEventMatchResults(code, workspace.tbaApiKey?.trim().orEmpty())
            League.FTC, League.XRP -> {
                val year = workspace.seasonId.take(4).toIntOrNull()
                fetchFtcEventMatchResults(code, workspace.toaApiKey?.trim().orEmpty(), year)
            }
        }
    }

    fun close() {
        httpClient.close()
    }
}
