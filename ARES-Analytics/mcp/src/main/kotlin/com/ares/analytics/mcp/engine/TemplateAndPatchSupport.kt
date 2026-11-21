package com.ares.analytics.mcp.engine

import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControllerProfileCodec
import com.areslib.drivetrain.DrivetrainComponentRole
import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.project.AresLeague
import com.areslib.routine.AresRoutineCodec
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemImplementationKind
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.subsystem.supportsPlatform
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.tuning.TuningAssignment
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningProfileDocumentCodec
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Supports Wave 2 `TEMPLATE` instantiation (all 31 SubsystemTemplates & 4 Drivetrain presets),
 * RFC-7396 + UID-preserving array-keyed `PATCH`, and multi-file atomic rollback transactions.
 */
internal class TemplateAndPatchSupport(
    private val engine: HeadlessProjectEngine,
    private val mutators: HeadlessProjectMutators,
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun applySubsystemTemplate(
        projectRoot: File,
        templateNameRaw: String,
        documentIdHint: String?,
        kotlinTypeNameHint: String?,
        displayNameHint: String?,
        implementationKindRaw: String?,
        expectedRevision: String?,
    ): DescriptorMutationResult {
        val canonical = projectRoot.canonicalFile
        val bundle = engine.loadBundle(canonical)
        val league = bundle.metadata?.league ?: throw IllegalStateException("Missing .ares/project.json")
        val platform = when (league) {
            AresLeague.FTC -> SubsystemPlatform.FTC
            AresLeague.FRC -> SubsystemPlatform.FRC
            AresLeague.XRP -> SubsystemPlatform.XRP
        }
        val normalizedTemplate = templateNameRaw.trim().uppercase()
        val template = SubsystemTemplate.entries.firstOrNull { it.name == normalizedTemplate }
            ?: throw IllegalArgumentException(
                "Unknown subsystem template '$templateNameRaw'. Valid templates: ${
                    SubsystemTemplate.entries.joinToString(", ") { it.name }
                }",
            )
        require(template.supportsPlatform(platform)) {
            "Subsystem template '${template.name}' is not supported on platform '${platform.name}'."
        }
        val implKind = when (implementationKindRaw?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: "DECLARATIVE_GENERATED") {
            "DECLARATIVE_GENERATED" -> SubsystemImplementationKind.DECLARATIVE_GENERATED
            "GENERATED_STARTER" -> SubsystemImplementationKind.GENERATED_STARTER
            "HAND_AUTHORED" -> throw IllegalArgumentException(
                "Use ares_kotlin_scaffold(kind='REGISTER_EXISTING_HYBRID') to create/register HAND_AUTHORED subsystems.",
            )
            else -> throw IllegalArgumentException(
                "Unsupported implementationKind '$implementationKindRaw'; expected DECLARATIVE_GENERATED or GENERATED_STARTER.",
            )
        }
        val documentId = sanitizeSlugId(documentIdHint ?: template.name.lowercase().replace('_', '-'))
        val kotlinTypeName = kotlinTypeNameHint?.trim()?.takeIf { it.isNotBlank() } ?: toPascalCase(documentId)
        val displayName = displayNameHint?.trim()?.takeIf { it.isNotBlank() } ?: toDisplayName(documentId)

        val doc = SubsystemTemplates.createWithOwnership(
            template = template,
            documentId = documentId,
            kotlinTypeName = kotlinTypeName,
            platform = platform,
            displayName = displayName,
            implementationKind = implKind,
        )
        val encoded = SubsystemDocumentCodec.encode(doc)
        return mutators.applyTransactionalUpsert(
            projectRoot = canonical,
            documentKind = "SUBSYSTEM",
            documentId = doc.documentId,
            relativePath = ".ares/subsystems/${doc.documentId}.aressubsystem",
            encodedText = encoded,
            expectedRevision = expectedRevision,
        )
    }

    suspend fun patchSubsystem(
        projectRoot: File,
        documentIdHint: String?,
        patchJson: String,
        expectedRevision: String?,
    ): DescriptorMutationResult {
        val rawId = requireNotNull(documentIdHint?.trim()?.takeIf { it.isNotBlank() }) {
            "documentId is required for SUBSYSTEM PATCH."
        }
        val id = McpPathAndIdentifierValidators.validateDocumentSlug(rawId, "SUBSYSTEM")
        val canonical = projectRoot.canonicalFile
        val relPath = ".ares/subsystems/$id.aressubsystem"
        val existingFile = JGitSnapshotSafety.safeResolveChild(canonical, relPath, requiredSubdir = ".ares/subsystems")
        require(existingFile.isFile) { "Cannot PATCH missing subsystem descriptor '$relPath'." }
        val mergedJson = applyJsonMergePatchPreservingUids(existingFile.readText(), patchJson)
        val decoded = SubsystemDocumentCodec.decode(sanitizePatchedSubsystemJson(mergedJson))
        val sanitized = sanitizePatchedSubsystem(decoded)
        val targetId = McpPathAndIdentifierValidators.validateDocumentSlug(sanitized.documentId, "SUBSYSTEM")
        require(targetId == id) {
            "SUBSYSTEM PATCH cannot rename documentId from '$id' to '$targetId'; use UPSERT/DELETE instead."
        }
        return mutators.applyTransactionalUpsert(
            projectRoot = canonical,
            documentKind = "SUBSYSTEM",
            documentId = targetId,
            relativePath = ".ares/subsystems/$targetId.aressubsystem",
            encodedText = SubsystemDocumentCodec.encode(sanitized),
            expectedRevision = expectedRevision,
            requiredSubdir = ".ares/subsystems",
        )
    }

    suspend fun applyDrivetrainTemplate(
        projectRoot: File,
        presetRaw: String,
        drivebaseIdHint: String?,
        expectedRevision: String?,
    ): DescriptorMutationResult {
        val canonical = projectRoot.canonicalFile
        val bundle = engine.loadBundle(canonical)
        val metadata = requireNotNull(bundle.metadata) { "Missing .ares/project.json" }
        val safeProjectUidPrefix = toUidSegment(metadata.projectId)
        val drivebaseId = sanitizeSlugId(drivebaseIdHint ?: "main-drive")
        val driveUid = "$safeProjectUidPrefix.drive.$drivebaseId"
        val existingDrivetrainFiles = File(canonical, ".ares/drivetrains")
            .listFiles { file -> file.isFile && file.extension == "aresdrivetrain" }
            ?.sortedBy { it.name }
            .orEmpty()
        val existingCanonicalProfile = bundle.snapshot.tuningProfiles
            .firstOrNull { it.authority == TuningProfileAuthority.CANONICAL_CHECKED_IN }
        val canonicalProfileUid = existingCanonicalProfile?.uid ?: "$safeProjectUidPrefix.profile.competition"
        val canonicalProfileId = existingCanonicalProfile?.profileId ?: "competition"

        val doc = buildDrivetrainPresetDocument(
            presetRaw = presetRaw,
            league = metadata.league,
            drivebaseId = drivebaseId,
            driveUid = driveUid,
            canonicalProfileUid = canonicalProfileUid,
        )
        val targetRelPath = ".ares/drivetrains/${doc.drivebaseId}.aresdrivetrain"
        val deletes = existingDrivetrainFiles
            .map { it.relativeTo(canonical).invariantSeparatorsPath }
            .filter { it != targetRelPath }

        val nonDrivetrainDecls = bundle.snapshot.subsystems.flatMap { it.tuningParameters } +
            bundle.snapshot.tuningComponents.flatMap { it.parameters }
        val allDeclarations = (doc.parameters + nonDrivetrainDecls).associateBy { it.uid }
        val writes = linkedMapOf(targetRelPath to DrivetrainDocumentCodec.encode(doc))

        val canonicalProfileFile = File(canonical, ".ares/tuning")
            .listFiles { f -> f.isFile && f.extension == "arestuning" }
            ?.firstOrNull { file ->
                runCatching { JsonParser.parseString(file.readText()).asJsonObject.get("uid")?.asString }
                    .getOrNull() == canonicalProfileUid
            }
            ?: File(canonical, ".ares/tuning/$canonicalProfileId.arestuning")

        val preservedAssignments = existingCanonicalProfile?.values
            ?.filter { it.parameterUid in allDeclarations }
            ?.associateBy { it.parameterUid }
            .orEmpty()
        val reconciledAssignments = buildList {
            doc.parameters.forEach { decl ->
                add(preservedAssignments[decl.uid] ?: TuningAssignment(decl.uid, decl.defaultValue))
            }
            preservedAssignments.values
                .filter { it.parameterUid !in doc.parameters.map { p -> p.uid }.toSet() }
                .forEach { add(it) }
        }.distinctBy { it.parameterUid }.sortedBy { it.parameterUid }

        val updatedCanonicalProfile = (existingCanonicalProfile ?: TuningProfileDocument(
            uid = canonicalProfileUid,
            profileId = canonicalProfileId,
            displayName = "Competition Baseline",
            description = "Canonical tuning profile for ${doc.displayName}.",
            projectId = metadata.projectId,
            drivebaseUid = doc.uid,
            authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
            values = reconciledAssignments,
        )).copy(
            drivebaseUid = doc.uid,
            values = reconciledAssignments,
        )
        val profileRelPath = canonicalProfileFile.relativeTo(canonical).invariantSeparatorsPath
        writes[profileRelPath] = TuningProfileDocumentCodec.encode(updatedCanonicalProfile, allDeclarations.values)

        // Prune stale drivetrain parameter assignments from any sibling canonical/local .arestuning files.
        reconcileOtherTuningProfiles(canonical, profileRelPath, bundle.snapshot.tuningProfiles, doc.uid, allDeclarations, writes)

        return applyTransactionalFileBatch(
            engine = engine,
            projectRoot = canonical,
            slug = "drivetrain-template-${doc.drivebaseId}",
            documentKind = "DRIVETRAIN",
            operation = "TEMPLATE",
            documentId = doc.drivebaseId,
            primaryRelativePath = targetRelPath,
            writes = writes,
            deletes = deletes,
            expectedRevision = expectedRevision,
            message = "Instantiated drivetrain preset '${presetRaw.trim().uppercase()}' into $targetRelPath.",
        )
    }

    suspend fun patchDrivetrain(
        projectRoot: File,
        patchJson: String,
        expectedRevision: String?,
    ): DescriptorMutationResult {
        val canonical = projectRoot.canonicalFile
        val existingFile = File(canonical, ".ares/drivetrains")
            .listFiles { f -> f.isFile && f.extension == "aresdrivetrain" }
            ?.sortedBy { it.name }
            ?.firstOrNull()
            ?: throw IllegalStateException("No .aresdrivetrain descriptor exists to PATCH.")
        val mergedJson = applyJsonMergePatchPreservingUids(existingFile.readText(), patchJson)
        val updated = DrivetrainDocumentCodec.decode(mergedJson)
        val relPath = existingFile.relativeTo(canonical).invariantSeparatorsPath
        return mutators.applyTransactionalUpsert(
            projectRoot = canonical,
            documentKind = "DRIVETRAIN",
            documentId = updated.drivebaseId,
            relativePath = relPath,
            encodedText = DrivetrainDocumentCodec.encode(updated),
            expectedRevision = expectedRevision,
        )
    }

    suspend fun patchSuperstructure(
        projectRoot: File,
        superstructureIdHint: String?,
        patchJson: String,
        expectedRevision: String?,
    ): DescriptorMutationResult {
        val rawId = requireNotNull(superstructureIdHint?.trim()?.takeIf { it.isNotBlank() }) {
            "superstructureId/documentId is required for SUPERSTRUCTURE PATCH."
        }
        val id = McpPathAndIdentifierValidators.validateDocumentSlug(rawId, "SUPERSTRUCTURE")
        val canonical = projectRoot.canonicalFile
        val existingFile = JGitSnapshotSafety.safeResolveChild(
            canonical,
            ".ares/superstructures/$id.aressuperstructure",
            requiredSubdir = ".ares/superstructures",
        )
        require(existingFile.isFile) { "Missing superstructure descriptor '.ares/superstructures/$id.aressuperstructure'." }
        val doc = SuperstructureDocumentCodec.decode(applyJsonMergePatchPreservingUids(existingFile.readText(), patchJson))
        val targetId = McpPathAndIdentifierValidators.validateDocumentSlug(doc.superstructureId, "SUPERSTRUCTURE")
        require(targetId == id) {
            "SUPERSTRUCTURE PATCH cannot rename superstructureId from '$id' to '$targetId'."
        }
        return mutators.applyTransactionalUpsert(
            projectRoot = canonical,
            documentKind = "SUPERSTRUCTURE",
            documentId = targetId,
            relativePath = ".ares/superstructures/$targetId.aressuperstructure",
            encodedText = SuperstructureDocumentCodec.encode(doc),
            expectedRevision = expectedRevision,
            requiredSubdir = ".ares/superstructures",
        )
    }

    suspend fun patchRoutine(
        projectRoot: File,
        documentIdHint: String?,
        patchJson: String,
        expectedRevision: String?,
        additionalWrites: List<Triple<String, String?, String>> = emptyList(),
    ): DescriptorMutationResult {
        val rawId = requireNotNull(documentIdHint?.trim()?.takeIf { it.isNotBlank() }) {
            "documentId is required for ROUTINE PATCH."
        }
        val id = McpPathAndIdentifierValidators.validateDocumentSlug(rawId, "ROUTINE")
        val canonical = projectRoot.canonicalFile
        val existingFile = JGitSnapshotSafety.safeResolveChild(
            canonical,
            ".ares/routines/$id.aresroutine",
            requiredSubdir = ".ares/routines",
        )
        require(existingFile.isFile) { "Missing routine descriptor '.ares/routines/$id.aresroutine'." }
        val doc = AresRoutineCodec.decode(applyJsonMergePatchPreservingUids(existingFile.readText(), patchJson))
        val targetId = McpPathAndIdentifierValidators.validateDocumentSlug(doc.documentId, "ROUTINE")
        require(targetId == id) {
            "ROUTINE PATCH cannot rename documentId from '$id' to '$targetId'."
        }
        return mutators.applyTransactionalUpsert(
            projectRoot = canonical,
            documentKind = "ROUTINE",
            documentId = targetId,
            relativePath = ".ares/routines/$targetId.aresroutine",
            encodedText = AresRoutineCodec.encode(doc),
            expectedRevision = expectedRevision,
            requiredSubdir = ".ares/routines",
            additionalWrites = additionalWrites,
        )
    }

    suspend fun patchControls(
        projectRoot: File,
        targetKindRaw: String,
        documentIdHint: String?,
        patchJson: String,
        expectedRevision: String?,
    ): DescriptorMutationResult {
        val rawId = requireNotNull(documentIdHint?.trim()?.takeIf { it.isNotBlank() }) {
            "documentId is required for CONTROLS PATCH."
        }
        val kind = targetKindRaw.trim().uppercase()
        val isProfile = kind == "CONTROLLER_PROFILE"
        val id = McpPathAndIdentifierValidators.validateDocumentSlug(rawId, if (isProfile) "CONTROLLER_PROFILE" else "CONTROL_SCHEME")
        val canonical = projectRoot.canonicalFile
        val requiredDir = if (isProfile) ".ares/controllers" else ".ares/controls"
        val relPath = if (isProfile) "$requiredDir/$id.arescontroller" else "$requiredDir/$id.arescontrols"
        val existingFile = JGitSnapshotSafety.safeResolveChild(canonical, relPath, requiredSubdir = requiredDir)
        require(existingFile.isFile) { "Missing controls descriptor '$relPath'." }
        val mergedJson = applyJsonMergePatchPreservingUids(existingFile.readText(), patchJson)
        val encoded = if (isProfile) {
            ControllerProfileCodec.encode(ControllerProfileCodec.decode(mergedJson))
        } else {
            ControlSchemeCodec.encode(ControlSchemeCodec.decode(mergedJson))
        }
        val decodedId = McpPathAndIdentifierValidators.validateDocumentSlug(
            if (isProfile) ControllerProfileCodec.decode(encoded).documentId else ControlSchemeCodec.decode(encoded).documentId,
            if (isProfile) "CONTROLLER_PROFILE" else "CONTROL_SCHEME",
        )
        require(decodedId == id) {
            "CONTROLS PATCH cannot rename documentId from '$id' to '$decodedId'."
        }
        val targetRel = if (isProfile) "$requiredDir/$decodedId.arescontroller" else "$requiredDir/$decodedId.arescontrols"
        return mutators.applyTransactionalUpsert(
            projectRoot = canonical,
            documentKind = if (isProfile) "CONTROLLER_PROFILE" else "CONTROL_SCHEME",
            documentId = decodedId,
            relativePath = targetRel,
            encodedText = encoded,
            expectedRevision = expectedRevision,
            requiredSubdir = requiredDir,
        )
    }

    /**
     * Applies RFC-7396 JSON Merge Patch with UID preservation for object arrays matched on stable keys
     * (`uid`, `hardwareId`, `fieldId`, `loopId`, `key`, `stepId`, `bindingId`, `slot`, `stateId`, `transitionId`,
     * `ruleId`, `entryId`, `controlId`, `id`). Setting `"_delete": true` in a patched element deletes that item.
     */
    fun applyJsonMergePatchPreservingUids(baseJson: String, patchJson: String): String {
        val baseEl = JsonParser.parseString(baseJson)
        val patchEl = JsonParser.parseString(patchJson)
        require(baseEl.isJsonObject && patchEl.isJsonObject) {
            "PATCH requires both target document and patchJson to be JSON objects."
        }
        val merged = mergeElements(baseEl, patchEl)
        return gson.toJson(merged)
    }

    private fun mergeElements(target: JsonElement?, patch: JsonElement): JsonElement {
        if (!patch.isJsonObject) {
            return patch.deepCopy()
        }
        val patchObj = patch.asJsonObject
        val result = if (target != null && target.isJsonObject) target.asJsonObject.deepCopy() else JsonObject()
        for ((key, value) in patchObj.entrySet()) {
            if (value.isJsonNull) {
                result.remove(key)
            } else if (value.isJsonArray && result.get(key)?.isJsonArray == true && key in keyedArrayFields) {
                result.add(key, mergeKeyedArray(result.getAsJsonArray(key), value.asJsonArray))
            } else if (value.isJsonObject) {
                result.add(key, mergeElements(result.get(key), value))
            } else {
                result.add(key, value.deepCopy())
            }
        }
        return result
    }

    private fun mergeKeyedArray(baseArr: JsonArray, patchArr: JsonArray): JsonArray {
        val baseObjects = baseArr.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        if (baseObjects.size != baseArr.size()) return patchArr.deepCopy()
        val patchObjects = patchArr.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        if (patchObjects.size != patchArr.size()) return patchArr.deepCopy()
        val allPatchHaveKeys = patchObjects.all { extractIdentityPairs(it).isNotEmpty() }
        if (!allPatchHaveKeys) return patchArr.deepCopy()

        val resultList = baseObjects.map { it.deepCopy() }.toMutableList()
        for (patchItem in patchObjects) {
            val keys = extractIdentityPairs(patchItem)
            val matchIndex = resultList.indexOfFirst { existing ->
                val existingKeys = extractIdentityPairs(existing)
                keys.any { pair -> pair in existingKeys }
            }
            val deleteRequested = patchItem.get("_delete")?.asJsonPrimitive?.let {
                it.isBoolean && it.asBoolean
            } == true
            if (matchIndex >= 0) {
                if (deleteRequested) {
                    resultList.removeAt(matchIndex)
                } else {
                    val existingItem = resultList[matchIndex]
                    val mergedObj = mergeElements(existingItem, patchItem).asJsonObject
                    mergedObj.remove("_delete")
                    if (!patchItem.has("uid") && existingItem.has("uid")) {
                        mergedObj.add("uid", existingItem.get("uid").deepCopy())
                    }
                    resultList[matchIndex] = mergedObj
                }
            } else if (!deleteRequested) {
                val added = patchItem.deepCopy()
                added.remove("_delete")
                resultList.add(added)
            }
        }
        return JsonArray().apply { resultList.forEach(::add) }
    }

    private fun extractIdentityPairs(obj: JsonObject): Set<Pair<String, String>> = buildSet {
        for (field in identityKeyOrder) {
            val value = obj.get(field)
            if (value != null && value.isJsonPrimitive) {
                add(field to value.asString)
            }
        }
    }

    private fun sanitizePatchedSubsystemJson(mergedJson: String): String {
        val root = JsonParser.parseString(mergedJson).asJsonObject.deepCopy()
        val hwObjs = root.get("hardware")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()
        val hardwareIds = hwObjs.mapNotNull { it.get("hardwareId")?.takeIf { p -> p.isJsonPrimitive }?.asString }.toHashSet()
        val fieldIds = root.get("stateFields")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("fieldId")?.takeIf { p -> p.isJsonPrimitive }?.asString }
            ?.toHashSet().orEmpty()
        val hasActuator = hwObjs.any { hw ->
            val kind = hw.get("kind")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            kind.contains("MOTOR", ignoreCase = true) || kind.contains("SERVO", ignoreCase = true)
        }
        val hasMotor = hwObjs.any { hw ->
            hw.get("kind")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().contains("MOTOR", ignoreCase = true)
        }

        val keptLoopObjs = mutableListOf<JsonObject>()
        root.get("controlLoops")?.takeIf { it.isJsonArray }?.asJsonArray?.let { loops ->
            val kept = JsonArray()
            for (el in loops) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val actuatorId = obj.get("actuatorId")?.takeIf { it.isJsonPrimitive }?.asString
                val targetFieldId = obj.get("targetFieldId")?.takeIf { it.isJsonPrimitive }?.asString
                val feedbackFieldId = obj.get("feedbackFieldId")?.takeIf { it.isJsonPrimitive }?.asString
                val actuatorOk = actuatorId != null && actuatorId in hardwareIds
                val targetOk = targetFieldId != null && targetFieldId in fieldIds
                val feedbackOk = feedbackFieldId == null || feedbackFieldId in fieldIds
                if (actuatorOk && targetOk && feedbackOk) {
                    kept.add(obj)
                    keptLoopObjs += obj
                }
            }
            root.add("controlLoops", kept)
        }

        val keptComponentUids = buildSet {
            root.get("uid")?.takeIf { it.isJsonPrimitive }?.asString?.let(::add)
            hwObjs.mapNotNullTo(this) { it.get("uid")?.takeIf { p -> p.isJsonPrimitive }?.asString }
            root.get("stateFields")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
                if (el.isJsonObject) el.asJsonObject.get("uid")?.takeIf { p -> p.isJsonPrimitive }?.asString?.let(::add)
            }
            keptLoopObjs.mapNotNullTo(this) { it.get("uid")?.takeIf { p -> p.isJsonPrimitive }?.asString }
        }
        root.get("tuningParameters")?.takeIf { it.isJsonArray }?.asJsonArray?.let { params ->
            val kept = JsonArray()
            for (el in params) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val componentUid = obj.get("componentUid")?.takeIf { it.isJsonPrimitive }?.asString
                if (componentUid == null || componentUid in keptComponentUids) kept.add(obj)
            }
            root.add("tuningParameters", kept)
        }

        root.get("safety")?.takeIf { it.isJsonObject }?.asJsonObject?.let { safety ->
            safety.get("homing")?.takeIf { it.isJsonObject }?.asJsonObject?.let { homing ->
                val homingActuator = homing.get("actuatorId")?.takeIf { it.isJsonPrimitive }?.asString
                if (homingActuator != null && homingActuator !in hardwareIds) {
                    homing.addProperty("method", "NONE")
                    homing.remove("actuatorId")
                    homing.remove("searchOutput")
                    homing.add("evidence", JsonArray())
                }
            }
            safety.get("faultRecovery")?.takeIf { it.isJsonObject }?.asJsonObject?.let { fault ->
                val faultActuator = fault.get("actuatorId")?.takeIf { it.isJsonPrimitive }?.asString
                if (faultActuator != null && faultActuator !in hardwareIds) {
                    fault.addProperty("enabled", false)
                    fault.remove("actuatorId")
                    fault.remove("currentFieldId")
                }
            }
            if (!hasMotor) safety.addProperty("requiresCurrentMonitoring", false)
            if (!hasActuator) {
                safety.addProperty("latchOutputFaults", false)
                safety.addProperty("requiresExplicitNeutralRecovery", false)
            }
        }
        return gson.toJson(root)
    }

    private fun sanitizePatchedSubsystem(document: SubsystemDocument): SubsystemDocument {
        val hardwareIds = document.hardware.mapTo(hashSetOf()) { it.hardwareId }
        val fieldIds = document.stateFields.mapTo(hashSetOf()) { it.fieldId }
        val validLoops = document.controlLoops.filter {
            it.actuatorId in hardwareIds && it.targetFieldId in fieldIds
        }
        return if (validLoops.size == document.controlLoops.size) {
            document
        } else {
            document.copy(controlLoops = validLoops)
        }
    }

    internal fun buildDrivetrainPresetDocument(
        presetRaw: String,
        league: AresLeague,
        drivebaseId: String,
        driveUid: String,
        canonicalProfileUid: String,
    ): DrivetrainDocument = DrivetrainPresetBuilder.build(
        presetRaw = presetRaw,
        league = league,
        drivebaseId = drivebaseId,
        driveUid = driveUid,
        canonicalProfileUid = canonicalProfileUid,
    )

    private fun reconcileOtherTuningProfiles(
        canonical: File,
        primaryProfileRelPath: String,
        profiles: List<TuningProfileDocument>,
        newDrivebaseUid: String,
        allDeclarations: Map<String, com.areslib.tuning.TuningParameterDeclaration>,
        writes: MutableMap<String, String>,
    ) {
        val tuningDir = File(canonical, ".ares/tuning")
        val localDir = File(canonical, ".ares/local/tuning")
        val candidateFiles = (tuningDir.listFiles().orEmpty().toList() + localDir.listFiles().orEmpty().toList())
            .filter { it.isFile && it.extension == "arestuning" }
        for (file in candidateFiles) {
            val rel = file.relativeTo(canonical).invariantSeparatorsPath
            if (rel == primaryProfileRelPath) continue
            val rawText = runCatching { file.readText() }.getOrNull() ?: continue
            val fileUid = runCatching { JsonParser.parseString(rawText).asJsonObject.get("uid")?.asString }.getOrNull()
            val matched = profiles.firstOrNull { it.uid == fileUid }
                ?: runCatching { TuningProfileDocumentCodec.decode(rawText, allDeclarations.values) }.getOrNull()
                ?: continue
            val repaired = matched.copy(
                drivebaseUid = if (matched.drivebaseUid != null) newDrivebaseUid else null,
                values = matched.values.filter { it.parameterUid in allDeclarations },
            )
            if (repaired != matched) {
                writes[rel] = TuningProfileDocumentCodec.encode(repaired, allDeclarations.values)
            }
        }
    }

    companion object {
        private val keyedArrayFields = setOf(
            "hardware", "stateFields", "controlLoops", "tuningParameters", "components", "modules",
            "parameters", "states", "transitions", "interlocks", "healthFallbacks", "luts", "steps",
            "bindings", "controllers", "controls", "values", "entries", "actions", "conditions",
            "apriltags", "fieldWaypoints", "obstacles",
        )
        private val identityKeyOrder = listOf(
            "uid", "hardwareId", "fieldId", "loopId", "key", "stepId", "bindingId", "slot",
            "stateId", "transitionId", "ruleId", "policyId", "lutId", "entryId", "controlId",
            "parameterUid", "id",
        )

        internal suspend fun applyTransactionalFileBatch(
            engine: HeadlessProjectEngine,
            projectRoot: File,
            slug: String,
            documentKind: String,
            operation: String,
            documentId: String,
            primaryRelativePath: String,
            writes: Map<String, String>,
            deletes: List<String> = emptyList(),
            expectedRevision: String? = null,
            message: String,
        ): DescriptorMutationResult {
            val canonical = projectRoot.canonicalFile
            val previousRevision = engine.computeProjectRevision(canonical)
            if (expectedRevision != null && expectedRevision != previousRevision) {
                throw IllegalStateException(
                    "Revision conflict (CAS): expected '$expectedRevision' but project revision is '$previousRevision'.",
                )
            }
            val beforeValidation = engine.validateProject(canonical)
            val snapshot = JGitSnapshotSafety.createSnapshot(canonical, slug = "before-$slug")
            val impactedPaths = (writes.keys + deletes).distinct()
            val backupState = impactedPaths.associateWith { rel ->
                val f = JGitSnapshotSafety.safeResolveChild(canonical, rel)
                if (f.isFile) f.readBytes() else null
            }

            try {
                for (rel in deletes) {
                    val f = JGitSnapshotSafety.safeResolveChild(canonical, rel)
                    if (f.exists()) f.delete()
                }
                for ((rel, text) in writes) {
                    val f = JGitSnapshotSafety.safeResolveChild(canonical, rel)
                    JGitSnapshotSafety.writeBytesAtomically(f, text.toByteArray(Charsets.UTF_8))
                }
                val afterValidation = engine.validateProject(canonical)
                if (!afterValidation.valid && afterValidation.errorCount > beforeValidation.errorCount) {
                    rollbackFiles(canonical, backupState)
                    throw IllegalStateException(
                        "Mutation rejected & rolled back because it introduced validation error(s): " +
                            afterValidation.issues.filter { it.severity == "ERROR" }.joinToString("; ") { "${it.path}: ${it.message}" },
                    )
                }
                val newRevision = engine.computeProjectRevision(canonical)
                val reload = engine.bridgeClient.notifyReload(
                    projectRoot = canonical.path,
                    reason = "$operation $documentKind:$documentId",
                )
                return DescriptorMutationResult(
                    projectRoot = canonical.path,
                    documentKind = documentKind,
                    operation = operation,
                    documentId = documentId,
                    filePath = primaryRelativePath,
                    previousRevision = previousRevision,
                    newRevision = newRevision,
                    snapshotRef = snapshot.refName,
                    validation = afterValidation,
                    bridgeReload = reload,
                    message = message,
                )
            } catch (error: Exception) {
                runCatching { rollbackFiles(canonical, backupState) }
                throw error
            }
        }

        private fun rollbackFiles(projectRoot: File, backups: Map<String, ByteArray?>) {
            for ((rel, prev) in backups) {
                val f = JGitSnapshotSafety.safeResolveChild(projectRoot, rel)
                if (prev != null) {
                    JGitSnapshotSafety.writeBytesAtomically(f, prev)
                } else if (f.exists()) {
                    f.delete()
                }
            }
        }

        internal fun sanitizeSlugId(raw: String): String = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { "item" }

        internal fun toPascalCase(id: String): String = id.split('-', '_', '.', ' ')
            .filter { it.isNotBlank() }
            .joinToString("") { token -> token.lowercase().replaceFirstChar(Char::titlecase) }
            .ifBlank { "Mechanism" }

        internal fun toDisplayName(id: String): String = id.split('-', '_', '.', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token -> token.lowercase().replaceFirstChar(Char::titlecase) }
            .ifBlank { id }

        private fun toUidSegment(raw: String): String = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9.-]+"), "-")
            .trim('-', '.')
            .ifBlank { "robot" }
    }
}
