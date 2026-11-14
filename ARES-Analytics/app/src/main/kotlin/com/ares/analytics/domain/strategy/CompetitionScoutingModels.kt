package com.ares.analytics.domain.strategy

import com.ares.analytics.shared.models.League
import kotlinx.serialization.Serializable

/** Normalizes team identifiers across TBA (`frc1234`), FTC/TOA (`11115`), and user input. */
fun normalizeTeamNumber(raw: String): String =
    raw.trim()
        .removePrefix("frc")
        .removePrefix("FRC")
        .removePrefix("ftc")
        .removePrefix("FTC")
        .removePrefix("#")
        .trim()

@Serializable
enum class ScoutedDrivetrain(val label: String) {
    SWERVE("Swerve"),
    MECANUM("Mecanum"),
    TANK_6WD("Tank / 6WD"),
    OTHER("Other / Custom"),
    UNKNOWN("Unknown"),
}

@Serializable
enum class AutoStartZone(val label: String) {
    LEFT("Left / Amp-side"),
    CENTER("Center"),
    RIGHT("Right / Source-side"),
    FLEXIBLE("Flexible (Any Zone)"),
    UNKNOWN("Unknown"),
}

@Serializable
enum class EndgameTier(val label: String, val scoreWeight: Double) {
    NONE("None / Park Only", 0.15),
    PARK("Reliable Park", 0.35),
    LOW_CLIMB("Level 1 / Low Climb", 0.65),
    HIGH_CLIMB("High / Harmony Climb", 1.0),
    UNKNOWN("Unknown", 0.25),
}

@Serializable
enum class RobotRole(val label: String) {
    PRIMARY_SCORER("Primary Scorer"),
    HYBRID_COMPLEMENT("Hybrid / Support Scorer"),
    DEFENSE_SPECIALIST("Defense / Disruptor"),
    FEEDER_AMP("Feeder / Setup"),
    DEVELOPING("Developing"),
}

@Serializable
enum class ScoutingQuickTag(val label: String) {
    FAST_CYCLES("Fast Cycles"),
    ACCURATE_AUTO("Consistent Auto"),
    STRONG_DEFENSE("Strong Defense"),
    TIPPY("Tippy / High CoG"),
    BROWNOUT_PRONE("Brownout / Slowdown"),
    FOUL_RISK("Foul Prone"),
    CLUTCH_ENDGAME("Clutch Endgame"),
    COMMS_DROP("Comms / Disconnect"),
}

@Serializable
enum class PicklistTier(
    val rankOrder: Int,
    val label: String,
    val shortBadge: String,
) {
    TIER_1_FIRST_PICK(0, "Tier 1 — First Pick", "1st Pick"),
    TIER_2_SECOND_PICK(1, "Tier 2 — Complementary", "2nd Pick"),
    TIER_3_DEPTH(2, "Tier 3 — Depth", "Depth"),
    UNTIERED(3, "Unranked Pool", "Pool"),
    DO_NOT_PICK(4, "Do Not Pick (DNP)", "DNP"),
}

@Serializable
enum class AllianceDraftStatus(val label: String) {
    AVAILABLE("Available"),
    OUR_TEAM("Our Team"),
    ALLIANCE_CAPTAIN("Captain"),
    PICKED("Picked"),
}

@Serializable
enum class PicklistWeightPreset(
    val label: String,
    val npOprWeight: Double,
    val autoWeight: Double,
    val endgameWeight: Double,
    val synergyWeight: Double,
    val defenseDriverWeight: Double,
) {
    BALANCED_CAPTAIN("Balanced Captain", 0.42, 0.20, 0.16, 0.14, 0.08),
    FIRST_PICK_CEILING("1st-Pick Scoring Ceiling", 0.56, 0.22, 0.12, 0.06, 0.04),
    COMPLEMENTARY_SECOND_PICK("Complementary 2nd Pick", 0.26, 0.20, 0.22, 0.22, 0.10),
    SUPPORT_DEFENSE("Defense & Reliability", 0.18, 0.12, 0.22, 0.18, 0.30),
}

/**
 * Tournament match pairing and optional official score breakdown.
 *
 * [redFoulPointsAwarded] represents penalty points included inside [redTotalScore] that were awarded
 * to Red from Blue fouls (and vice-versa for [blueFoulPointsAwarded]), allowing clean NP-OPR calculation.
 */
@Serializable
data class EventMatchResult(
    val matchNumber: Int,
    val compLevel: String = "quals",
    val redTeams: List<String> = emptyList(),
    val blueTeams: List<String> = emptyList(),
    val redTotalScore: Int? = null,
    val blueTotalScore: Int? = null,
    val redFoulPointsAwarded: Int = 0,
    val blueFoulPointsAwarded: Int = 0,
    val redAutoPoints: Int? = null,
    val blueAutoPoints: Int? = null,
    val redTeleopPoints: Int? = null,
    val blueTeleopPoints: Int? = null,
    val redEndgamePoints: Int? = null,
    val blueEndgamePoints: Int? = null,
    val scheduledTimeMs: Long? = null,
    val source: String = "api",
    val updatedAtMs: Long = 0L,
) {
    val isPlayed: Boolean
        get() = redTotalScore != null && blueTotalScore != null && redTotalScore >= 0 && blueTotalScore >= 0

    fun redNonPenaltyScore(): Int? =
        redTotalScore?.takeIf { it >= 0 }?.let { (it - redFoulPointsAwarded.coerceAtLeast(0)).coerceAtLeast(0) }

    fun blueNonPenaltyScore(): Int? =
        blueTotalScore?.takeIf { it >= 0 }?.let { (it - blueFoulPointsAwarded.coerceAtLeast(0)).coerceAtLeast(0) }

    fun normalizedKey(): String = "${compLevel.trim().lowercase().ifBlank { "quals" }}:$matchNumber"
}

/** Pit-scouting robot profile keyed by normalized [teamNumber]. */
@Serializable
data class PitTeamProfile(
    val teamNumber: String,
    val teamNickname: String = "",
    val drivetrain: ScoutedDrivetrain = ScoutedDrivetrain.UNKNOWN,
    val preferredAutoZone: AutoStartZone = AutoStartZone.UNKNOWN,
    val endgameCapability: EndgameTier = EndgameTier.UNKNOWN,
    val primaryRole: RobotRole = RobotRole.PRIMARY_SCORER,
    val autoSummary: String = "",
    val notes: String = "",
    val scoutAuthor: String = "Pit Crew",
    val updatedAtMs: Long = 0L,
)

/** Per-match stand observation keyed by team, match, and [scoutAuthor] for lossless multi-scout merging. */
@Serializable
data class StandMatchObservation(
    val teamNumber: String,
    val compLevel: String = "quals",
    val matchNumber: Int,
    val autoAttempted: Boolean = true,
    val autoSucceeded: Boolean = false,
    val estimatedCycles: Int = 0,
    val endgameAchieved: EndgameTier = EndgameTier.NONE,
    val defenseRating: Int = 0,
    val driverRating: Int = 3,
    val diedOrCommsLost: Boolean = false,
    val quickTags: List<ScoutingQuickTag> = emptyList(),
    val notes: String = "",
    val scoutAuthor: String = "Scout",
    val updatedAtMs: Long = 0L,
) {
    fun mergeIdentityKey(): String =
        "${normalizeTeamNumber(teamNumber)}|${compLevel.trim().lowercase().ifBlank { "quals" }}|$matchNumber|" +
            scoutAuthor.trim().lowercase().ifBlank { "scout" }
}

/** Coach tier/pin/live-draft state for a candidate team in the Alliance Selection Picklist. */
@Serializable
data class TeamPicklistOverride(
    val teamNumber: String,
    val tier: PicklistTier = PicklistTier.UNTIERED,
    val pinnedRank: Int? = null,
    val draftStatus: AllianceDraftStatus = AllianceDraftStatus.AVAILABLE,
    val coachNote: String = "",
    val updatedAtMs: Long = 0L,
)

/** Portable offline-first scouting and event-analytics bundle stored per workspace & event code. */
@Serializable
data class EventScoutingBundle(
    val schemaVersion: Int = 1,
    val eventCode: String = "",
    val league: League = League.FTC,
    val selectedPreset: PicklistWeightPreset = PicklistWeightPreset.BALANCED_CAPTAIN,
    val matchResults: List<EventMatchResult> = emptyList(),
    val pitProfiles: List<PitTeamProfile> = emptyList(),
    val standObservations: List<StandMatchObservation> = emptyList(),
    val picklistOverrides: List<TeamPicklistOverride> = emptyList(),
    val updatedAtMs: Long = 0L,
)

/** Summary returned after merging an incoming student scout file or external match export. */
data class MergeScoutingSummary(
    val mergedMatches: Int,
    val mergedPitProfiles: Int,
    val mergedStandObservations: Int,
    val mergedPicklistOverrides: Int,
    val distinctScoutAuthors: Set<String>,
)
