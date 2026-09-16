// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.ui.components.core

import com.ares.analytics.ui.util.DesktopFileChoosers
import java.awt.Desktop
import java.io.File
import java.net.URI

/** Opens the native directory chooser at the current robot repository when possible. */
internal fun chooseProjectDirectory(currentPath: String?): File? =
    DesktopFileChoosers.chooseDirectory(
        dialogTitle = "Choose robot repository",
        initialPath = currentPath,
        approveButtonText = "Use this project"
    )

/** Opens an external link when the current desktop supports browsing; failure is non-fatal. */
internal fun openExternalLink(url: String): Boolean = runCatching {
    if (!Desktop.isDesktopSupported()) return@runCatching false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return@runCatching false
    desktop.browse(URI(url))
    true
}.getOrDefault(false)
