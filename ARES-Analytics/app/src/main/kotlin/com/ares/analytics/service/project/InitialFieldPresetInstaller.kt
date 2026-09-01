package com.ares.analytics.service.project

import com.ares.analytics.service.writeFileAtomically
import com.ares.analytics.shared.models.League
import com.ares.analytics.util.ProjectLayout
import com.areslib.state.RobotFieldDocument
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Installs a reviewed season layout without discarding starter-owned simulation content. */
internal object InitialFieldPresetInstaller {
    fun install(
        root: File,
        league: League,
        robotName: String,
        resourcePath: String,
        resourceLoader: (String) -> InputStream?,
    ) {
        val fieldFile = ProjectLayout.fieldDefinitionFile(root.path, league)
        check(fieldFile.isFile) { "The reviewed starter is missing its canonical field document." }
        val presetJson = requireNotNull(resourceLoader(resourcePath)) {
            "The reviewed initial field preset '$resourcePath' is missing from this installation."
        }.bufferedReader().use { it.readText() }
        val current = RobotFieldDocument.decode(fieldFile.readText())
        val preset = RobotFieldDocument.decode(presetJson)
        check(preset.fieldType == current.fieldType) { "The reviewed initial field preset has the wrong league." }
        check(preset.apriltags.isNotEmpty()) {
            "The reviewed initial field preset does not contain an AprilTag layout."
        }
        check(
            preset.resolvedWidthMeters == current.resolvedWidthMeters &&
                preset.resolvedHeightMeters == current.resolvedHeightMeters &&
                preset.xAxisDirection == current.xAxisDirection &&
                preset.yAxisDirection == current.yAxisDirection
        ) {
            "The reviewed initial field preset does not match the starter coordinate frame."
        }
        val personalized = current.copy(
            revision = current.revision + 1L,
            name = "${robotName.trim()} Field",
            gameYear = preset.gameYear,
            allianceSymmetry = preset.allianceSymmetry,
            apriltags = preset.apriltags,
        )
        writeFileAtomically(fieldFile) { temporary ->
            Files.writeString(
                temporary.toPath(),
                RobotFieldDocument.encode(personalized).trimEnd() + System.lineSeparator(),
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }
    }
}
