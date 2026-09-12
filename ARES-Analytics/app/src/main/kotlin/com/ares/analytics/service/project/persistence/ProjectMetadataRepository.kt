package com.ares.analytics.service.project.persistence

import com.ares.analytics.shared.AppJson
import com.ares.analytics.util.Sha256
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

data class SavedProjectMetadata(
    val document: AresProjectMetadataDocument,
    val contentHash: String,
    val historyFile: File?,
    val created: Boolean,
    val repaired: Boolean = false,
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
            check(repositoryDecodeFails(rawBytes)) {
                "The project identity became valid after preview. Reload it instead of replacing it through repair."
            }

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

    private fun repositoryDecodeFails(bytes: ByteArray): Boolean = runCatching {
        decodeProjectMetadata(bytes.toString(Charsets.UTF_8))
    }.isFailure

}
/** Adds a stable, student-facing shape check before the library codec touches non-null Kotlin fields. */
internal fun decodeProjectMetadata(json: String): AresProjectMetadataDocument {
    val root = runCatching { AppJson.parseToJsonElement(json) }.getOrElse { error ->
        throw IllegalArgumentException("Project metadata is not valid JSON: ${error.message}", error)
    }
    val objectValue = root as? JsonObject
        ?: throw IllegalArgumentException("Project metadata must be one JSON object.")
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
    require(!primitive("schemaVersion").isString && primitive("schemaVersion").intOrNull != null) {
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
