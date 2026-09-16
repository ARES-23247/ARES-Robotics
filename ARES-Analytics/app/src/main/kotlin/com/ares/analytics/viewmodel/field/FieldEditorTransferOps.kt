package com.ares.analytics.viewmodel.field

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.ares.analytics.service.writeFileAtomically
import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.models.League
import com.ares.analytics.util.ProjectLayout
import com.ares.analytics.viewmodel.AprilTagExportFormat
import com.ares.analytics.viewmodel.AprilTagImportPreview
import com.ares.analytics.viewmodel.FieldEditorIntent
import com.ares.analytics.viewmodel.FieldEditorState
import com.areslib.state.RobotFieldConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object FieldEditorTransferOps {
    suspend fun importFieldImage(
        imageFile: File,
        projectPath: String,
        league: League,
    ): ImageBitmap = withContext(Dispatchers.IO) {
        val target = File(ProjectLayout.assetsDirectory(projectPath, league), "field_image.png")
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".field_image.png.tmp")
        try {
            Files.copy(imageFile.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        org.jetbrains.skia.Image.makeFromEncoded(target.readBytes()).toComposeImageBitmap()
    }

    fun previewAprilTagMap(
        intent: FieldEditorIntent.PreviewAprilTagMap,
        document: RobotFieldConfig?,
        existingTags: List<AprilTagPlacement>,
        activeLeague: League,
        activeProjectPath: String?,
    ): AprilTagImportPreview {
        require(intent.league == activeLeague && (intent.projectPath == null || intent.projectPath == activeProjectPath)) {
            "Load the target project and league before importing AprilTags"
        }
        val doc = requireNotNull(document) { "Load a field before importing its AprilTags" }
        return FieldAprilTagTransfer.decode(
            content = intent.content,
            fileName = intent.fileName,
            field = doc,
            existingTags = existingTags,
            league = intent.league,
        )
    }

    fun applyAprilTagImport(
        preview: AprilTagImportPreview,
        state: FieldEditorState,
        replaceExisting: Boolean,
    ): FieldEditorState {
        val tags = if (replaceExisting) {
            preview.tags
        } else {
            val existingIds = state.aprilTags.mapTo(hashSetOf()) { it.tagId }
            state.aprilTags + preview.tags.filterNot { it.tagId in existingIds }
        }
        return state.copy(
            aprilTags = tags,
            fieldImageConfig = if (replaceExisting) {
                state.fieldImageConfig.copy(
                    widthMeters = preview.fieldLengthMeters ?: state.fieldImageConfig.widthMeters,
                    heightMeters = preview.fieldWidthMeters ?: state.fieldImageConfig.heightMeters,
                )
            } else {
                state.fieldImageConfig
            },
        )
    }

    suspend fun exportAprilTagMap(
        document: RobotFieldConfig,
        format: AprilTagExportFormat,
        destination: File,
    ) {
        val content = withContext(Dispatchers.Default) {
            FieldAprilTagTransfer.encode(document, format)
        }
        withContext(Dispatchers.IO) {
            writeFileAtomically(destination) { temporary ->
                temporary.writeText(content.trimEnd() + System.lineSeparator())
            }
        }
    }
}
