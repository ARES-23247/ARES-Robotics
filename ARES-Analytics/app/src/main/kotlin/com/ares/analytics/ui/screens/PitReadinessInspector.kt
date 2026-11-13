package com.ares.analytics.ui.screens

import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.service.drivebase.toUiDrivebase
import com.ares.analytics.service.hardware.HardwareInventoryOwner
import com.ares.analytics.service.hardware.HardwareSetupService
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.util.ProjectLayout
import com.areslib.controls.ControllerInputPlatform
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PitMatchType(val label: String) {
    PRACTICE("Practice"),
    QUALIFICATION("Qual"),
    PLAYOFF("Playoff"),
}

enum class PitAllianceColor(val label: String) {
    UNASSIGNED("Unassigned"),
    BLUE("Blue"),
    RED("Red"),
}

data class PitMatchTag(
    val matchType: PitMatchType = PitMatchType.QUALIFICATION,
    val matchNumber: String = "",
    val alliance: PitAllianceColor = PitAllianceColor.UNASSIGNED,
    val inspectorInitials: String = "",
    val batteryId: String = "",
) {
    fun summaryBadge(): String {
        val numPart = matchNumber.trim().takeIf(String::isNotEmpty)?.let { " #$it" }.orEmpty()
        val alliancePart = if (alliance != PitAllianceColor.UNASSIGNED) " · ${alliance.label.uppercase(Locale.US)}" else ""
        val batteryPart = com.ares.analytics.domain.pit.normalizeBatteryId(batteryId)
            .takeIf(String::isNotEmpty)
            ?.let { " · $it" }
            .orEmpty()
        return "${matchType.label}$numPart$alliancePart$batteryPart"
    }
}

data class PitManualCheckItem(
    val id: String,
    val title: String,
    val detail: String,
    val checked: Boolean = false,
)

data class PitDriveDeviceSpec(
    val id: String,
    val displayName: String,
    val roleKey: String,
    val address: String,
    val addressDescription: String,
)

data class PitSubsystemSpec(
    val documentId: String,
    val displayName: String,
    val actuatorAndSensorIds: List<String>,
    val measurementFields: List<String>,
)

data class PitProjectSignalProfile(
    val league: League,
    val hasAresProject: Boolean,
    val projectLabel: String,
    val drivebaseKind: DrivebaseKind? = null,
    val drivebaseName: String? = null,
    val driveDevices: List<PitDriveDeviceSpec> = emptyList(),
    val subsystems: List<PitSubsystemSpec> = emptyList(),
    val routineCount: Int = 0,
    val autonomousCatalogPresent: Boolean = false,
    val descriptorErrors: List<String> = emptyList(),
) {
    companion object {
        fun leagueFallback(league: League, reason: String = "League default signals (no .ares project linked)"): PitProjectSignalProfile =
            PitProjectSignalProfile(
                league = league,
                hasAresProject = false,
                projectLabel = reason,
            )
    }
}

object PitReadinessInspector {

    private val receiptTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).withZone(ZoneOffset.UTC)

    fun defaultManualChecklist(league: League): List<PitManualCheckItem> {
        val bumperText = when (league) {
            League.FRC -> "Alliance bumpers mounted firmly, reversible fabric/numbers legal & matching assigned alliance."
            League.FTC -> "Alliance markers & team number plates mounted securely within 18-inch sizing cube."
            League.XRP -> "Chassis caster, wheel hubs, reflectance array & battery pack seated cleanly."
        }
        return listOf(
            PitManualCheckItem(
                id = "battery_physical",
                title = "Fresh battery strapped & main power seated",
                detail = "Competition pack strapped down; Anderson / XT30 / barrel connector & main breaker inspected.",
            ),
            PitManualCheckItem(
                id = "alliance_hardware",
                title = "Alliance bumpers / plates & sizing verified",
                detail = bumperText,
            ),
            PitManualCheckItem(
                id = "mechanisms_homed",
                title = "Mechanisms stowed & zeroed before power-on",
                detail = "Arms, elevators, intakes, and turrets physically in legal starting configuration; limit switches clear.",
            ),
            PitManualCheckItem(
                id = "ds_auto_selected",
                title = "Driver Station autonomous & alliance confirmed",
                detail = "Selected autonomous routine, alliance color, and delay double-checked with drive coach.",
            ),
            PitManualCheckItem(
                id = "starting_pose_preload",
                title = "Starting field pose & preload verified",
                detail = "Robot aligned to legal field reference marks with legal game-piece preload.",
            ),
            PitManualCheckItem(
                id = "fasteners_belts_wiring",
                title = "Fasteners, belts/chains & strain relief checked",
                detail = "No loose set screws, frayed belts, pinched CAN/sensor leads, or low pneumatic pressure.",
            ),
        )
    }

    suspend fun loadProjectProfile(
        workspace: WorkspaceConfig?,
        projectSession: ProjectSession? = null,
        hardwareSetupService: HardwareSetupService? = null,
    ): PitProjectSignalProfile = withContext(Dispatchers.IO) {
        val league = workspace?.league ?: League.FTC
        val path = workspace?.projectPath?.trim().orEmpty()
        if (path.isBlank()) {
            return@withContext PitProjectSignalProfile.leagueFallback(league)
        }
        val layoutErr = ProjectLayout.validationError(path, league)
        if (layoutErr != null) {
            return@withContext PitProjectSignalProfile.leagueFallback(
                league = league,
                reason = "${league.name} default signals ($layoutErr)",
            )
        }

        val session = projectSession ?: ProjectSession()
        val hwService = hardwareSetupService ?: HardwareSetupService()
        val platform = when (league) {
            League.FTC -> ControllerInputPlatform.FTC
            League.FRC -> ControllerInputPlatform.FRC
            League.XRP -> ControllerInputPlatform.XRP
        }

        val snapshotResult = runCatching {
            session.snapshot(projectPath = path, targetPlatform = platform, forceReload = true)
        }
        if (snapshotResult.exceptionOrNull() is CancellationException) {
            throw snapshotResult.exceptionOrNull() as CancellationException
        }
        val hwResult = runCatching { hwService.inspect(path, league) }
        if (hwResult.exceptionOrNull() is CancellationException) {
            throw hwResult.exceptionOrNull() as CancellationException
        }

        val docs = snapshotResult.getOrNull()?.documents
        val hw = hwResult.getOrNull()
        if (docs == null && hw == null) {
            return@withContext PitProjectSignalProfile.leagueFallback(league)
        }

        val query = docs?.query
        val uiDrivebase = runCatching { query?.drivetrains?.singleOrNull()?.toUiDrivebase() }.getOrNull()
        val driveItems = hw?.items.orEmpty()
            .filter { it.owner == HardwareInventoryOwner.DRIVEBASE }
            .map { item ->
                PitDriveDeviceSpec(
                    id = item.uid.removePrefix("drivebase:"),
                    displayName = item.displayName,
                    roleKey = item.roleKey,
                    address = item.address,
                    addressDescription = item.addressDescription,
                )
            }

        val subsystemSpecs = query?.subsystems.orEmpty().map { sub ->
            PitSubsystemSpec(
                documentId = sub.documentId,
                displayName = sub.displayName,
                actuatorAndSensorIds = sub.hardware.map { it.hardwareId },
                measurementFields = sub.hardware.flatMap { dev -> dev.measurements.map { it.fieldId } }.distinct(),
            )
        }

        val errors = buildList {
            docs?.diagnostics.orEmpty().forEach { add("${it.file.name}: ${it.message}") }
            hw?.errorIssues.orEmpty().forEach { add(it.message) }
        }.distinct()

        val hasAnyDescriptor = query?.metadata != null || uiDrivebase != null || subsystemSpecs.isNotEmpty() || driveItems.isNotEmpty()
        if (!hasAnyDescriptor) {
            return@withContext PitProjectSignalProfile.leagueFallback(league)
        }

        val labelParts = buildList {
            add(uiDrivebase?.displayName ?: uiDrivebase?.kind?.name ?: "${league.name} Drive")
            add("${subsystemSpecs.size} subsystem(s)")
            add("${query?.routines?.size ?: 0} routine(s)")
        }
        PitProjectSignalProfile(
            league = league,
            hasAresProject = true,
            projectLabel = ".ares Project: ${labelParts.joinToString(" · ")}",
            drivebaseKind = uiDrivebase?.kind,
            drivebaseName = uiDrivebase?.displayName,
            driveDevices = driveItems,
            subsystems = subsystemSpecs,
            routineCount = query?.routines?.size ?: 0,
            autonomousCatalogPresent = query?.autonomousCatalog != null,
            descriptorErrors = errors,
        )
    }

    /**
     * Evaluates passive, read-only NT4 signal observations tailored to [profile].
     * Never commands actuators or declares the robot match-ready.
     */
    internal fun evaluateProjectAwarePitReadiness(
        connected: Boolean,
        frames: Map<String, TelemetryFrame>,
        heartbeatAgeMs: Long?,
        profile: PitProjectSignalProfile,
    ): List<SelfTestStep> {
        data class Spec(val id: String, val name: String, val description: String, val evaluate: () -> Pair<SelfTestStatus, String>)

        val batteryWarnV = when (profile.league) {
            League.FRC -> 12.2
            League.FTC -> 12.0
            League.XRP -> 4.7
        }
        val normalizedFrames = HashMap<String, TelemetryFrame>(frames.size * 2)
        for ((k, v) in frames) {
            normalizedFrames[k.removePrefix("/")] = v
        }

        fun frameOf(vararg candidates: String): TelemetryFrame? =
            candidates.firstNotNullOfOrNull { normalizedFrames[it.removePrefix("/")] }

        val specs = mutableListOf<Spec>()

        // 1. League-aware Battery Voltage
        specs += Spec(
            id = "battery",
            name = "Battery voltage (${profile.league.name})",
            description = String.format(
                Locale.US,
                "Passive voltage check against %.1f V %s pit pre-match floor.",
                batteryWarnV,
                profile.league.name,
            ),
        ) {
            val v = TelemetryMetricCatalog.BATTERY_VOLTAGE.keys.firstNotNullOfOrNull { frameOf(it) }?.value
            when {
                v == null || !v.isFinite() -> SelfTestStatus.WAITING to "No finite battery-voltage topic received."
                v < batteryWarnV -> SelfTestStatus.WARNING to String.format(
                    Locale.US,
                    "%.2f V observed (below %.1f V %s pre-match target; swap or charge pack).",
                    v,
                    batteryWarnV,
                    profile.league.name,
                )
                else -> SelfTestStatus.OBSERVED to String.format(Locale.US, "%.2f V observed.", v)
            }
        }

        // 2. Control Loop Timing
        specs += Spec(
            id = "loop_timing",
            name = "Control loop timing",
            description = "Checks published loop execution period without altering robot scheduling.",
        ) {
            val loopMs = (TelemetryMetricCatalog.LOOP_TIME.keys + listOf("ARES/Telemetry/LoopTimeMs", "Robot/LoopTimeMs"))
                .firstNotNullOfOrNull { frameOf(it) }?.value
            when {
                loopMs == null || !loopMs.isFinite() -> SelfTestStatus.WAITING to "No loop-time topic received yet."
                loopMs > 20.0 -> SelfTestStatus.WARNING to String.format(Locale.US, "%.2f ms loop period (> 20.0 ms nominal).", loopMs)
                else -> SelfTestStatus.OBSERVED to String.format(Locale.US, "%.2f ms loop period observed.", loopMs)
            }
        }

        // 3. Drivebase Devices (Project-Aware or League Default)
        if (profile.driveDevices.isNotEmpty()) {
            profile.driveDevices.forEach { dev ->
                specs += Spec(
                    id = "drive_${dev.id}",
                    name = "Drive · ${dev.displayName}",
                    description = "Passive telemetry check (${dev.addressDescription}); never commands the actuator.",
                ) {
                    val tokens = buildDeviceTokens(dev.id, dev.address, dev.roleKey)
                    val matched = findMatchingTelemetry(normalizedFrames, tokens, ownerHint = "motors")
                    if (matched != null) {
                        SelfTestStatus.OBSERVED to "Observed on '${matched.key}' (${formatFrameSummary(matched)})."
                    } else {
                        SelfTestStatus.WAITING to "No telemetry topic received matching ${tokens.take(3).joinToString("/")}."
                    }
                }
            }
        } else {
            val defaultCorners = if (profile.league == League.XRP) {
                listOf("left" to "Left drive motor", "right" to "Right drive motor")
            } else {
                listOf(
                    "fl" to "Front-left drive signal",
                    "fr" to "Front-right drive signal",
                    "rl" to "Rear-left drive signal",
                    "rr" to "Rear-right drive signal",
                )
            }
            defaultCorners.forEach { (corner, title) ->
                specs += Spec(
                    id = "motor_$corner",
                    name = title,
                    description = "Confirms telemetry exists; it does not command or certify the motor.",
                ) {
                    val aliases = when (corner) {
                        "rl" -> listOf("rl", "bl")
                        "rr" -> listOf("rr", "br")
                        else -> listOf(corner)
                    }
                    val cur = aliases.firstNotNullOfOrNull { frameOf("Hardware/Motors/$it/CurrentAmps", "Hardware/Motors/$it/Current") }
                    val mot = aliases.firstNotNullOfOrNull { frameOf("Hardware/Motors/$it/Velocity", "Hardware/Motors/$it/Power") }
                    if (cur != null || mot != null) {
                        val parts = listOfNotNull(
                            cur?.value?.takeIf(Double::isFinite)?.let { String.format(Locale.US, "current %.2f A", it) },
                            mot?.value?.takeIf(Double::isFinite)?.let { String.format(Locale.US, "motion %.2f", it) },
                        ).joinToString()
                        SelfTestStatus.OBSERVED to if (parts.isBlank()) "Motor topics observed." else "Telemetry observed: $parts."
                    } else {
                        SelfTestStatus.WAITING to "No current, velocity, or power topic received for ${aliases.joinToString("/")}."
                    }
                }
            }
        }

        // 4. Configured .ares Subsystems
        profile.subsystems.forEach { sub ->
            specs += Spec(
                id = "subsystem_${sub.documentId}",
                name = "Subsystem · ${sub.displayName}",
                description = "Checks passive state/sensor topics for .ares subsystem '${sub.documentId}' (${sub.actuatorAndSensorIds.size} device(s)).",
            ) {
                val tokens = (listOf(sub.documentId, sub.displayName.replace(" ", "")) + sub.actuatorAndSensorIds + sub.measurementFields)
                    .map { it.lowercase(Locale.ROOT) }
                    .filter { it.length >= 2 }
                    .distinct()
                val match = normalizedFrames.values.firstOrNull { f ->
                    val k = f.key.lowercase(Locale.ROOT)
                    tokens.any { tok -> k.contains("subsystems/$tok") || k.startsWith("$tok/") || k.contains("/$tok/") }
                }
                if (match != null) {
                    SelfTestStatus.OBSERVED to "Observed '${match.key}' (${formatFrameSummary(match)})."
                } else {
                    SelfTestStatus.WAITING to "Waiting for subsystem topic (${sub.documentId} / ${sub.displayName})."
                }
            }
        }

        // 5. Localization & Vision
        specs += Spec(
            id = "localization",
            name = "Odometry & localization",
            description = "Checks published estimator/Pinpoint/IMU pose evidence without moving the robot.",
        ) {
            val pinpointStatus = frameOf("Drive/Pinpoint_Status")?.stringValue?.trim().orEmpty()
            val posePresent = listOf("ARES/EstimatedPose/0", "ARES/EstimatedPose/1", "ARES/EstimatedPose/2")
                .all { frameOf(it)?.value?.isFinite() == true } ||
                listOf("Drive/Odom_X", "Drive/Odom_Y", "Drive/Odom_Heading")
                    .all { frameOf(it)?.value?.isFinite() == true }
            when {
                pinpointStatus.contains("FAULT", true) || pinpointStatus.contains("DISCONNECT", true) ->
                    SelfTestStatus.WARNING to "Localization fault reported: $pinpointStatus"
                pinpointStatus.isNotBlank() && !pinpointStatus.equals("UNKNOWN", true) ->
                    SelfTestStatus.OBSERVED to "Robot reports: $pinpointStatus"
                posePresent -> SelfTestStatus.OBSERVED to "Finite estimator/odometry pose stream observed."
                else -> SelfTestStatus.WAITING to "No complete estimator or odometry pose received yet."
            }
        }

        specs += Spec(
            id = "vision",
            name = "Vision / AprilTag camera",
            description = "Checks published camera heartbeat/FPS without requiring an AprilTag in view.",
        ) {
            val fps = frameOf("Vision/Limelight/FPS", "Vision/FPS")?.value
            val anyVision = normalizedFrames.keys.any { it.startsWith("Vision/", ignoreCase = true) }
            when {
                fps != null && fps.isFinite() && fps >= 5.0 ->
                    SelfTestStatus.OBSERVED to String.format(Locale.US, "%.1f FPS observed.", fps)
                fps != null && fps.isFinite() ->
                    SelfTestStatus.WARNING to String.format(Locale.US, "Low camera rate: %.1f FPS (< 5.0 FPS).", fps)
                anyVision ->
                    SelfTestStatus.WARNING to "Vision topics present, but camera FPS is not published."
                else ->
                    SelfTestStatus.WAITING to "No Limelight or vision-health topic received."
            }
        }

        if (!connected) {
            return specs.map { spec ->
                SelfTestStep(
                    id = spec.id,
                    name = spec.name,
                    description = spec.description,
                    status = SelfTestStatus.WAITING,
                    details = "Connect to a robot or simulator to observe this signal.",
                )
            }
        }

        if (heartbeatAgeMs == null || heartbeatAgeMs !in 0L..HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS) {
            val staleDetail = if (heartbeatAgeMs == null) {
                "The network socket is connected, but no ARES telemetry-loop heartbeat has arrived."
            } else {
                "The last ARES telemetry-loop heartbeat is $heartbeatAgeMs ms old. Treat displayed values as stale."
            }
            return specs.map { spec ->
                SelfTestStep(
                    id = spec.id,
                    name = spec.name,
                    description = spec.description,
                    status = SelfTestStatus.WAITING,
                    details = staleDetail,
                )
            }
        }

        val evaluated = specs.map { spec ->
            val (status, detail) = spec.evaluate()
            SelfTestStep(spec.id, spec.name, spec.description, status, detail)
        }.toMutableList()

        if (profile.hasAresProject) {
            val autoSummary = if (profile.descriptorErrors.isNotEmpty()) {
                SelfTestStatus.WARNING to "${profile.descriptorErrors.size} .ares descriptor issue(s): ${profile.descriptorErrors.first()}"
            } else if (profile.routineCount == 0 && !profile.autonomousCatalogPresent) {
                SelfTestStatus.WARNING to "0 autonomous routines configured in .ares project."
            } else {
                SelfTestStatus.OBSERVED to "${profile.routineCount} routine(s), ${profile.subsystems.size} subsystem(s), catalog=${profile.autonomousCatalogPresent}."
            }
            evaluated += SelfTestStep(
                id = "ares_project_descriptors",
                name = ".ares project & autonomous catalog",
                description = "Local workspace descriptor check (offline static evidence; not robot flash verification).",
                status = autoSummary.first,
                details = autoSummary.second,
            )
        }

        return evaluated
    }

    internal fun buildPitReadinessMarkdownReceipt(
        workspace: WorkspaceConfig?,
        profile: PitProjectSignalProfile,
        matchTag: PitMatchTag,
        connected: Boolean,
        streamLive: Boolean,
        signalSteps: List<SelfTestStep>,
        manualChecklist: List<PitManualCheckItem>,
        generatedAtEpochMs: Long = System.currentTimeMillis(),
    ): String = buildString {
        appendLine("# ARES Pre-Match Pit Readiness Receipt — ${matchTag.summaryBadge()}")
        appendLine()
        appendLine("> **Safety Notice**: Read-only passive NT4 telemetry observation + human pit inspection. No actuators were pulsed, and signal presence alone does not certify mechanical readiness.")
        appendLine()
        appendLine("- **Generated**: ${receiptTimeFormatter.format(Instant.ofEpochMilli(generatedAtEpochMs))}")
        if (workspace != null) {
            appendLine("- **Workspace**: `${profile.league.name}` · Team `${workspace.teamId}` · Robot `${workspace.robotName.ifBlank { workspace.robotId }}`")
        } else {
            appendLine("- **League**: `${profile.league.name}`")
        }
        appendLine("- **Match Tag**: `${matchTag.summaryBadge()}`" +
            com.ares.analytics.domain.pit.normalizeBatteryId(matchTag.batteryId).takeIf(String::isNotEmpty)?.let { " · **Battery**: `$it`" }.orEmpty() +
            matchTag.inspectorInitials.trim().takeIf(String::isNotEmpty)?.let { " · **Inspector**: `$it`" }.orEmpty())
        appendLine("- **Project Profile**: ${profile.projectLabel}")
        appendLine("- **NT4 Link State**: ${when { !connected -> "OFFLINE"; streamLive -> "STREAM LIVE"; else -> "STREAM STALE" }}")
        appendLine()

        val observedCount = signalSteps.count { it.status == SelfTestStatus.OBSERVED }
        val warningCount = signalSteps.count { it.status == SelfTestStatus.WARNING }
        appendLine("## 1. Passive NT4 Signal Matrix ($observedCount/${signalSteps.size} Observed, $warningCount Warning)")
        appendLine()
        appendLine("| Check | Status | Evidence |")
        appendLine("|---|---|---|")
        signalSteps.forEach { step ->
            appendLine("| ${step.name.replace("|", "\\|")} | `${step.status.name}` | ${step.details.replace("|", "\\|")} |")
        }
        appendLine()

        val checkedCount = manualChecklist.count { it.checked }
        appendLine("## 2. Manual Pit Crew Physical Checklist ($checkedCount/${manualChecklist.size} Signed Off)")
        appendLine()
        manualChecklist.forEach { item ->
            val box = if (item.checked) "[x]" else "[ ]"
            appendLine("- $box **${item.title}** — ${item.detail}")
        }
    }

    private fun buildDeviceTokens(id: String, address: String, roleKey: String): List<String> {
        val roleAliases = when {
            roleKey.contains("FRONT_LEFT", true) -> listOf("fl", "frontleft", "front_left")
            roleKey.contains("FRONT_RIGHT", true) -> listOf("fr", "frontright", "front_right")
            roleKey.contains("BACK_LEFT", true) || roleKey.contains("REAR_LEFT", true) -> listOf("rl", "bl", "rearleft", "backleft")
            roleKey.contains("BACK_RIGHT", true) || roleKey.contains("REAR_RIGHT", true) -> listOf("rr", "br", "rearright", "backright")
            roleKey.contains("LEFT", true) -> listOf("left")
            roleKey.contains("RIGHT", true) -> listOf("right")
            roleKey.contains("IMU", true) || roleKey.contains("GYRO", true) -> listOf("imu", "gyro", "pigeon", "heading")
            else -> emptyList()
        }
        return (listOf(id, address) + roleAliases)
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun findMatchingTelemetry(
        frames: Map<String, TelemetryFrame>,
        tokens: List<String>,
        ownerHint: String,
    ): TelemetryFrame? {
        for (token in tokens) {
            frames.entries.firstOrNull { (key, _) ->
                val lower = key.lowercase(Locale.ROOT)
                val segments = lower.split('/', '_', '-')
                lower.contains("/$token/") || lower.endsWith("/$token") ||
                    (lower.contains(ownerHint) && token in segments)
            }?.value?.let { return it }
        }
        return null
    }

    private fun formatFrameSummary(frame: TelemetryFrame): String {
        val str = frame.stringValue?.trim()?.takeIf(String::isNotEmpty)
        return if (str != null) str else String.format(Locale.US, "%.2f", frame.value)
    }
}
