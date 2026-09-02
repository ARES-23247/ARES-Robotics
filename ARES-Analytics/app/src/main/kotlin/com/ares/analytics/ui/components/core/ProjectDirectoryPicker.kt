package com.ares.analytics.ui.components.core

import com.ares.analytics.ui.util.DesktopFileChoosers
import java.io.File

/** Opens the native directory chooser at the current robot repository when possible. */
internal fun chooseProjectDirectory(currentPath: String?): File? =
    DesktopFileChoosers.chooseDirectory(
        dialogTitle = "Choose robot repository",
        initialPath = currentPath,
        approveButtonText = "Use this project"
    )
