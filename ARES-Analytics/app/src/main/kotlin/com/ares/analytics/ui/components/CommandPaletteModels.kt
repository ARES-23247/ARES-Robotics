package com.ares.analytics.ui.components

import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.domain.navigation.PitOperationsSubTab
import com.ares.analytics.domain.notebook.NotebookSectionParser
import com.ares.analytics.domain.pit.BatteryHealthSummary
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.Session
import com.ares.analytics.ui.help.GlossaryCatalog
import java.util.Locale

enum class CommandPaletteProjectCategory(val label: String) {
    STAGE("Robot Studio Stage"),
    SUBSYSTEM("Subsystem"),
    SUPERSTRUCTURE("Superstructure"),
    ROUTINE("Autonomous Routine"),
}

data class CommandPaletteProjectItem(
    val id: String,
    val label: String,
    val subtitle: String,
    val category: CommandPaletteProjectCategory,
    val target: NavigationTarget,
    val keywords: List<String> = emptyList(),
    val entityId: String? = null,
)

data class CommandPaletteRunItem(
    val sessionId: String,
    val label: String,
    val subtitle: String,
    val keywords: List<String> = emptyList(),
)

data class CommandPaletteNotebookItem(
    val entryId: String,
    val title: String,
    val subtitle: String,
    val keywords: List<String> = emptyList(),
)

data class CommandPalettePitTabItem(
    val tab: PitOperationsSubTab,
    val label: String,
    val subtitle: String,
    val keywords: List<String> = emptyList(),
)

data class CommandPaletteBatteryItem(
    val batteryId: String,
    val label: String,
    val subtitle: String,
    val gradeLabel: String,
    val keywords: List<String> = emptyList(),
)

data class CommandPaletteMatchItem(
    val matchNumber: Int,
    val compLevel: String,
    val label: String,
    val subtitle: String,
    val keywords: List<String> = emptyList(),
)

sealed interface CommandPaletteUnifiedEntry {
    val key: String
    val title: String
    val subtitle: String
    val badge: String

    data class RecentDestination(val target: NavigationTarget) : CommandPaletteUnifiedEntry {
        override val key: String = "recent-${target.name}"
        override val title: String = target.label
        override val subtitle: String = "${target.groupLabel()} · Recently visited"
        override val badge: String = "Recent"
    }

    data class PitTab(val item: CommandPalettePitTabItem) : CommandPaletteUnifiedEntry {
        override val key: String = "pit-tab-${item.tab.name}"
        override val title: String = item.label
        override val subtitle: String = item.subtitle
        override val badge: String = "Pit Ops"
    }

    data class BatteryPack(val item: CommandPaletteBatteryItem) : CommandPaletteUnifiedEntry {
        override val key: String = "battery-${item.batteryId}"
        override val title: String = item.label
        override val subtitle: String = item.subtitle
        override val badge: String = item.gradeLabel
    }

    data class ScheduledMatch(val item: CommandPaletteMatchItem) : CommandPaletteUnifiedEntry {
        override val key: String = "match-${item.compLevel}-${item.matchNumber}"
        override val title: String = item.label
        override val subtitle: String = item.subtitle
        override val badge: String = "Schedule"
    }

    data class RunReplay(val item: CommandPaletteRunItem, val isRecent: Boolean = false) : CommandPaletteUnifiedEntry {
        override val key: String = "run-${item.sessionId}"
        override val title: String = item.label
        override val subtitle: String = item.subtitle
        override val badge: String = if (isRecent) "Recent Run" else "Replay Run"
    }

    data class NotebookNote(val item: CommandPaletteNotebookItem, val isRecent: Boolean = false) : CommandPaletteUnifiedEntry {
        override val key: String = "note-${item.entryId}"
        override val title: String = item.title
        override val subtitle: String = item.subtitle
        override val badge: String = if (isRecent) "Recent Note" else "Notebook"
    }

    data class ProjectEntity(val item: CommandPaletteProjectItem) : CommandPaletteUnifiedEntry {
        override val key: String = "project-${item.id}"
        override val title: String = item.label
        override val subtitle: String = item.subtitle
        override val badge: String = item.category.label
    }

    data class ScreenDestination(val target: NavigationTarget) : CommandPaletteUnifiedEntry {
        override val key: String = "screen-${target.name}"
        override val title: String = target.label
        override val subtitle: String = target.groupLabel()
        override val badge: String = "Open"
    }

    data class GlossaryEntry(val term: String, val definition: String) : CommandPaletteUnifiedEntry {
        override val key: String = "glossary-$term"
        override val title: String = term
        override val subtitle: String = definition
        override val badge: String = "Glossary"
    }
}

fun defaultPitOperationsTabItems(): List<CommandPalettePitTabItem> = listOf(
    CommandPalettePitTabItem(
        tab = PitOperationsSubTab.READINESS,
        label = "Pit: ${PitOperationsSubTab.READINESS.label}",
        subtitle = "Pre-match passive NT4 hardware & signal sign-off checklist",
        keywords = listOf("pit", "readiness", "sign-off", "checklist", "receipt", "nt4"),
    ),
    CommandPalettePitTabItem(
        tab = PitOperationsSubTab.BATTERY_FLEET,
        label = "Pit: ${PitOperationsSubTab.BATTERY_FLEET.label}",
        subtitle = "Battery internal resistance (R_int), Battery Beak check-ins & sag grading",
        keywords = listOf("pit", "battery", "beak", "r_int", "resistance", "voltage", "sag", "pack"),
    ),
    CommandPalettePitTabItem(
        tab = PitOperationsSubTab.MATCH_QUEUE,
        label = "Pit: ${PitOperationsSubTab.MATCH_QUEUE.label}",
        subtitle = "Offline tournament schedule, alliances & pit turnaround queue",
        keywords = listOf("pit", "match", "schedule", "queue", "turnaround", "alliance", "quals"),
    ),
    CommandPalettePitTabItem(
        tab = PitOperationsSubTab.ALERT_THRESHOLDS,
        label = "Pit: ${PitOperationsSubTab.ALERT_THRESHOLDS.label}",
        subtitle = "Live brownout, thermal & loop-overrun fault alert thresholds",
        keywords = listOf("pit", "alert", "threshold", "brownout", "thermal", "warning", "overrun"),
    ),
)

fun buildCommandPaletteBatteryItems(summaries: List<BatteryHealthSummary>): List<CommandPaletteBatteryItem> =
    summaries.map { pack ->
        val resText = (pack.latestBeakMilliohms ?: pack.medianTelemetryMilliohms ?: pack.latestTelemetryMilliohms)
            ?.let { "${"%.1f".format(Locale.US, it)} mΩ" }
            ?: "No R_int"
        val minVoltText = (pack.latestRestingVoltage ?: pack.worstMinVoltage)
            ?.let { "${"%.2f".format(Locale.US, it)} V" }
            ?: "Unmeasured"
        CommandPaletteBatteryItem(
            batteryId = pack.batteryId,
            label = "Battery ${pack.batteryId}" + if (pack.label != pack.batteryId) " (${pack.label})" else "",
            subtitle = "${pack.status.label} · ${pack.hardwareCycleCount} cycles · $resText · $minVoltText",
            gradeLabel = pack.grade.label,
            keywords = listOf(
                "battery",
                "bat",
                "pack",
                pack.batteryId,
                pack.label,
                pack.status.label,
                pack.grade.label,
                pack.recommendation,
            ),
        )
    }

fun buildCommandPaletteMatchItems(
    matches: List<MatchInfo>,
    teamId: String = "",
): List<CommandPaletteMatchItem> {
    val cleanTeam = teamId.trim()
    return matches
        .filter { it.matchNumber > 0 }
        .sortedWith(compareBy<MatchInfo> { it.compLevel }.thenBy { it.matchNumber })
        .map { match ->
            val level = match.compLevel.ifBlank { "quals" }
            val ourSide = when {
                cleanTeam.isNotBlank() && match.redAlliance.any { it.equals(cleanTeam, ignoreCase = true) } -> "RED · "
                cleanTeam.isNotBlank() && match.blueAlliance.any { it.equals(cleanTeam, ignoreCase = true) } -> "BLUE · "
                else -> ""
            }
            val redStr = match.redAlliance.joinToString(", ").ifBlank { "—" }
            val blueStr = match.blueAlliance.joinToString(", ").ifBlank { "—" }
            CommandPaletteMatchItem(
                matchNumber = match.matchNumber,
                compLevel = level,
                label = "Match #${match.matchNumber} ($level)",
                subtitle = "${ourSide}Red [$redStr] vs Blue [$blueStr]",
                keywords = listOf(
                    "match",
                    "schedule",
                    "queue",
                    level,
                    "match ${match.matchNumber}",
                    "match #${match.matchNumber}",
                    "m#${match.matchNumber}",
                    "q${match.matchNumber}",
                    match.matchNumber.toString(),
                ) + match.redAlliance + match.blueAlliance,
            )
        }
}

fun buildCommandPaletteRunItems(sessions: List<Session>): List<CommandPaletteRunItem> =
    sessions.sortedByDescending(Session::createdAt).map { session ->
        val runTitle = session.matchNumber?.let { "Match #$it" } ?: "Run ${session.sessionId.take(8)}"
        val alliancePart = session.allianceColor?.uppercase() ?: "Practice"
        val tagsPart = if (session.tags.isEmpty()) "untagged" else session.tags.joinToString(", ")
        val matchTerms = session.matchNumber?.let { listOf("match $it", "match #$it", "m#$it", "$it") }.orEmpty()
        CommandPaletteRunItem(
            sessionId = session.sessionId,
            label = "$runTitle ($alliancePart)",
            subtitle = "Session ${session.sessionId.take(12)} · Tags: $tagsPart",
            keywords = listOf("run", "replay", "session", session.sessionId, alliancePart) + matchTerms + session.tags,
        )
    }

fun buildCommandPaletteNotebookItems(entries: List<EngineeringNotebookEntry>): List<CommandPaletteNotebookItem> =
    entries.sortedByDescending(EngineeringNotebookEntry::updatedAtMs).map { entry ->
        val parsed = NotebookSectionParser.parseMarkdown(entry.markdownBody)
        val title = parsed.title.ifBlank { entry.entryId }
        val tagsText = if (parsed.tags.isEmpty()) entry.entryType.name else parsed.tags.joinToString(", ")
        CommandPaletteNotebookItem(
            entryId = entry.entryId,
            title = title,
            subtitle = "${entry.entryId} · r${entry.revision} (${entry.reviewState.name}) · $tagsText",
            keywords = listOf(
                "notebook",
                "note",
                "portfolio",
                entry.entryId,
                entry.entryType.name,
                entry.reviewState.name,
                entry.markdownBody.take(400),
            ) + parsed.tags + listOfNotNull(entry.humanAuthorId) + entry.evidence.map { it.referenceId },
        )
    }

fun filterCommandPaletteRuns(
    query: String,
    items: List<CommandPaletteRunItem>,
): List<CommandPaletteRunItem> {
    val normalized = query.trim().lowercase(Locale.US)
    if (normalized.isEmpty()) return emptyList()
    return items.filter { item ->
        item.label.lowercase(Locale.US).contains(normalized) ||
            item.subtitle.lowercase(Locale.US).contains(normalized) ||
            item.keywords.any { it.lowercase(Locale.US).contains(normalized) }
    }.take(8)
}

fun filterCommandPaletteNotebookEntries(
    query: String,
    items: List<CommandPaletteNotebookItem>,
): List<CommandPaletteNotebookItem> {
    val normalized = query.trim().lowercase(Locale.US)
    if (normalized.isEmpty()) return emptyList()
    return items.filter { item ->
        item.title.lowercase(Locale.US).contains(normalized) ||
            item.subtitle.lowercase(Locale.US).contains(normalized) ||
            item.keywords.any { it.lowercase(Locale.US).contains(normalized) }
    }.take(8)
}

fun filterCommandPaletteBatteries(
    query: String,
    items: List<CommandPaletteBatteryItem>,
): List<CommandPaletteBatteryItem> {
    val normalized = query.trim().lowercase(Locale.US)
    if (normalized.isEmpty()) return emptyList()
    return items.filter { item ->
        item.batteryId.lowercase(Locale.US).contains(normalized) ||
            item.label.lowercase(Locale.US).contains(normalized) ||
            item.subtitle.lowercase(Locale.US).contains(normalized) ||
            item.keywords.any { it.lowercase(Locale.US).contains(normalized) }
    }.take(8)
}

fun filterCommandPaletteMatches(
    query: String,
    items: List<CommandPaletteMatchItem>,
): List<CommandPaletteMatchItem> {
    val normalized = query.trim().lowercase(Locale.US)
    if (normalized.isEmpty()) return emptyList()
    return items.filter { item ->
        item.label.lowercase(Locale.US).contains(normalized) ||
            item.subtitle.lowercase(Locale.US).contains(normalized) ||
            item.keywords.any { it.lowercase(Locale.US).contains(normalized) }
    }.take(8)
}

fun filterCommandPalettePitTabs(
    query: String,
    items: List<CommandPalettePitTabItem>,
): List<CommandPalettePitTabItem> {
    val normalized = query.trim().lowercase(Locale.US)
    if (normalized.isEmpty()) return items
    return items.filter { item ->
        item.label.lowercase(Locale.US).contains(normalized) ||
            item.subtitle.lowercase(Locale.US).contains(normalized) ||
            item.keywords.any { it.lowercase(Locale.US).contains(normalized) }
    }
}

fun defaultRobotStudioStageItems(): List<CommandPaletteProjectItem> = listOf(
    CommandPaletteProjectItem(
        id = "stage:identity",
        label = "1. Robot Identity & Dimensions",
        subtitle = "Robot Studio Stage 1 · Team, season, and bumper footprint",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.PROJECT_IDENTITY,
        keywords = listOf("identity", "dimensions", "footprint", "bumper", "stage 1"),
    ),
    CommandPaletteProjectItem(
        id = "stage:drivetrain",
        label = "2. Drivetrain Builder",
        subtitle = "Robot Studio Stage 2 · Swerve, mecanum, differential, and localization",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.DRIVEBASE_BUILDER,
        keywords = listOf("drivetrain", "drivebase", "swerve", "mecanum", "localization", "stage 2"),
    ),
    CommandPaletteProjectItem(
        id = "stage:subsystems",
        label = "3. Mechanisms / Subsystems",
        subtitle = "Robot Studio Stage 3 · Hardware, stateflow, PID, and commissioning",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.SUBSYSTEM_GEN,
        keywords = listOf("subsystem", "mechanism", "arm", "elevator", "intake", "shooter", "stage 3"),
    ),
    CommandPaletteProjectItem(
        id = "stage:wiring",
        label = "4. Hardware Ports & Pit Wiring",
        subtitle = "Robot Studio Stage 4 · CAN buses, REV Hub ports, and conflict audit",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.HARDWARE_SETUP,
        keywords = listOf("wiring", "ports", "can", "expansion hub", "control hub", "stage 4"),
    ),
    CommandPaletteProjectItem(
        id = "stage:superstructure",
        label = "5. Superstructure Coordinator",
        subtitle = "Robot Studio Stage 5 · Multi-subsystem postures, interlocks, and simulation lab",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.SUPERSTRUCTURE_STUDIO,
        keywords = listOf("superstructure", "posture", "interlock", "coordinator", "stage 5"),
    ),
    CommandPaletteProjectItem(
        id = "stage:controls",
        label = "6. TeleOp Controls",
        subtitle = "Robot Studio Stage 6 · Driver and operator gamepad bindings",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.CONTROLS,
        keywords = listOf("controls", "gamepad", "controller", "teleop", "bindings", "stage 6"),
    ),
    CommandPaletteProjectItem(
        id = "stage:autonomous",
        label = "7. Autonomous Routines",
        subtitle = "Robot Studio Stage 7 · Trajectories, action timeline, and scrubber",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.PATH_PLANNER,
        keywords = listOf("autonomous", "routine", "path", "trajectory", "stage 7"),
    ),
    CommandPaletteProjectItem(
        id = "stage:verification",
        label = "8. Verify & Build Readiness",
        subtitle = "Robot Studio Stage 8 · Codegen, safety checks, and Gradle verification",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.ROBOT_STUDIO,
        keywords = listOf("verify", "build", "readiness", "codegen", "stage 8"),
    ),
)

fun discoverProjectCommandPaletteItems(projectPath: String): List<CommandPaletteProjectItem> {
    val stages = defaultRobotStudioStageItems()
    if (projectPath.isBlank()) return stages
    val loaded = runCatching { AresProjectDocuments().load(projectPath).query }.getOrNull()
        ?: return stages

    val subsystemItems = loaded.subsystems.map { doc ->
        CommandPaletteProjectItem(
            id = "subsystem:${doc.documentId}",
            label = "Subsystem: ${doc.displayName}",
            subtitle = ".ares/subsystems/${doc.documentId}.aressubsystem · ${doc.template.name}",
            category = CommandPaletteProjectCategory.SUBSYSTEM,
            target = NavigationTarget.SUBSYSTEM_GEN,
            keywords = listOf(doc.documentId, doc.displayName, doc.template.name, "subsystem", "mechanism"),
            entityId = doc.documentId,
        )
    }

    val superstructureItems = loaded.superstructures.map { doc ->
        CommandPaletteProjectItem(
            id = "superstructure:${doc.superstructureId}",
            label = "Superstructure: ${doc.displayName}",
            subtitle = ".ares/superstructures/${doc.superstructureId}.aressuperstructure · ${doc.states.size} postures",
            category = CommandPaletteProjectCategory.SUPERSTRUCTURE,
            target = NavigationTarget.SUPERSTRUCTURE_STUDIO,
            keywords = listOf(doc.superstructureId, doc.displayName, "superstructure", "coordinator") +
                doc.states.map { it.displayName },
            entityId = doc.superstructureId,
        )
    }

    val routineItems = loaded.routines.map { doc ->
        CommandPaletteProjectItem(
            id = "routine:${doc.documentId}",
            label = "Routine: ${doc.name}",
            subtitle = ".ares/routines/${doc.documentId}.aresroutine · ${doc.steps.size} steps",
            category = CommandPaletteProjectCategory.ROUTINE,
            target = NavigationTarget.PATH_PLANNER,
            keywords = listOf(doc.documentId, doc.name, "routine", "autonomous", "auto"),
            entityId = doc.documentId,
        )
    }

    return stages + subsystemItems + superstructureItems + routineItems
}

fun filterCommandPaletteProjectItems(
    query: String,
    items: List<CommandPaletteProjectItem>,
): List<CommandPaletteProjectItem> {
    val normalized = query.trim().lowercase(Locale.US)
    if (normalized.isEmpty()) {
        return items.filter { it.category == CommandPaletteProjectCategory.STAGE }
    }
    return items.filter { item ->
        item.label.lowercase(Locale.US).contains(normalized) ||
            item.subtitle.lowercase(Locale.US).contains(normalized) ||
            item.category.label.lowercase(Locale.US).contains(normalized) ||
            item.keywords.any { it.lowercase(Locale.US).contains(normalized) }
    }
}

fun filterNavigationTargets(query: String, developerMode: Boolean): List<NavigationTarget> {
    val normalized = query.trim().lowercase(Locale.US)
    return availablePaletteTargets(developerMode).filter { target ->
        normalized.isEmpty() ||
            target.label.lowercase(Locale.US).contains(normalized) ||
            target.groupLabel().lowercase(Locale.US).contains(normalized) ||
            target.searchTerms().any { it.contains(normalized) }
    }
}

fun buildUnifiedCommandPaletteEntries(
    query: String,
    developerMode: Boolean,
    recentTargets: List<NavigationTarget> = emptyList(),
    projectItems: List<CommandPaletteProjectItem> = emptyList(),
    pitTabItems: List<CommandPalettePitTabItem> = defaultPitOperationsTabItems(),
    batteryItems: List<CommandPaletteBatteryItem> = emptyList(),
    matchItems: List<CommandPaletteMatchItem> = emptyList(),
    runItems: List<CommandPaletteRunItem> = emptyList(),
    notebookItems: List<CommandPaletteNotebookItem> = emptyList(),
): List<CommandPaletteUnifiedEntry> = buildList {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        recentTargets.take(5).forEach { add(CommandPaletteUnifiedEntry.RecentDestination(it)) }
        runItems.take(3).forEach { add(CommandPaletteUnifiedEntry.RunReplay(it, isRecent = true)) }
        notebookItems.take(3).forEach { add(CommandPaletteUnifiedEntry.NotebookNote(it, isRecent = true)) }
        batteryItems.take(2).forEach { add(CommandPaletteUnifiedEntry.BatteryPack(it)) }
        matchItems.take(2).forEach { add(CommandPaletteUnifiedEntry.ScheduledMatch(it)) }
        pitTabItems.forEach { add(CommandPaletteUnifiedEntry.PitTab(it)) }
        projectItems.filter { it.category == CommandPaletteProjectCategory.STAGE }
            .forEach { add(CommandPaletteUnifiedEntry.ProjectEntity(it)) }
        availablePaletteTargets(developerMode)
            .filterNot { it in recentTargets }
            .forEach { add(CommandPaletteUnifiedEntry.ScreenDestination(it)) }
    } else {
        filterCommandPaletteBatteries(trimmed, batteryItems)
            .forEach { add(CommandPaletteUnifiedEntry.BatteryPack(it)) }
        filterCommandPaletteMatches(trimmed, matchItems)
            .forEach { add(CommandPaletteUnifiedEntry.ScheduledMatch(it)) }
        filterCommandPaletteRuns(trimmed, runItems)
            .forEach { add(CommandPaletteUnifiedEntry.RunReplay(it)) }
        filterCommandPaletteNotebookEntries(trimmed, notebookItems)
            .forEach { add(CommandPaletteUnifiedEntry.NotebookNote(it)) }
        filterCommandPalettePitTabs(trimmed, pitTabItems)
            .forEach { add(CommandPaletteUnifiedEntry.PitTab(it)) }
        filterCommandPaletteProjectItems(trimmed, projectItems)
            .forEach { add(CommandPaletteUnifiedEntry.ProjectEntity(it)) }
        filterNavigationTargets(trimmed, developerMode)
            .forEach { add(CommandPaletteUnifiedEntry.ScreenDestination(it)) }
        GlossaryCatalog.search(trimmed).take(5)
            .forEach { add(CommandPaletteUnifiedEntry.GlossaryEntry(it.term, it.definition)) }
    }
}
