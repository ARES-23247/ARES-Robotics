// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.ui.components.core

import java.awt.Desktop
import java.net.URI

/** Opens an external link when the current desktop supports browsing; failure is non-fatal. */
internal fun openExternalLink(url: String): Boolean = runCatching {
    if (!Desktop.isDesktopSupported()) return@runCatching false
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return@runCatching false
    desktop.browse(URI(url))
    true
}.getOrDefault(false)
