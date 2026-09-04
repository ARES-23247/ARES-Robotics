package com.ares.analytics.ui.screens

import com.ares.analytics.service.versioncontrol.ProjectArchiveExporter
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.util.DesktopFileChoosers
import java.io.File

internal fun chooseStandaloneArchiveDestination(config: WorkspaceConfig): File? {
    val projectRoot = File(config.projectPath).canonicalFile
    val defaultName = config.robotId
        .ifBlank { config.robotName.ifBlank { "ares-robot" } }
        .replace(Regex("[^A-Za-z0-9._-]"), "-") + "-standalone.zip"
    return DesktopFileChoosers.chooseSaveFile(
        dialogTitle = "Export standalone robot archive",
        defaultFileName = defaultName,
        initialDirectory = projectRoot.parentFile,
        filterDescription = "ZIP archive",
        extensions = listOf("zip"),
    )
}

internal suspend fun exportStandaloneArchive(
    config: WorkspaceConfig,
    exporter: ProjectArchiveExporter,
    destination: File,
): String {
    val projectRoot = File(config.projectPath).canonicalFile
    return runCatching { exporter.export(projectRoot.path, destination.path) }.fold(
        onSuccess = { result ->
            "Exported ${result.fileCount} project files to ${result.destinationPath}. " +
                "Extract the archive to work from an IDE or terminal; build outputs and secrets were excluded."
        },
        onFailure = { error -> "Standalone export failed: ${error.message ?: error::class.simpleName}" },
    )
}
