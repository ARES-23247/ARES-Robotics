package com.ares.analytics.ui.components.core

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import com.ares.analytics.ui.theme.*
import java.awt.Dialog
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Window
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.JDialog

/**
 * Mode of operation for [AresFileChooserContent].
 */
enum class AresFileChooserMode {
    DIRECTORY,
    OPEN_FILE,
    OPEN_FILES,
    SAVE_FILE
}

/**
 * Metadata for detected robot project flavors.
 */
internal enum class RobotProjectFlavor(val displayName: String, val badgeColor: Color) {
    ARES("ARES Project", Color(0xFF00E5FF)),
    FTC("FTC Robot", Color(0xFF29B6F6)),
    FRC("FRC Robot", Color(0xFFFF9800)),
    XRP("XRP Micro", Color(0xFFAB47BC)),
    GRADLE("Gradle Project", Color(0xFF4CAF50))
}

/**
 * Inspects a folder to detect if it matches any robotics project type.
 */
internal fun detectRobotFlavor(dir: File): RobotProjectFlavor? {
    if (!dir.isDirectory) return null
    return runCatching {
        val names = dir.list()?.toSet() ?: return null
        when {
            names.contains(".ares") -> RobotProjectFlavor.ARES
            names.contains("TeamCode") || names.contains("FtcRobotController") -> RobotProjectFlavor.FTC
            names.contains("marvin") || File(dir, "src/main/deploy").isDirectory -> RobotProjectFlavor.FRC
            names.contains("ares_micro") || (names.contains("main.py") && names.contains("tools")) -> RobotProjectFlavor.XRP
            names.contains("settings.gradle.kts") || names.contains("settings.gradle") -> RobotProjectFlavor.GRADLE
            else -> null
        }
    }.getOrNull()
}

/**
 * Sort column options for the file chooser table.
 */
internal enum class FileSortColumn {
    NAME,
    TYPE,
    DATE_MODIFIED,
    SIZE
}

/**
 * Launcher object that opens the Compose-based file chooser in a modal Swing dialog.
 */
internal object AresFileChooserLauncher {

    /** Test hook to allow automated tests to inspect or supply file choices without UI interaction. */
    @Volatile
    var activeDialog: JDialog? = null

    @Volatile
    var testSelectionOverride: ((File) -> Unit)? = null

    fun show(
        mode: AresFileChooserMode,
        dialogTitle: String,
        initialDirectory: File?,
        defaultFileName: String? = null,
        filterDescription: String? = null,
        extensions: List<String> = emptyList(),
        approveButtonText: String? = null,
    ): List<File>? {
        val resultRef = AtomicReference<List<File>?>(null)

        val showAction = {
            val owner = Window.getWindows().firstOrNull { it.isShowing && (it.isFocused || it.isActive) }
                ?: Window.getWindows().firstOrNull { it.isShowing }

            val dialog = JDialog(owner, dialogTitle, Dialog.ModalityType.APPLICATION_MODAL).apply {
                defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
                size = Dimension(940, 640)
                minimumSize = Dimension(740, 480)
                setLocationRelativeTo(owner)
                runCatching {
                    AresBrandTokens::class.java.classLoader.getResourceAsStream("brand/ares-studio-app.png")?.use { stream ->
                        ImageIO.read(stream)?.let { setIconImages(listOf(it)) }
                    }
                }
            }

            activeDialog = dialog

            testSelectionOverride = { file ->
                resultRef.set(listOf(file))
                dialog.dispose()
            }

            val composePanel = ComposePanel().apply {
                setContent {
                    AresTheme {
                        AresFileChooserContent(
                            mode = mode,
                            dialogTitle = dialogTitle,
                            initialDirectory = initialDirectory,
                            defaultFileName = defaultFileName,
                            filterDescription = filterDescription,
                            extensions = extensions,
                            approveButtonText = approveButtonText,
                            onConfirm = { files ->
                                resultRef.set(files)
                                dialog.dispose()
                            },
                            onCancel = {
                                resultRef.set(null)
                                dialog.dispose()
                            }
                        )
                    }
                }
            }

            dialog.contentPane.add(composePanel)
            try {
                dialog.isVisible = true
            } finally {
                dialog.dispose()
                activeDialog = null
                testSelectionOverride = null
            }
        }

        if (EventQueue.isDispatchThread()) {
            showAction()
        } else {
            EventQueue.invokeAndWait(showAction)
        }

        return resultRef.get()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AresFileChooserContent(
    mode: AresFileChooserMode,
    dialogTitle: String,
    initialDirectory: File?,
    defaultFileName: String? = null,
    filterDescription: String? = null,
    extensions: List<String> = emptyList(),
    approveButtonText: String? = null,
    onConfirm: (List<File>) -> Unit,
    onCancel: () -> Unit,
) {
    val state = remember(mode, initialDirectory, defaultFileName, extensions) {
        AresFileChooserState(mode, dialogTitle, initialDirectory, defaultFileName,
            filterDescription, extensions, approveButtonText, onConfirm, onCancel)
    }
    Surface(modifier = Modifier.fillMaxSize(), color = AresBackground) {
        Column(modifier = Modifier.fillMaxSize()) {
            AresFileChooserNavigation(state)
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AresFileChooserSidebar(state)
                AresFileChooserEntries(state, Modifier.weight(1f))
            }
            AresFileChooserActions(state)
        }
    }
}
