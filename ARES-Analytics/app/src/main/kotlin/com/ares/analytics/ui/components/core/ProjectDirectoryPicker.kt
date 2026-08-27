package com.ares.analytics.ui.components.core

import java.io.File
import javax.swing.JFileChooser

/** Opens the native directory chooser at the current robot repository when possible. */
internal fun chooseProjectDirectory(currentPath: String?): File? {
    val initial = currentPath?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::exists)
    val chooser = JFileChooser(initial).apply {
        dialogTitle = "Choose robot repository"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
        approveButtonText = "Use this project"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.canonicalFile
    } else {
        null
    }
}
