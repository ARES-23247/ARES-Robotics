package com.ares.analytics.viewmodel.project

import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.areslib.project.*
import java.util.Locale

internal data class ProjectIdentityDraftValidation(
    val document: AresProjectMetadataDocument?,
    val fieldErrors: Map<ProjectIdentityField, String>,
    val generalErrors: List<String>,
)

internal fun validateProjectIdentityDraft(
    league: League,
    draft: ProjectIdentityDraft,
): ProjectIdentityDraftValidation {
    val errors = linkedMapOf<ProjectIdentityField, String>()
    val projectId = draft.projectId.trim()
    if (!projectId.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}"))) {
        errors[ProjectIdentityField.PROJECT_ID] =
            "Use a stable ID that starts with a letter and contains only letters, numbers, dot, underscore, or dash."
    }
    val teamId = draft.teamId.trim()
    if (!teamId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}"))) {
        errors[ProjectIdentityField.TEAM_ID] = "Use the team number or another stable team key."
    }
    val seasonId = draft.seasonId.trim()
    if (!seasonId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}"))) {
        errors[ProjectIdentityField.SEASON_ID] = "Use a stable season key such as 2026."
    }
    val robotId = draft.robotId.trim()
    if (!robotId.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}"))) {
        errors[ProjectIdentityField.ROBOT_ID] = "Use a stable robot key that starts with a letter."
    }
    val displayName = draft.displayName.trim()
    if (displayName.isEmpty() || displayName.length > 80) {
        errors[ProjectIdentityField.DISPLAY_NAME] = "Enter a robot name using 1 to 80 characters."
    }
    fun parse(field: ProjectIdentityField, raw: String, label: String): Double? {
        val value = raw.trim().toDoubleOrNull()
        if (value == null || !value.isFinite() || value <= 0.0) {
            errors[field] = "$label must be a positive number in meters."
            return null
        }
        return value
    }
    val robotLength = parse(ProjectIdentityField.ROBOT_LENGTH, draft.robotLengthMeters, "Robot length")
    val robotWidth = parse(ProjectIdentityField.ROBOT_WIDTH, draft.robotWidthMeters, "Robot width")
    val fieldLength = parse(ProjectIdentityField.FIELD_LENGTH, draft.fieldLengthMeters, "Field length")
    val fieldWidth = parse(ProjectIdentityField.FIELD_WIDTH, draft.fieldWidthMeters, "Field width")
    val xrpPort = if (league == League.XRP) draft.xrpLinkPort.trim().toIntOrNull().also { value ->
        if (value == null || value !in 1024..65535 || value == 5810) {
            errors[ProjectIdentityField.XRP_LINK_PORT] = "Use a port from 1024 to 65535 other than the NT4 port 5810."
        }
    } else null
    val xrpDeadman = if (league == League.XRP) draft.xrpDeadmanTimeoutMs.trim().toIntOrNull().also { value ->
        if (value == null || value !in 100..1_000) {
            errors[ProjectIdentityField.XRP_DEADMAN_TIMEOUT] = "Use a deadman timeout from 100 to 1000 milliseconds."
        }
    } else null
    val xrpBrownout = if (league == League.XRP) draft.xrpBrownoutThresholdVolts.trim().toDoubleOrNull().also { value ->
        if (value == null || !value.isFinite() || value !in 3.0..6.0) {
            errors[ProjectIdentityField.XRP_BROWNOUT_THRESHOLD] = "Use a brownout threshold from 3.0 to 6.0 volts."
        }
    } else null
    if (league == League.XRP && (draft.xrpSsid.isBlank() || draft.xrpSsid.length > 32)) {
        errors[ProjectIdentityField.XRP_SSID] = "Enter an XRP Wi-Fi network name using 1 to 32 characters."
    }
    if (errors.isNotEmpty()) return ProjectIdentityDraftValidation(null, errors, emptyList())

    val document = AresProjectMetadataDocument(
        projectId = projectId,
        identity = AresProjectIdentityDocument(teamId, seasonId, robotId, displayName),
        league = league.toAresLeague(),
        coordinateConvention = league.coordinateConvention(),
        robotLengthMeters = requireNotNull(robotLength),
        robotWidthMeters = requireNotNull(robotWidth),
        fieldLengthMeters = requireNotNull(fieldLength),
        fieldWidthMeters = requireNotNull(fieldWidth),
        authoringModel = draft.authoringModel,
        runtimeOptions = draft.runtimeOptions(league, xrpPort, xrpDeadman, xrpBrownout),
    )
    val generalErrors = validateAresProjectMetadata(document)
    return ProjectIdentityDraftValidation(document.takeIf { generalErrors.isEmpty() }, errors, generalErrors)
}

private fun ProjectIdentityDraft.runtimeOptions(
    league: League,
    xrpPort: Int?,
    xrpDeadman: Int?,
    xrpBrownout: Double?,
): AresRuntimeOptionsDocument = when (league) {
    League.FTC -> AresRuntimeOptionsDocument(
        ftc = AresFtcRuntimeOptionsDocument(ftcHubCommandTransport, ftcLimelightProxyEnabled),
    )
    League.XRP -> AresRuntimeOptionsDocument(
        xrp = AresXrpRuntimeOptionsDocument(
            wifiMode = xrpWifiMode,
            ssid = xrpSsid.trim(),
            port = requireNotNull(xrpPort),
            deadmanTimeoutMs = requireNotNull(xrpDeadman),
            brownoutThresholdVolts = requireNotNull(xrpBrownout),
        ),
    )
    League.FRC -> AresRuntimeOptionsDocument()
}

internal fun projectIdentityDraft(config: WorkspaceConfig, current: AresProjectMetadataDocument?): ProjectIdentityDraft {
    val field = defaultFieldDimensions(config.league)
    val ftcRuntime = current?.takeIf { it.league == AresLeague.FTC }?.requireFtcRuntimeOptions()
        ?: AresFtcRuntimeOptionsDocument()
    val xrpRuntime = current?.takeIf { it.league == AresLeague.XRP }?.requireXrpRuntimeOptions()
        ?: AresXrpRuntimeOptionsDocument()
    return ProjectIdentityDraft(
        projectId = current?.projectId ?: suggestedProjectId(config),
        teamId = current?.identity?.teamId ?: config.teamId,
        seasonId = current?.identity?.seasonId ?: config.seasonId,
        robotId = current?.identity?.robotId ?: config.robotId,
        displayName = current?.identity?.displayName ?: config.robotName.ifBlank { config.robotId },
        robotLengthMeters = current?.robotLengthMeters?.asInput() ?: config.robotLengthMeters?.asInput().orEmpty(),
        robotWidthMeters = current?.robotWidthMeters?.asInput() ?: config.robotWidthMeters?.asInput().orEmpty(),
        fieldLengthMeters = (current?.fieldLengthMeters ?: field.first).asInput(),
        fieldWidthMeters = (current?.fieldWidthMeters ?: field.second).asInput(),
        authoringModel = current?.authoringModel ?: AresProjectAuthoringModel.GUI_OWNED,
        ftcHubCommandTransport = ftcRuntime.hubCommandTransport,
        ftcLimelightProxyEnabled = ftcRuntime.limelightProxyEnabled,
        xrpWifiMode = xrpRuntime.wifiMode,
        xrpSsid = xrpRuntime.ssid,
        xrpLinkPort = xrpRuntime.port.toString(),
        xrpDeadmanTimeoutMs = xrpRuntime.deadmanTimeoutMs.toString(),
        xrpBrownoutThresholdVolts = xrpRuntime.brownoutThresholdVolts.asInput(),
    )
}

internal fun projectIdentityChanges(
    current: AresProjectMetadataDocument?,
    proposed: AresProjectMetadataDocument,
): List<ProjectIdentityChange> {
    fun changed(label: String, before: Any?, after: Any): ProjectIdentityChange? =
        if (before?.toString() == after.toString()) null
        else ProjectIdentityChange(label, before?.toString() ?: "missing", after.toString())
    val shared = listOfNotNull(
        changed("Stable project ID", current?.projectId, proposed.projectId),
        changed("Team ID", current?.identity?.teamId, proposed.identity.teamId),
        changed("Season ID", current?.identity?.seasonId, proposed.identity.seasonId),
        changed("Robot ID", current?.identity?.robotId, proposed.identity.robotId),
        changed("Robot display name", current?.identity?.displayName, proposed.identity.displayName),
        changed("League", current?.league, proposed.league),
        changed("Coordinate convention", current?.coordinateConvention, proposed.coordinateConvention),
        changed("Robot length (m)", current?.robotLengthMeters, proposed.robotLengthMeters),
        changed("Robot width (m)", current?.robotWidthMeters, proposed.robotWidthMeters),
        changed("Field length (m)", current?.fieldLengthMeters, proposed.fieldLengthMeters),
        changed("Field width (m)", current?.fieldWidthMeters, proposed.fieldWidthMeters),
        changed("Authoring model", current?.authoringModel, proposed.authoringModel),
    )
    val platform = when (proposed.league) {
        AresLeague.FTC -> listOfNotNull(
            changed("FTC hub command transport", current?.takeIf { it.league == AresLeague.FTC }?.requireFtcRuntimeOptions()?.hubCommandTransport, proposed.requireFtcRuntimeOptions().hubCommandTransport),
            changed("Limelight camera proxy", current?.takeIf { it.league == AresLeague.FTC }?.requireFtcRuntimeOptions()?.limelightProxyEnabled, proposed.requireFtcRuntimeOptions().limelightProxyEnabled),
        )
        AresLeague.XRP -> {
            val before = current?.takeIf { it.league == AresLeague.XRP }?.requireXrpRuntimeOptions()
            val after = proposed.requireXrpRuntimeOptions()
            listOfNotNull(
                changed("XRP Wi-Fi mode", before?.wifiMode, after.wifiMode),
                changed("XRP Wi-Fi network", before?.ssid, after.ssid),
                changed("XRP Link port", before?.port, after.port),
                changed("XRP deadman timeout (ms)", before?.deadmanTimeoutMs, after.deadmanTimeoutMs),
                changed("XRP brownout threshold (V)", before?.brownoutThresholdVolts, after.brownoutThresholdVolts),
            )
        }
        AresLeague.FRC -> emptyList()
    }
    return shared + platform
}

private fun suggestedProjectId(config: WorkspaceConfig): String {
    val raw = "team${config.teamId}-${config.robotId}-${config.seasonId}"
    val normalized = raw.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.', '_').take(64)
    return normalized.takeIf { it.firstOrNull()?.isLetter() == true } ?: "project-${normalized.take(56)}"
}

internal val STABLE_IDENTITY_FIELDS = setOf(
    ProjectIdentityField.PROJECT_ID,
    ProjectIdentityField.TEAM_ID,
    ProjectIdentityField.SEASON_ID,
    ProjectIdentityField.ROBOT_ID,
)

private fun defaultFieldDimensions(league: League): Pair<Double, Double> = when (league) {
    League.FTC -> 3.6576 to 3.6576
    League.FRC -> 16.541 to 8.211
    League.XRP -> 2.54 to 1.4224
}

internal fun League.toAresLeague(): AresLeague = when (this) {
    League.FTC -> AresLeague.FTC
    League.FRC -> AresLeague.FRC
    League.XRP -> AresLeague.XRP
}

private fun League.coordinateConvention(): AresCoordinateConvention = when (this) {
    League.FTC, League.XRP -> AresCoordinateConvention.CENTER_ORIGIN_CCW
    League.FRC -> AresCoordinateConvention.BLUE_CORNER_ORIGIN_CCW
}

private fun Double.asInput(): String = String.format(Locale.ROOT, "%.6f", this).trimEnd('0').trimEnd('.')
