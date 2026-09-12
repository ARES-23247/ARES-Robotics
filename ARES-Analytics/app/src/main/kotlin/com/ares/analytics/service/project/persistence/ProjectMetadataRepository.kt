package com.ares.analytics.service.project.persistence

import com.ares.analytics.shared.AppJson
import com.ares.analytics.util.Sha256
import com.areslib.project.ARES_PROJECT_METADATA_SCHEMA_VERSION
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

data class SavedProjectMetadata(
    val document: AresProjectMetadataDocument,
    val contentHash: String,
    val historyFile: File?,
    val created: Boolean,
    val repaired: Boolean = false,
)

/** The decode result and raw hash describe the same captured file contents. */
internal data class ProjectMetadataInspection(
    val result: Result<AresProjectMetadataDocument>,
    val rawContentHash: String?,
)

internal class UnsupportedProjectMetadataSchemaException(val schemaVersion: Int) : IllegalArgumentException(
    "Unsupported project metadata schema $schemaVersion; current Studio supports schema-$ARES_PROJECT_METADATA_SCHEMA_VERSION projects only and will not rewrite this project.",
)

/** Canonical, Git-tracked robot and field geometry at `.ares/project.json`. */
class ProjectMetadataRepository {
    fun file(projectPath: String): File = resolveProjectPath(projectPath, ".ares/project.json")

    fun load(projectPath: String): Result<AresProjectMetadataDocument> {
        val file = file(projectPath)
        if (!file.isFile) return Result.failure(NoSuchElementException("Project metadata does not exist at ${file.path}"))
        return runCatching { decodeProjectMetadata(file.readText()) }
    }

    fun rawContentHash(projectPath: String): String = Sha256.fileHex(file(projectPath))

    internal fun inspect(projectPath: String): ProjectMetadataInspection {
        val target = file(projectPath)
        if (!target.exists()) return ProjectMetadataInspection(
            Result.failure(NoSuchElementException("Project metadata does not exist at ${target.path}")),
            rawContentHash = null,
        )
        // I/O failures propagate without granting a repair token. Do not reread to hash a newer file.
        val bytes = target.readBytes()
        return ProjectMetadataInspection(
            runCatching { decodeProjectMetadata(bytes.toString(Charsets.UTF_8)) },
            Sha256.hex(bytes),
        )
    }

    /** Creates metadata once. Replacing an existing file requires a reviewed save or repair hash. */
    fun save(projectPath: String, document: AresProjectMetadataDocument): String =
        saveReviewed(projectPath, expectedContentHash = null, document).contentHash

    /**
     * Saves only after the caller reviewed a proposal based on [expectedContentHash].
     * A corrupt or concurrently changed current file is preserved and causes a visible failure.
     */
    fun saveReviewed(
        projectPath: String,
        expectedContentHash: String?,
        document: AresProjectMetadataDocument,
    ): SavedProjectMetadata {
        val encoded = AresProjectMetadataCodec.encode(document)
        val normalized = AresProjectMetadataCodec.decode(encoded)
        val target = file(projectPath)
        return ProjectDocumentWriteLocks.withLock(target) {
            val previous = target.takeIf(File::isFile)?.let { current ->
                decodeProjectMetadata(current.readText())
            }
            val previousEncoded = previous?.let(AresProjectMetadataCodec::encode)
            val actualHash = previousEncoded?.let(Sha256::hex)
            require(actualHash == expectedContentHash) {
                "Project identity changed after preview. Reload it, review the new diff, and try again."
            }

            val proposedHash = Sha256.hex(encoded)
            if (proposedHash == actualHash) {
                return@withLock SavedProjectMetadata(normalized, proposedHash, historyFile = null, created = false)
            }

            val historyFile = previous?.let {
                val oldHash = requireNotNull(actualHash)
                val history = resolveProjectPath(projectPath, ".ares/history/project/$oldHash.json")
                val oldContent = requireNotNull(previousEncoded)
                when {
                    !history.exists() -> AtomicProjectFileWriter.write(history, oldContent, replaceExisting = false)
                    history.readText() != oldContent -> error(
                        "Project identity history collision at ${history.path}; no files were replaced.",
                    )
                }
                history
            }
            AtomicProjectFileWriter.write(target, encoded, replaceExisting = previous != null)
            SavedProjectMetadata(normalized, proposedHash, historyFile, created = previous == null)
        }
    }

    /**
     * Explicitly repairs an invalid canonical identity after a reviewed diff.
     *
     * The exact invalid bytes are hash-bound to the preview and copied into recovery storage
     * before the canonical path is atomically replaced. A concurrent edit aborts the repair.
     */
    fun repairReviewed(
        projectPath: String,
        expectedRawContentHash: String,
        document: AresProjectMetadataDocument,
    ): SavedProjectMetadata {
        val encoded = AresProjectMetadataCodec.encode(document)
        val normalized = AresProjectMetadataCodec.decode(encoded)
        val target = file(projectPath)
        return ProjectDocumentWriteLocks.withLock(target) {
            require(target.isFile) {
                "The invalid project identity was removed after preview. Reload before creating a replacement."
            }
            val rawBytes = target.readBytes()
            val actualRawHash = Sha256.hex(rawBytes)
            require(actualRawHash == expectedRawContentHash) {
                "The invalid project identity changed after preview. Reload it, review the new repair, and try again."
            }
            val decodeFailure = runCatching { decodeProjectMetadata(rawBytes.toString(Charsets.UTF_8)) }.exceptionOrNull()
            check(decodeFailure != null) {
                "The project identity became valid after preview. Reload it instead of replacing it through repair."
            }
            // Reviewed repair restores damaged current/unknown-format bytes; it is not a migration.
            if (decodeFailure is UnsupportedProjectMetadataSchemaException) throw decodeFailure

            val recovery = resolveProjectPath(projectPath, ".ares/recovery/project/$actualRawHash.raw")
            when {
                !recovery.exists() -> AtomicProjectFileWriter.write(recovery, rawBytes, replaceExisting = false)
                !recovery.readBytes().contentEquals(rawBytes) -> error(
                    "Project identity recovery collision at ${recovery.path}; no files were replaced.",
                )
            }
            AtomicProjectFileWriter.write(target, encoded, replaceExisting = true)
            SavedProjectMetadata(
                document = normalized,
                contentHash = Sha256.hex(encoded),
                historyFile = recovery,
                created = false,
                repaired = true,
            )
        }
    }

}
/** Adds a stable, student-facing shape check before the library codec touches non-null Kotlin fields. */
internal fun decodeProjectMetadata(json: String): AresProjectMetadataDocument {
    val root = runCatching { AppJson.parseToJsonElement(json) }.getOrElse { error ->
        throw IllegalArgumentException("Project metadata is not valid JSON: ${error.message}", error)
    }
    val objectValue = root as? JsonObject
        ?: throw IllegalArgumentException("Project metadata must be one JSON object.")
    val schemaVersion = (objectValue["schemaVersion"] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()
        ?.let { runCatching { it.intValueExact() }.getOrNull() }
    // Classify an explicit version before checking fields belonging to the current schema.
    if (schemaVersion != null && schemaVersion != ARES_PROJECT_METADATA_SCHEMA_VERSION) {
        throw UnsupportedProjectMetadataSchemaException(schemaVersion)
    }
    val required = listOf(
        "schemaVersion",
        "projectId",
        "identity",
        "league",
        "coordinateConvention",
        "robotLengthMeters",
        "robotWidthMeters",
        "fieldLengthMeters",
        "fieldWidthMeters",
        "authoringModel",
        "runtimeOptions",
    )
    val missing = required.filter { field -> objectValue[field] == null || objectValue[field] is JsonNull }
    require(missing.isEmpty()) {
        "Project metadata is missing required ${if (missing.size == 1) "field" else "fields"}: ${missing.joinToString()}"
    }
    fun primitive(field: String): JsonPrimitive = objectValue.getValue(field) as? JsonPrimitive
        ?: throw IllegalArgumentException("Project metadata field '$field' must be a single value.")
    require(schemaVersion != null) {
        "Project metadata field 'schemaVersion' must be a whole number."
    }
    require(primitive("projectId").isString) {
        "Project metadata field 'projectId' must be text."
    }
    val league = primitive("league").takeIf { it.isString }?.content
    require(league == "FTC" || league == "FRC" || league == "XRP") {
        "Project metadata field 'league' must be FTC, FRC, or XRP."
    }
    val convention = primitive("coordinateConvention").takeIf { it.isString }?.content
    require(convention == "CENTER_ORIGIN_CCW" || convention == "BLUE_CORNER_ORIGIN_CCW") {
        "Project metadata field 'coordinateConvention' is not supported."
    }
    listOf("robotLengthMeters", "robotWidthMeters", "fieldLengthMeters", "fieldWidthMeters").forEach { field ->
        val value = primitive(field)
        require(!value.isString && value.doubleOrNull != null) {
            "Project metadata field '$field' must be a number in meters."
        }
    }
    return AresProjectMetadataCodec.decode(json)
}
