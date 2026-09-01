package com.ares.analytics.service.project.persistence

import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FieldImageConfig
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.GamePieceType
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.util.ProjectLayout
import com.ares.analytics.util.Sha256
import com.ares.analytics.viewmodel.field.FieldDocumentMapper
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import java.io.File

internal data class LoadedFieldDocument(
    val document: RobotFieldConfig,
    val imageConfig: FieldImageConfig,
    val obstacles: List<Obstacle>,
    val gamePieces: List<GamePiece>,
    val gamePieceTypes: List<GamePieceType>,
    val aprilTags: List<AprilTagPlacement>,
    val fieldWaypoints: List<FieldWaypoint>,
)

/** Canonical, history-preserving owner of the single project field document. */
internal object FieldDocumentStore {
    fun load(projectPath: String, league: League): LoadedFieldDocument {
        val canonicalFile = ProjectLayout.fieldDefinitionFile(projectPath, league)
        return if (canonicalFile.isFile) {
            fromDocument(RobotFieldDocument.decode(canonicalFile.readText()))
        } else {
            fromDocument(
                FieldDocumentMapper.newDocument(
                    league,
                    FieldDocumentMapper.defaultImageConfig(league),
                ),
            )
        }
    }

    fun fromDocument(document: RobotFieldConfig): LoadedFieldDocument = LoadedFieldDocument(
        document = document,
        imageConfig = FieldDocumentMapper.image(document),
        obstacles = FieldDocumentMapper.obstacles(document),
        gamePieces = FieldDocumentMapper.gamePieces(document),
        gamePieceTypes = FieldDocumentMapper.gamePieceTypes(document),
        aprilTags = FieldDocumentMapper.aprilTags(document),
        fieldWaypoints = FieldDocumentMapper.fieldWaypoints(document),
    )

    fun save(projectPath: String, league: League, document: RobotFieldConfig) {
        val encoded = RobotFieldDocument.encode(document)
        val validated = RobotFieldDocument.decode(encoded)
        val canonicalFile = ProjectLayout.fieldDefinitionFile(projectPath, league)
        ProjectDocumentWriteLocks.withLock(canonicalFile) {
            val previous = canonicalFile.takeIf(File::isFile)?.let { RobotFieldDocument.decode(it.readText()) }
            if (previous != null) checkpoint(projectPath, previous)
            checkpoint(projectPath, validated)
            if (previous != validated || !canonicalFile.isFile) {
                AtomicProjectFileWriter.write(canonicalFile, encoded, replaceExisting = true)
            }
        }
    }

    private fun checkpoint(projectPath: String, document: RobotFieldConfig) {
        val encoded = RobotFieldDocument.encode(document)
        val hash = Sha256.hex(encoded)
        val historyFile = File(
            resolveProjectPath(projectPath, ".ares/history/fields"),
            "${document.revision.toString().padStart(8, '0')}-${hash.take(12)}.json",
        )
        if (historyFile.isFile) {
            require(historyFile.readText() == encoded) {
                "Field history checkpoint '${historyFile.name}' already exists with different bytes"
            }
        } else {
            AtomicProjectFileWriter.write(historyFile, encoded, replaceExisting = false)
        }
    }
}
