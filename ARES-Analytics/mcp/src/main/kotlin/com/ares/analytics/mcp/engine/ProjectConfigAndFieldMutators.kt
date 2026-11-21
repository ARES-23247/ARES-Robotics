package com.ares.analytics.mcp.engine

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineValidationSeverity
import com.areslib.routine.validateAutonomousCatalog
import com.areslib.state.AprilTagMapCodec
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldAprilTag
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldObstacle
import com.areslib.state.RobotFieldValidator
import com.areslib.state.RobotFieldWaypoint
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ConfigOrFieldMutationResult(
    val projectRoot: String,
    val configKind: String,
    val operation: String,
    val filePath: String,
    val pendingConfirmation: Boolean = false,
    val confirmationToken: String? = null,
    val previousRevision: String? = null,
    val newRevision: String? = null,
    val snapshotRef: String? = null,
    val itemCount: Int = 0,
    val documentJson: String? = null,
    val backupPath: String? = null,
    val validationSummary: ProjectValidationSummary? = null,
    val message: String,
)

/**
 * Manages canonical `.ares/project.json`, `.ares/action-catalog.json`, `.ares/autonomous-catalog.json`,
 * and canonical league-specific `field.json` (presets + WPILib/Limelight AprilTag import + history backups).
 */
internal class ProjectConfigAndFieldMutators(
    private val engine: HeadlessProjectEngine,
    private val templateAndPatch: TemplateAndPatchSupport,
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun mutateProjectConfig(
        projectRoot: File,
        configKindRaw: String,
        operationRaw: String,
        documentJson: String? = null,
        patchJson: String? = null,
        targetKeyOrEntryId: String? = null,
        confirmationToken: String? = null,
        expectedRevision: String? = null,
    ): ConfigOrFieldMutationResult {
        val canonical = projectRoot.canonicalFile
        val kind = configKindRaw.trim().uppercase()
        val op = operationRaw.trim().uppercase()
        return when (kind) {
            "PROJECT_METADATA" -> mutateProjectMetadata(canonical, op, documentJson, patchJson, expectedRevision)
            "ACTION_CATALOG" -> mutateActionCatalog(
                canonical, op, documentJson, patchJson, targetKeyOrEntryId, confirmationToken, expectedRevision,
            )
            "AUTONOMOUS_CATALOG" -> mutateAutonomousCatalog(
                canonical, op, documentJson, patchJson, targetKeyOrEntryId, confirmationToken, expectedRevision,
            )
            else -> throw IllegalArgumentException(
                "Unsupported configKind '$configKindRaw'; expected PROJECT_METADATA, ACTION_CATALOG, or AUTONOMOUS_CATALOG.",
            )
        }
    }

    suspend fun mutateField(
        projectRoot: File,
        operationRaw: String,
        presetName: String? = null,
        aprilTagSourceJson: String? = null,
        aprilTagSourcePath: String? = null,
        defaultFamily: String? = null,
        defaultTagSizeMeters: Double? = null,
        documentJson: String? = null,
        patchJson: String? = null,
        expectedRevision: String? = null,
    ): ConfigOrFieldMutationResult {
        val canonical = projectRoot.canonicalFile
        val bundle = engine.loadBundle(canonical)
        val metadata = requireNotNull(bundle.metadata) { "Missing .ares/project.json" }
        val league = metadata.league
        val fieldFile = ProjectCodegenConventions.fieldDefinitionFile(canonical, league)
        val fieldRelPath = fieldFile.relativeTo(canonical).invariantSeparatorsPath
        val expectedFieldType = league.toFieldType()
        val op = operationRaw.trim().uppercase()

        val currentConfig = if (fieldFile.isFile) {
            RobotFieldDocument.decode(fieldFile.readText())
        } else {
            defaultFieldConfig(league)
        }

        if (op == "INSPECT") {
            val encoded = RobotFieldDocument.encode(currentConfig)
            val issues = RobotFieldValidator.validate(currentConfig, expectedFieldType)
            return ConfigOrFieldMutationResult(
                projectRoot = canonical.path,
                configKind = "FIELD",
                operation = "INSPECT",
                filePath = fieldRelPath,
                previousRevision = bundle.revision,
                itemCount = currentConfig.apriltags.size,
                documentJson = encoded,
                message = buildString {
                    append("Field (${expectedFieldType.name}) at $fieldRelPath: ")
                    append("${currentConfig.apriltags.size} AprilTags, ")
                    append("${currentConfig.obstacles.size} obstacles, ")
                    append("${currentConfig.fieldWaypoints.size} waypoints")
                    if (!fieldFile.isFile) append(" (not yet written on disk; showing default layout)")
                    if (issues.isNotEmpty()) append(". Validation issues: ${issues.joinToString("; ") { it.message }}")
                },
            )
        }

        val updatedConfig: RobotFieldConfig = when (op) {
            "INSTALL_PRESET" -> {
                val preset = (presetName ?: "LEAGUE_DEFAULT").trim().uppercase()
                applyFieldPreset(currentConfig, preset, league)
            }
            "IMPORT_APRILTAGS" -> {
                val rawSource = when {
                    !aprilTagSourceJson.isNullOrBlank() -> aprilTagSourceJson
                    !aprilTagSourcePath.isNullOrBlank() -> {
                        val sourceFile = JGitSnapshotSafety.safeResolveChild(canonical, aprilTagSourcePath)
                        require(sourceFile.isFile) { "AprilTag map file not found at '$aprilTagSourcePath'." }
                        sourceFile.readText()
                    }
                    else -> throw IllegalArgumentException(
                        "IMPORT_APRILTAGS requires either aprilTagSourceJson or a project-relative aprilTagSourcePath.",
                    )
                }
                val baseForTransform = currentConfig.copy(
                    fieldType = expectedFieldType,
                    widthMeters = currentConfig.widthMeters.takeIf { it > 0.0 } ?: 0.0,
                    heightMeters = currentConfig.heightMeters.takeIf { it > 0.0 } ?: 0.0,
                )
                val imported = AprilTagMapCodec.decodeForField(rawSource, baseForTransform)
                val fallbackFamily = defaultFamily?.trim()?.takeIf { it.isNotBlank() } ?: "36h11"
                val fallbackSize = defaultTagSizeMeters ?: defaultTagSizeForLeague(league)
                val normalizedTags = imported.tags.map { tag ->
                    tag.copy(
                        name = tag.name.ifBlank { "Tag ${tag.id}" },
                        family = tag.family.ifBlank { fallbackFamily },
                        sizeMeters = tag.sizeMeters ?: fallbackSize,
                        editorId = tag.editorId.ifBlank { "apriltag-${tag.id}" },
                    )
                }
                baseForTransform.copy(
                    revision = baseForTransform.revision + 1L,
                    apriltags = normalizedTags,
                )
            }
            "UPSERT" -> {
                val raw = requireNotNull(documentJson) { "documentJson is required for FIELD UPSERT." }
                RobotFieldDocument.decode(raw)
            }
            "PATCH" -> {
                val patch = requireNotNull(patchJson) { "patchJson is required for FIELD PATCH." }
                val baseJson = RobotFieldDocument.encode(currentConfig)
                val merged = templateAndPatch.applyJsonMergePatchPreservingUids(baseJson, patch)
                RobotFieldDocument.decode(merged)
            }
            else -> throw IllegalArgumentException(
                "Unsupported FIELD operation '$operationRaw'; expected INSPECT, INSTALL_PRESET, IMPORT_APRILTAGS, UPSERT, or PATCH.",
            )
        }

        val fieldIssues = RobotFieldValidator.validate(updatedConfig, requiredFieldType = expectedFieldType)
        require(fieldIssues.isEmpty()) {
            "Field validation failed: " + fieldIssues.joinToString("; ") { "${it.code}: ${it.message}" }
        }
        val encoded = RobotFieldDocument.encode(updatedConfig)
        val writes = linkedMapOf(fieldRelPath to encoded)
        var backupRelPath: String? = null
        if (fieldFile.isFile) {
            val existingText = fieldFile.readText()
            val shaPrefix = ConfirmationTokenManager.sha256Hex(existingText).take(16)
            backupRelPath = ".ares/history/fields/$shaPrefix.field.json"
            writes[backupRelPath] = existingText
        }

        val batchResult = TemplateAndPatchSupport.applyTransactionalFileBatch(
            engine = engine,
            projectRoot = canonical,
            slug = "field-$op",
            documentKind = "FIELD",
            operation = op,
            documentId = updatedConfig.id.ifBlank { "field" },
            primaryRelativePath = fieldRelPath,
            writes = writes,
            expectedRevision = expectedRevision,
            message = "Updated canonical field ($op) at $fieldRelPath with ${updatedConfig.apriltags.size} AprilTag(s).",
        )
        return ConfigOrFieldMutationResult(
            projectRoot = canonical.path,
            configKind = "FIELD",
            operation = op,
            filePath = fieldRelPath,
            previousRevision = batchResult.previousRevision,
            newRevision = batchResult.newRevision,
            snapshotRef = batchResult.snapshotRef,
            itemCount = updatedConfig.apriltags.size,
            backupPath = backupRelPath,
            validationSummary = batchResult.validation,
            message = batchResult.message,
        )
    }

    private suspend fun mutateProjectMetadata(
        canonical: File,
        op: String,
        documentJson: String?,
        patchJson: String?,
        expectedRevision: String?,
    ): ConfigOrFieldMutationResult {
        val relPath = ".ares/project.json"
        val file = JGitSnapshotSafety.safeResolveChild(canonical, relPath)
        require(file.isFile) { "Missing .ares/project.json" }
        val existingDoc = AresProjectMetadataCodec.decode(file.readText())
        val currentEncoded = AresProjectMetadataCodec.encode(existingDoc)
        if (op == "INSPECT") {
            return ConfigOrFieldMutationResult(
                projectRoot = canonical.path,
                configKind = "PROJECT_METADATA",
                operation = "INSPECT",
                filePath = relPath,
                previousRevision = engine.computeProjectRevision(canonical),
                documentJson = currentEncoded,
                message = "Loaded .ares/project.json for ${existingDoc.projectId} (${existingDoc.league.name}, ${existingDoc.authoringModel.name}).",
            )
        }
        val nextDoc = when (op) {
            "UPSERT" -> AresProjectMetadataCodec.decode(requireNotNull(documentJson) { "documentJson is required" })
            "PATCH" -> {
                val merged = templateAndPatch.applyJsonMergePatchPreservingUids(
                    currentEncoded,
                    requireNotNull(patchJson) { "patchJson is required" },
                )
                AresProjectMetadataCodec.decode(merged)
            }
            else -> throw IllegalArgumentException("Unsupported PROJECT_METADATA operation '$op'; expected INSPECT, UPSERT, or PATCH.")
        }
        val encoded = AresProjectMetadataCodec.encode(nextDoc)
        val res = TemplateAndPatchSupport.applyTransactionalFileBatch(
            engine = engine,
            projectRoot = canonical,
            slug = "project-metadata",
            documentKind = "PROJECT_METADATA",
            operation = op,
            documentId = nextDoc.projectId,
            primaryRelativePath = relPath,
            writes = mapOf(relPath to encoded),
            expectedRevision = expectedRevision,
            message = "Updated .ares/project.json (${nextDoc.league.name}, ${nextDoc.authoringModel.name}).",
        )
        return res.toConfigResult("PROJECT_METADATA", documentJson = encoded)
    }

    private suspend fun mutateActionCatalog(
        canonical: File,
        op: String,
        documentJson: String?,
        patchJson: String?,
        targetKey: String?,
        confirmationToken: String?,
        expectedRevision: String?,
    ): ConfigOrFieldMutationResult {
        val relPath = ".ares/action-catalog.json"
        val file = JGitSnapshotSafety.safeResolveChild(canonical, relPath)
        val bundle = engine.loadBundle(canonical)
        val projectId = bundle.metadata?.projectId ?: "robot-project"
        val currentDoc = if (file.isFile) CapabilityCatalogCodec.decode(file.readText()) else CapabilityCatalogDocument(projectId = projectId)
        val currentEncoded = CapabilityCatalogCodec.encode(currentDoc)

        if (op == "INSPECT") {
            return ConfigOrFieldMutationResult(
                projectRoot = canonical.path,
                configKind = "ACTION_CATALOG",
                operation = "INSPECT",
                filePath = relPath,
                previousRevision = bundle.revision,
                itemCount = currentDoc.actions.size,
                documentJson = currentEncoded,
                message = "Loaded .ares/action-catalog.json (${currentDoc.actions.size} actions, ${currentDoc.conditions.size} conditions).",
            )
        }

        if (op == "DELETE_ACTION") {
            val key = requireNotNull(targetKey?.trim()?.takeIf { it.isNotBlank() }) {
                "targetKeyOrEntryId is required for DELETE_ACTION."
            }
            require(currentDoc.actions.any { it.key == key }) { "Action '$key' does not exist in $relPath." }
            val digest = ConfirmationTokenManager.sha256Hex("$currentEncoded::$key")
            val opKey = "DELETE_ACTION::$key"
            if (confirmationToken.isNullOrBlank()) {
                val issued = engine.tokens.issueTargetToken(canonical, opKey, digest)
                return ConfigOrFieldMutationResult(
                    projectRoot = canonical.path,
                    configKind = "ACTION_CATALOG",
                    operation = "PREVIEW_DELETE_ACTION",
                    filePath = relPath,
                    pendingConfirmation = true,
                    confirmationToken = issued,
                    previousRevision = bundle.revision,
                    message = "Deleting action '$key' staged. Re-run DELETE_ACTION with confirmationToken.",
                )
            }
            val check = engine.tokens.verifyAndConsumeTargetToken(canonical, opKey, digest, confirmationToken)
            if (check is ConfirmationCheckResult.Rejected) {
                throw IllegalArgumentException(check.message)
            }
            val updated = currentDoc.copy(
                revision = currentDoc.revision + 1,
                actions = currentDoc.actions.filterNot { it.key == key },
            )
            val encoded = CapabilityCatalogCodec.encode(updated)
            return TemplateAndPatchSupport.applyTransactionalFileBatch(
                engine = engine,
                projectRoot = canonical,
                slug = "action-catalog-delete",
                documentKind = "ACTION_CATALOG",
                operation = op,
                documentId = projectId,
                primaryRelativePath = relPath,
                writes = mapOf(relPath to encoded),
                expectedRevision = expectedRevision,
                message = "Removed action '$key' from $relPath.",
            ).toConfigResult("ACTION_CATALOG", updated.actions.size)
        }

        val updatedDoc = when (op) {
            "UPSERT" -> CapabilityCatalogCodec.decode(
                normalizeCapabilityCatalogJson(requireNotNull(documentJson) { "documentJson is required" }),
            )
            "PATCH" -> CapabilityCatalogCodec.decode(
                normalizeCapabilityCatalogJson(
                    templateAndPatch.applyJsonMergePatchPreservingUids(
                        currentEncoded,
                        requireNotNull(patchJson) { "patchJson is required" },
                    ),
                ),
            )
            "UPSERT_ACTION" -> {
                val raw = requireNotNull(documentJson) { "documentJson containing ActionDescriptor is required" }
                val actionObj = JsonParser.parseString(raw).asJsonObject.deepCopy()
                normalizeActionDescriptorObject(actionObj)
                val synthetic = JsonParser.parseString(currentEncoded).asJsonObject.deepCopy().apply {
                    add("actions", JsonArray().apply { add(actionObj) })
                    add("conditions", JsonArray())
                }
                val action = CapabilityCatalogCodec.decode(gson.toJson(synthetic)).actions.single()
                require(action.key.isNotBlank() && action.displayName.isNotBlank()) {
                    "ActionDescriptor requires non-blank key and displayName."
                }
                val mergedActions = currentDoc.actions.filterNot { it.key == action.key } + action
                currentDoc.copy(revision = currentDoc.revision + 1, actions = mergedActions)
            }
            else -> throw IllegalArgumentException(
                "Unsupported ACTION_CATALOG operation '$op'; expected INSPECT, UPSERT, PATCH, UPSERT_ACTION, or DELETE_ACTION.",
            )
        }
        val encoded = CapabilityCatalogCodec.encode(updatedDoc)
        return TemplateAndPatchSupport.applyTransactionalFileBatch(
            engine = engine,
            projectRoot = canonical,
            slug = "action-catalog-$op",
            documentKind = "ACTION_CATALOG",
            operation = op,
            documentId = updatedDoc.projectId,
            primaryRelativePath = relPath,
            writes = mapOf(relPath to encoded),
            expectedRevision = expectedRevision,
            message = "Updated .ares/action-catalog.json (${updatedDoc.actions.size} actions).",
        ).toConfigResult("ACTION_CATALOG", updatedDoc.actions.size, encoded)
    }

    private suspend fun mutateAutonomousCatalog(
        canonical: File,
        op: String,
        documentJson: String?,
        patchJson: String?,
        targetEntryId: String?,
        confirmationToken: String?,
        expectedRevision: String?,
    ): ConfigOrFieldMutationResult {
        val relPath = ".ares/autonomous-catalog.json"
        val file = JGitSnapshotSafety.safeResolveChild(canonical, relPath)
        val bundle = engine.loadBundle(canonical)
        val projectId = bundle.metadata?.projectId ?: "robot-project"
        val currentDoc = if (file.isFile) {
            AutonomousCatalogCodec.decode(file.readText())
        } else {
            AutonomousCatalogDocument(projectId = projectId, entries = emptyList())
        }
        val currentEncoded = AutonomousCatalogCodec.encode(currentDoc)

        if (op == "INSPECT") {
            return ConfigOrFieldMutationResult(
                projectRoot = canonical.path,
                configKind = "AUTONOMOUS_CATALOG",
                operation = "INSPECT",
                filePath = relPath,
                previousRevision = bundle.revision,
                itemCount = currentDoc.entries.size,
                documentJson = currentEncoded,
                message = "Loaded .ares/autonomous-catalog.json (${currentDoc.entries.size} entries).",
            )
        }

        if (op == "DELETE_ENTRY") {
            val entryId = requireNotNull(targetEntryId?.trim()?.takeIf { it.isNotBlank() }) {
                "targetKeyOrEntryId is required for DELETE_ENTRY."
            }
            require(currentDoc.entries.any { it.entryId == entryId }) {
                "Autonomous catalog entry '$entryId' does not exist."
            }
            val digest = ConfirmationTokenManager.sha256Hex("$currentEncoded::$entryId")
            val opKey = "DELETE_AUTO_ENTRY::$entryId"
            if (confirmationToken.isNullOrBlank()) {
                val issued = engine.tokens.issueTargetToken(canonical, opKey, digest)
                return ConfigOrFieldMutationResult(
                    projectRoot = canonical.path,
                    configKind = "AUTONOMOUS_CATALOG",
                    operation = "PREVIEW_DELETE_ENTRY",
                    filePath = relPath,
                    pendingConfirmation = true,
                    confirmationToken = issued,
                    previousRevision = bundle.revision,
                    message = "Deleting autonomous entry '$entryId' staged. Re-run DELETE_ENTRY with confirmationToken.",
                )
            }
            val check = engine.tokens.verifyAndConsumeTargetToken(canonical, opKey, digest, confirmationToken)
            if (check is ConfirmationCheckResult.Rejected) {
                throw IllegalArgumentException(check.message)
            }
            val nextEntries = currentDoc.entries.filterNot { it.entryId == entryId }
            val updated = currentDoc.copy(
                revision = currentDoc.revision + 1,
                defaultEntryId = currentDoc.defaultEntryId?.takeUnless { it == entryId },
                entries = nextEntries,
            )
            return writeAutonomousCatalog(canonical, op, relPath, updated, expectedRevision)
        }

        val updatedDoc = when (op) {
            "UPSERT" -> AutonomousCatalogCodec.decode(requireNotNull(documentJson) { "documentJson is required" })
            "PATCH" -> AutonomousCatalogCodec.decode(
                templateAndPatch.applyJsonMergePatchPreservingUids(
                    currentEncoded,
                    requireNotNull(patchJson) { "patchJson is required" },
                ),
            )
            "UPSERT_ENTRY" -> {
                val raw = requireNotNull(documentJson) { "documentJson containing AutonomousCatalogEntry is required" }
                val entryObj = JsonParser.parseString(raw).asJsonObject.deepCopy()
                val synthetic = JsonParser.parseString(currentEncoded).asJsonObject.deepCopy().apply {
                    remove("defaultEntryId")
                    add("entries", JsonArray().apply { add(entryObj) })
                }
                val entry = AutonomousCatalogCodec.decode(gson.toJson(synthetic)).entries.single()
                require(entry.entryId.isNotBlank() && entry.routineId.isNotBlank() && entry.displayName.isNotBlank()) {
                    "AutonomousCatalogEntry requires non-blank entryId, displayName, and routineId."
                }
                val mergedEntries = currentDoc.entries.filterNot { it.entryId == entry.entryId } + entry
                currentDoc.copy(
                    revision = currentDoc.revision + 1,
                    defaultEntryId = currentDoc.defaultEntryId ?: entry.entryId.takeIf { entry.enabled },
                    entries = mergedEntries,
                )
            }
            else -> throw IllegalArgumentException(
                "Unsupported AUTONOMOUS_CATALOG operation '$op'; expected INSPECT, UPSERT, PATCH, UPSERT_ENTRY, or DELETE_ENTRY.",
            )
        }
        return writeAutonomousCatalog(canonical, op, relPath, updatedDoc, expectedRevision)
    }

    private suspend fun writeAutonomousCatalog(
        canonical: File,
        op: String,
        relPath: String,
        doc: AutonomousCatalogDocument,
        expectedRevision: String?,
    ): ConfigOrFieldMutationResult {
        val routineIds = engine.loadBundle(canonical).snapshot.routines
            .map { it.documentId }
            .toSet()
        val catalogErrors = validateAutonomousCatalog(doc, routineIds)
            .filter { it.severity == RoutineValidationSeverity.ERROR }
        require(catalogErrors.isEmpty()) {
            "Autonomous catalog validation failed: " + catalogErrors.joinToString("; ") { "${it.path}: ${it.message}" }
        }
        val encoded = AutonomousCatalogCodec.encode(doc)
        return TemplateAndPatchSupport.applyTransactionalFileBatch(
            engine = engine,
            projectRoot = canonical,
            slug = "autonomous-catalog-$op",
            documentKind = "AUTONOMOUS_CATALOG",
            operation = op,
            documentId = doc.projectId,
            primaryRelativePath = relPath,
            writes = mapOf(relPath to encoded),
            expectedRevision = expectedRevision,
            message = "Updated .ares/autonomous-catalog.json (${doc.entries.size} entries).",
        ).toConfigResult("AUTONOMOUS_CATALOG", doc.entries.size, encoded)
    }

    private fun defaultFieldConfig(league: AresLeague): RobotFieldConfig = RobotFieldConfig(
        id = "${league.name.lowercase()}-default",
        name = "${league.name} Field",
        fieldType = league.toFieldType(),
        widthMeters = 0.0,
        heightMeters = 0.0,
    )

    private fun applyFieldPreset(base: RobotFieldConfig, preset: String, league: AresLeague): RobotFieldConfig {
        val fieldType = league.toFieldType()
        val centered = league == AresLeague.FTC || league == AresLeague.XRP
        val resolvedWidth = base.widthMeters.takeIf { it > 0.0 } ?: base.copy(fieldType = fieldType).resolvedWidthMeters
        val resolvedHeight = base.heightMeters.takeIf { it > 0.0 } ?: base.copy(fieldType = fieldType).resolvedHeightMeters
        val originX = if (centered) 0.0 else resolvedWidth * 0.25
        val originY = if (centered) 0.0 else resolvedHeight * 0.50
        val nextRev = base.revision + 1L

        return when (preset) {
            "LEAGUE_DEFAULT" -> base.copy(
                id = base.id.ifBlank { "${league.name.lowercase()}-default" },
                name = base.name.ifBlank { "${league.name} Default Field" },
                revision = nextRev,
                fieldType = fieldType,
            )
            "CLEAR_PRACTICE_FLOOR" -> base.copy(
                id = "clear-practice-floor",
                name = "Clear Practice Floor (${league.name})",
                revision = nextRev,
                fieldType = fieldType,
                obstacles = emptyList(),
                elements = emptyList(),
                apriltags = emptyList(),
                fieldWaypoints = listOf(
                    RobotFieldWaypoint(
                        id = "preset-open-origin",
                        name = "Practice Start Origin",
                        x = originX,
                        y = originY,
                        headingDegrees = 0.0,
                    ),
                ),
            )
            "VISION_CALIBRATION_TARGET_1M" -> {
                val startX = if (centered) -0.5 else originX
                base.copy(
                    id = "vision-calibration-1m",
                    name = "Vision Calibration (1.0 m Ahead)",
                    revision = nextRev,
                    fieldType = fieldType,
                    obstacles = emptyList(),
                    elements = emptyList(),
                    apriltags = listOf(
                        RobotFieldAprilTag(
                            id = 1,
                            name = "Calibration Target #1 (1.0 m)",
                            family = "36h11",
                            sizeMeters = defaultTagSizeForLeague(league),
                            x = startX + 1.0,
                            y = originY,
                            z = if (league == AresLeague.XRP) 0.08 else 0.30,
                            yaw = 180.0,
                            editorId = "preset-vision-tag-1",
                        ),
                    ),
                    fieldWaypoints = listOf(
                        RobotFieldWaypoint(
                            id = "preset-vision-start",
                            name = "Robot Camera Start",
                            x = startX,
                            y = originY,
                            headingDegrees = 0.0,
                        ),
                        RobotFieldWaypoint(
                            id = "preset-vision-standoff",
                            name = "0.5 m Standoff Check",
                            x = startX + 0.5,
                            y = originY,
                            headingDegrees = 0.0,
                        ),
                    ),
                )
            }
            "THREE_CONE_SLALOM_COURSE" -> {
                val spacing = when (league) {
                    AresLeague.FRC -> 1.4
                    AresLeague.FTC -> 0.75
                    AresLeague.XRP -> 0.40
                }
                val lateralOffset = when (league) {
                    AresLeague.FRC -> 0.65
                    AresLeague.FTC -> 0.38
                    AresLeague.XRP -> 0.20
                }
                val coneDiameter = when (league) {
                    AresLeague.FRC -> 0.32
                    AresLeague.FTC -> 0.22
                    AresLeague.XRP -> 0.12
                }
                val baseX = if (centered) -spacing else resolvedWidth * 0.30
                base.copy(
                    id = "three-cone-slalom",
                    name = "3-Cone Slalom Practice Course (${league.name})",
                    revision = nextRev,
                    fieldType = fieldType,
                    apriltags = emptyList(),
                    elements = emptyList(),
                    obstacles = listOf(
                        RobotFieldObstacle("preset-slalom-cone-1", "Slalom Cone 1", baseX, originY, coneDiameter, coneDiameter),
                        RobotFieldObstacle("preset-slalom-cone-2", "Slalom Cone 2", baseX + spacing, originY, coneDiameter, coneDiameter),
                        RobotFieldObstacle("preset-slalom-cone-3", "Slalom Cone 3", baseX + spacing * 2.0, originY, coneDiameter, coneDiameter),
                    ),
                    fieldWaypoints = listOf(
                        RobotFieldWaypoint("preset-slalom-wp-start", "Slalom Start", baseX - spacing * 0.6, originY - lateralOffset, 0.0),
                        RobotFieldWaypoint("preset-slalom-wp-1", "Gate 1 (Left)", baseX + spacing * 0.5, originY + lateralOffset, 0.0),
                        RobotFieldWaypoint("preset-slalom-wp-2", "Gate 2 (Right)", baseX + spacing * 1.5, originY - lateralOffset, 0.0),
                        RobotFieldWaypoint("preset-slalom-wp-finish", "Slalom Finish", baseX + spacing * 2.6, originY + lateralOffset, 0.0),
                    ),
                )
            }
            else -> throw IllegalArgumentException(
                "Unknown field preset '$preset'; expected LEAGUE_DEFAULT, VISION_CALIBRATION_TARGET_1M, THREE_CONE_SLALOM_COURSE, or CLEAR_PRACTICE_FLOOR.",
            )
        }
    }

    private fun defaultTagSizeForLeague(league: AresLeague): Double = when (league) {
        AresLeague.FRC -> 0.1651
        AresLeague.FTC -> 0.127
        AresLeague.XRP -> 0.08
    }

    private fun AresLeague.toFieldType(): FieldType = when (this) {
        AresLeague.FTC -> FieldType.FTC
        AresLeague.FRC -> FieldType.FRC
        AresLeague.XRP -> FieldType.XRP
    }

    private fun DescriptorMutationResult.toConfigResult(
        configKind: String,
        itemCount: Int = 0,
        documentJson: String? = null,
    ): ConfigOrFieldMutationResult = ConfigOrFieldMutationResult(
        projectRoot = projectRoot,
        configKind = configKind,
        operation = operation,
        filePath = filePath,
        pendingConfirmation = pendingConfirmation,
        confirmationToken = confirmationToken,
        previousRevision = previousRevision,
        newRevision = newRevision,
        snapshotRef = snapshotRef,
        itemCount = itemCount,
        documentJson = documentJson,
        validationSummary = validation,
        message = message,
    )

    private fun normalizeCapabilityCatalogJson(rawJson: String): String {
        val root = JsonParser.parseString(rawJson).asJsonObject.deepCopy()
        val actions = ensureArrayField(root, "actions")
        actions.forEach { el -> if (el.isJsonObject) normalizeActionDescriptorObject(el.asJsonObject) }
        val conditions = ensureArrayField(root, "conditions")
        conditions.forEach { el ->
            if (el.isJsonObject) {
                val obj = el.asJsonObject
                if (!obj.has("category") || obj.get("category").isJsonNull) obj.addProperty("category", "General")
                normalizeParameterObjects(ensureArrayField(obj, "parameters"))
                ensureArrayField(obj, "resources")
            }
        }
        return gson.toJson(root)
    }

    private fun normalizeActionDescriptorObject(obj: JsonObject) {
        if (!obj.has("category") || obj.get("category").isJsonNull) obj.addProperty("category", "General")
        normalizeParameterObjects(ensureArrayField(obj, "parameters"))
        ensureArrayField(obj, "resources")
        if (!obj.has("allowedContexts") || obj.get("allowedContexts").isJsonNull) {
            obj.add("allowedContexts", JsonArray().apply {
                add(JsonPrimitive("AUTONOMOUS"))
                add(JsonPrimitive("TELEOP"))
                add(JsonPrimitive("TEST"))
            })
        }
    }

    private fun normalizeParameterObjects(parameters: JsonArray) {
        parameters.forEach { el ->
            if (el.isJsonObject) {
                val p = el.asJsonObject
                ensureArrayField(p, "options")
                if (!p.has("required") || p.get("required").isJsonNull) p.addProperty("required", true)
            }
        }
    }

    private fun ensureArrayField(obj: JsonObject, key: String): JsonArray {
        val existing = obj.get(key)
        if (existing != null && existing.isJsonArray) return existing.asJsonArray
        return JsonArray().also { obj.add(key, it) }
    }
}
