package com.ares.analytics.viewmodel.field

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.ares.analytics.shared.League
import com.ares.analytics.util.ProjectLayout
import org.jetbrains.skia.Image

/** Shared portable image-loading boundary for the editor, planners, and dashboard. */
internal object FieldImageLoader {
    fun load(projectPath: String, league: League, configuredPath: String?): Result<ImageBitmap?> = runCatching {
        val displayPath = configuredPath?.trim()?.takeIf(String::isNotEmpty) ?: return@runCatching null
        val imageFile = ProjectLayout.fieldImageFile(projectPath, league, displayPath)
        require(imageFile.isFile) {
            "Field image '$displayPath' was not found in the robot assets folder."
        }
        Image.makeFromEncoded(imageFile.readBytes()).toComposeImageBitmap()
    }
}
