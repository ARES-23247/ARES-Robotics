package com.ares.analytics.ui.components.core

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.components.forms.AresTextField
import com.ares.analytics.ui.theme.*
import java.awt.Dialog
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Window
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.JDialog

/**
 * Mode of operation for [AresFileChooserDialog].
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
                    val stream = AresBrandTokens::class.java.classLoader.getResourceAsStream("brand/ares-studio-app.png")
                    if (stream != null) {
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

/**
 * Top-level Composable content of the modern ARES File Chooser.
 */
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
    val normalizedExtensions = remember(extensions) {
        extensions.map { it.trim().removePrefix(".").lowercase() }.filter(String::isNotEmpty)
    }

    val userHome = remember { File(System.getProperty("user.home")).canonicalFile }
    val initialDir = remember(initialDirectory) {
        val target = initialDirectory?.takeIf(File::exists)?.canonicalFile
            ?: initialDirectory?.parentFile?.takeIf(File::exists)?.canonicalFile
            ?: File(userHome, "Documents").takeIf(File::exists)
            ?: userHome
        if (target.isDirectory) target else target.parentFile ?: userHome
    }

    var currentDirectory by remember { mutableStateOf(initialDir) }
    var history by remember { mutableStateOf(listOf(initialDir)) }
    var historyIndex by remember { mutableStateOf(0) }

    var selectedFiles by remember { mutableStateOf<Set<File>>(emptySet()) }
    var fileNameInput by remember { mutableStateOf(defaultFileName ?: "") }
    var searchQuery by remember { mutableStateOf("") }
    var isEditingPath by remember { mutableStateOf(false) }
    var pathEditText by remember { mutableStateOf(currentDirectory.absolutePath) }

    var sortColumn by remember { mutableStateOf(FileSortColumn.NAME) }
    var sortAscending by remember { mutableStateOf(true) }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("New Folder") }

    fun navigateTo(dir: File) {
        val canonical = dir.canonicalFile
        if (!canonical.exists() || !canonical.isDirectory) return
        currentDirectory = canonical
        pathEditText = canonical.absolutePath
        selectedFiles = emptySet()
        val newHistory = history.take(historyIndex + 1) + canonical
        history = newHistory
        historyIndex = newHistory.lastIndex
    }

    fun navigateBack() {
        if (historyIndex > 0) {
            historyIndex--
            currentDirectory = history[historyIndex]
            pathEditText = currentDirectory.absolutePath
            selectedFiles = emptySet()
        }
    }

    fun navigateForward() {
        if (historyIndex < history.lastIndex) {
            historyIndex++
            currentDirectory = history[historyIndex]
            pathEditText = currentDirectory.absolutePath
            selectedFiles = emptySet()
        }
    }

    fun navigateUp() {
        currentDirectory.parentFile?.let(::navigateTo)
    }

    // Refresh file entries for currentDirectory
    val directoryEntries = remember(currentDirectory, normalizedExtensions, mode) {
        val all = runCatching { currentDirectory.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
        all.filter { file ->
            if (file.isDirectory) {
                true
            } else when (mode) {
                AresFileChooserMode.DIRECTORY -> false
                else -> {
                    if (normalizedExtensions.isEmpty()) true
                    else normalizedExtensions.any { ext -> file.name.endsWith(".$ext", ignoreCase = true) }
                }
            }
        }
    }

    val filteredEntries = remember(directoryEntries, searchQuery, sortColumn, sortAscending) {
        val query = searchQuery.trim().lowercase()
        val searched = if (query.isEmpty()) {
            directoryEntries
        } else {
            directoryEntries.filter { it.name.lowercase().contains(query) }
        }

        val comparator = Comparator<File> { a, b ->
            if (a.isDirectory != b.isDirectory) {
                if (a.isDirectory) -1 else 1
            } else {
                val comp = when (sortColumn) {
                    FileSortColumn.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                    FileSortColumn.TYPE -> {
                        val extA = a.extension.lowercase()
                        val extB = b.extension.lowercase()
                        extA.compareTo(extB)
                    }
                    FileSortColumn.DATE_MODIFIED -> a.lastModified().compareTo(b.lastModified())
                    FileSortColumn.SIZE -> a.length().compareTo(b.length())
                }
                if (sortAscending) comp else -comp
            }
        }
        searched.sortedWith(comparator)
    }

    val effectiveApproveText = approveButtonText ?: when (mode) {
        AresFileChooserMode.DIRECTORY -> "Select Folder"
        AresFileChooserMode.SAVE_FILE -> "Save"
        AresFileChooserMode.OPEN_FILE, AresFileChooserMode.OPEN_FILES -> "Open"
    }

    fun handleApprove() {
        when (mode) {
            AresFileChooserMode.DIRECTORY -> {
                val target = selectedFiles.firstOrNull()?.takeIf(File::isDirectory) ?: currentDirectory
                onConfirm(listOf(target.canonicalFile))
            }
            AresFileChooserMode.OPEN_FILE -> {
                val target = selectedFiles.firstOrNull { it.isFile }
                if (target != null) onConfirm(listOf(target.canonicalFile))
            }
            AresFileChooserMode.OPEN_FILES -> {
                val targets = selectedFiles.filter(File::isFile).map(File::getCanonicalFile)
                if (targets.isNotEmpty()) onConfirm(targets)
            }
            AresFileChooserMode.SAVE_FILE -> {
                val name = fileNameInput.trim()
                if (name.isNotEmpty()) {
                    var targetFile = File(currentDirectory, name).canonicalFile
                    if (normalizedExtensions.isNotEmpty() && normalizedExtensions.none { targetFile.name.endsWith(".$it", ignoreCase = true) }) {
                        targetFile = File(currentDirectory, "$name.${normalizedExtensions.first()}").canonicalFile
                    }
                    onConfirm(listOf(targetFile))
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AresBackground,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AresSurface)
                    .border(1.dp, AresBorder)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = if (mode == AresFileChooserMode.DIRECTORY) Icons.Default.FolderOpen else Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = AresCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = dialogTitle,
                            color = AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        if (filterDescription != null) {
                            Text(
                                text = filterDescription,
                                color = AresTextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }

                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = AresTextSecondary)
                }
            }

            // Top Navigation & Breadcrumbs Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AresSurfaceElevated)
                    .border(1.dp, AresBorder)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Navigation buttons
                IconButton(
                    onClick = ::navigateBack,
                    enabled = historyIndex > 0,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = if (historyIndex > 0) AresCyan else AresTextSecondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = ::navigateForward,
                    enabled = historyIndex < history.lastIndex,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = "Forward",
                        tint = if (historyIndex < history.lastIndex) AresCyan else AresTextSecondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = ::navigateUp,
                    enabled = currentDirectory.parentFile != null,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = "Up",
                        tint = if (currentDirectory.parentFile != null) AresCyan else AresTextSecondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Breadcrumb path display or editable text field
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(AresSurface, RoundedCornerShape(6.dp))
                        .border(1.dp, if (isEditingPath) AresCyan else AresBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (isEditingPath) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextField(
                                value = pathEditText,
                                onValueChange = { pathEditText = it },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = AresTextPrimary,
                                    unfocusedTextColor = AresTextPrimary,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    val target = File(pathEditText.trim())
                                    if (target.exists() && target.isDirectory) {
                                        navigateTo(target)
                                        isEditingPath = false
                                    }
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Go", tint = AresCyan, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = {
                                    isEditingPath = false
                                    pathEditText = currentDirectory.absolutePath
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel edit", tint = AresTextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    } else {
                        // Clickable Breadcrumbs
                        val breadcrumbScrollState = rememberScrollState()
                        LaunchedEffect(currentDirectory) {
                            breadcrumbScrollState.scrollTo(breadcrumbScrollState.maxValue)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(breadcrumbScrollState)
                                .clickable { isEditingPath = true },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val segments = remember(currentDirectory) {
                                generatePathSegments(currentDirectory)
                            }
                            segments.forEachIndexed { index, (name, target) ->
                                Text(
                                    text = name,
                                    color = if (index == segments.lastIndex) AresCyan else AresTextPrimary,
                                    fontWeight = if (index == segments.lastIndex) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clickable { navigateTo(target) }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                                if (index < segments.lastIndex) {
                                    Text("›", color = AresTextSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // New Folder Button
                IconButton(
                    onClick = { showNewFolderDialog = true },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder", tint = AresCyan, modifier = Modifier.size(20.dp))
                }

                // Refresh Button
                IconButton(
                    onClick = { navigateTo(currentDirectory) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AresTextSecondary, modifier = Modifier.size(18.dp))
                }

                // Search Box
                Box(
                    modifier = Modifier
                        .width(170.dp)
                        .height(36.dp)
                        .background(AresSurface, RoundedCornerShape(6.dp))
                        .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = AresTextSecondary, modifier = Modifier.size(16.dp))
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter...", color = AresTextSecondary.copy(alpha = 0.6f), fontSize = 12.sp) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = AresTextPrimary,
                                unfocusedTextColor = AresTextPrimary,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = AresTextSecondary, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }

            // Main Content Area: Sidebar + File List
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Left Quick Access Sidebar
                Column(
                    modifier = Modifier
                        .width(210.dp)
                        .fillMaxHeight()
                        .background(AresSurfaceElevated)
                        .border(1.dp, AresBorder)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "QUICK ACCESS",
                        color = AresTextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )

                    SidebarItem(
                        icon = Icons.Default.Home,
                        label = "User Home",
                        selected = currentDirectory == userHome,
                        onClick = { navigateTo(userHome) }
                    )

                    val docDir = File(userHome, "Documents")
                    if (docDir.exists()) {
                        SidebarItem(
                            icon = Icons.Default.Folder,
                            label = "Documents",
                            selected = currentDirectory == docDir,
                            onClick = { navigateTo(docDir) }
                        )
                    }

                    val downloadDir = File(userHome, "Downloads")
                    if (downloadDir.exists()) {
                        SidebarItem(
                            icon = Icons.Default.Download,
                            label = "Downloads",
                            selected = currentDirectory == downloadDir,
                            onClick = { navigateTo(downloadDir) }
                        )
                    }

                    val desktopDir = File(userHome, "Desktop")
                    if (desktopDir.exists()) {
                        SidebarItem(
                            icon = Icons.Default.Computer,
                            label = "Desktop",
                            selected = currentDirectory == desktopDir,
                            onClick = { navigateTo(desktopDir) }
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "ROBOTICS",
                        color = AresTextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )

                    val aresDocDir = File(docDir, "ARES")
                    if (aresDocDir.exists()) {
                        SidebarItem(
                            icon = Icons.Default.PrecisionManufacturing,
                            label = "ARES Projects",
                            selected = currentDirectory == aresDocDir,
                            badgeText = "ARES",
                            onClick = { navigateTo(aresDocDir) }
                        )
                    }

                    val robotsDir = File(userHome, "Robots")
                    if (robotsDir.exists()) {
                        SidebarItem(
                            icon = Icons.Default.PrecisionManufacturing,
                            label = "Robots",
                            selected = currentDirectory == robotsDir,
                            onClick = { navigateTo(robotsDir) }
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "DRIVES",
                        color = AresTextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )

                    val roots = remember { File.listRoots().orEmpty() }
                    roots.forEach { root ->
                        val driveLabel = root.absolutePath.ifEmpty { "Drive" }
                        SidebarItem(
                            icon = Icons.Default.Storage,
                            label = driveLabel,
                            selected = currentDirectory == root,
                            onClick = { navigateTo(root) }
                        )
                    }
                }

                // File List Area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(AresBackground)
                ) {
                    // Column Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AresSurface)
                            .border(1.dp, AresBorder)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HeaderCell(
                            label = "Name",
                            sortCol = FileSortColumn.NAME,
                            currentCol = sortColumn,
                            isAsc = sortAscending,
                            modifier = Modifier.weight(0.48f),
                            onSort = {
                                if (sortColumn == FileSortColumn.NAME) sortAscending = !sortAscending
                                else { sortColumn = FileSortColumn.NAME; sortAscending = true }
                            }
                        )
                        HeaderCell(
                            label = "Flavor / Type",
                            sortCol = FileSortColumn.TYPE,
                            currentCol = sortColumn,
                            isAsc = sortAscending,
                            modifier = Modifier.weight(0.22f),
                            onSort = {
                                if (sortColumn == FileSortColumn.TYPE) sortAscending = !sortAscending
                                else { sortColumn = FileSortColumn.TYPE; sortAscending = true }
                            }
                        )
                        HeaderCell(
                            label = "Modified",
                            sortCol = FileSortColumn.DATE_MODIFIED,
                            currentCol = sortColumn,
                            isAsc = sortAscending,
                            modifier = Modifier.weight(0.18f),
                            onSort = {
                                if (sortColumn == FileSortColumn.DATE_MODIFIED) sortAscending = !sortAscending
                                else { sortColumn = FileSortColumn.DATE_MODIFIED; sortAscending = false }
                            }
                        )
                        HeaderCell(
                            label = "Size",
                            sortCol = FileSortColumn.SIZE,
                            currentCol = sortColumn,
                            isAsc = sortAscending,
                            modifier = Modifier.weight(0.12f),
                            onSort = {
                                if (sortColumn == FileSortColumn.SIZE) sortAscending = !sortAscending
                                else { sortColumn = FileSortColumn.SIZE; sortAscending = false }
                            }
                        )
                    }

                    // Entries List
                    val listState = rememberLazyListState()
                    if (filteredEntries.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (searchQuery.isNotEmpty()) "No files or folders match \"$searchQuery\"" else "This folder is empty",
                                color = AresTextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(filteredEntries, key = { it.absolutePath }) { file ->
                                val isSelected = selectedFiles.contains(file)
                                val isDirectory = file.isDirectory
                                val robotFlavor = if (isDirectory) detectRobotFlavor(file) else null

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isSelected) AresCyanGlow else Color.Transparent)
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) AresCyan.copy(alpha = 0.5f) else Color.Transparent,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .combinedClickable(
                                            onClick = {
                                                if (mode == AresFileChooserMode.OPEN_FILES) {
                                                    selectedFiles = if (isSelected) selectedFiles - file else selectedFiles + file
                                                } else {
                                                    selectedFiles = setOf(file)
                                                    if (!isDirectory && mode == AresFileChooserMode.SAVE_FILE) {
                                                        fileNameInput = file.name
                                                    }
                                                }
                                            },
                                            onDoubleClick = {
                                                if (isDirectory) {
                                                    navigateTo(file)
                                                } else if (mode != AresFileChooserMode.DIRECTORY) {
                                                    selectedFiles = setOf(file)
                                                    handleApprove()
                                                }
                                            }
                                        )
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Name + Icon
                                    Row(
                                        modifier = Modifier.weight(0.48f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = resolveFileIcon(file),
                                            contentDescription = null,
                                            tint = if (isDirectory) AresCyan else AresTextSecondary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = file.name,
                                            color = if (isSelected) AresCyan else AresTextPrimary,
                                            fontWeight = if (isDirectory || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    // Flavor / Type Badge
                                    Box(modifier = Modifier.weight(0.22f)) {
                                        if (robotFlavor != null) {
                                            Surface(
                                                color = robotFlavor.badgeColor.copy(alpha = 0.15f),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, robotFlavor.badgeColor.copy(alpha = 0.6f)),
                                                shape = RoundedCornerShape(4.dp),
                                            ) {
                                                Text(
                                                    text = robotFlavor.displayName,
                                                    color = robotFlavor.badgeColor,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = if (isDirectory) "Folder" else file.extension.uppercase().ifEmpty { "File" },
                                                color = AresTextSecondary,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }

                                    // Modified Date
                                    Text(
                                        text = formatTimestamp(file.lastModified()),
                                        color = AresTextSecondary,
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(0.18f),
                                        maxLines = 1,
                                    )

                                    // Size
                                    Text(
                                        text = if (isDirectory) "--" else formatFileSize(file.length()),
                                        color = AresTextSecondary,
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(0.12f),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Selection & Action Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AresSurface)
                    .border(1.dp, AresBorder)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    when (mode) {
                        AresFileChooserMode.DIRECTORY -> {
                            val selected = selectedFiles.firstOrNull()?.takeIf(File::isDirectory)
                            if (selected != null) {
                                Text(
                                    text = "Selected: ${selected.name}",
                                    color = AresCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = selected.absolutePath,
                                    color = AresTextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Text(
                                    text = "Current folder: ${currentDirectory.name}",
                                    color = AresTextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = currentDirectory.absolutePath,
                                    color = AresTextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        AresFileChooserMode.SAVE_FILE -> {
                            AresTextField(
                                value = fileNameInput,
                                onValueChange = { fileNameInput = it },
                                label = "Save as file name",
                                placeholder = defaultFileName ?: "untitled",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        AresFileChooserMode.OPEN_FILE, AresFileChooserMode.OPEN_FILES -> {
                            val count = selectedFiles.size
                            Text(
                                text = if (count == 0) "No file selected" else if (count == 1) selectedFiles.first().name else "$count files selected",
                                color = if (count > 0) AresCyan else AresTextSecondary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                            if (count == 1) {
                                Text(
                                    text = selectedFiles.first().absolutePath,
                                    color = AresTextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Action Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AresTextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Cancel")
                    }

                    val canApprove = when (mode) {
                        AresFileChooserMode.DIRECTORY -> true
                        AresFileChooserMode.SAVE_FILE -> fileNameInput.isNotBlank()
                        AresFileChooserMode.OPEN_FILE -> selectedFiles.any(File::isFile)
                        AresFileChooserMode.OPEN_FILES -> selectedFiles.any(File::isFile)
                    }

                    Button(
                        onClick = ::handleApprove,
                        enabled = canApprove,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AresCyan,
                            contentColor = AresBackground,
                            disabledContainerColor = AresBorder,
                            disabledContentColor = AresTextSecondary.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(effectiveApproveText, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // New Folder Dialog Modal
        if (showNewFolderDialog) {
            AresDialog(
                title = "Create New Folder",
                onDismiss = { showNewFolderDialog = false },
                confirmText = "Create",
                onConfirm = {
                    val name = newFolderName.trim()
                    if (name.isNotEmpty()) {
                        val newDir = File(currentDirectory, name)
                        if (newDir.mkdirs() || newDir.isDirectory) {
                            navigateTo(newDir)
                        }
                    }
                    showNewFolderDialog = false
                    newFolderName = "New Folder"
                },
                isConfirmEnabled = newFolderName.isNotBlank(),
            ) {
                AresTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = "Folder name",
                    placeholder = "my-new-folder",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badgeText: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) AresCyanGlow else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) AresCyan else AresTextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = if (selected) AresCyan else AresTextPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (badgeText != null) {
            Surface(
                color = AresCyan.copy(alpha = 0.15f),
                shape = RoundedCornerShape(3.dp),
            ) {
                Text(
                    text = badgeText,
                    color = AresCyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun HeaderCell(
    label: String,
    sortCol: FileSortColumn,
    currentCol: FileSortColumn,
    isAsc: Boolean,
    modifier: Modifier = Modifier,
    onSort: () -> Unit,
) {
    Row(
        modifier = modifier.clickable(onClick = onSort),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = if (sortCol == currentCol) AresCyan else AresTextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        if (sortCol == currentCol) {
            Icon(
                imageVector = if (isAsc) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = AresCyan,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun resolveFileIcon(file: File): ImageVector {
    if (file.isDirectory) return Icons.Default.Folder
    val ext = file.extension.lowercase()
    return when (ext) {
        "png", "jpg", "jpeg", "svg", "bmp", "webp", "gif" -> Icons.Default.Image
        "mp4", "mov", "avi", "mkv", "webm" -> Icons.Default.Movie
        "json", "jsonl", "rlog", "revlog", "hoot", "csv", "parquet" -> Icons.Default.Analytics
        "kt", "java", "py", "xml", "gradle", "kts", "properties" -> Icons.Default.Code
        "zip", "tar", "gz", "7z", "jar" -> Icons.Default.Archive
        else -> Icons.Default.InsertDriveFile
    }
}

private fun generatePathSegments(dir: File): List<Pair<String, File>> {
    val segments = mutableListOf<Pair<String, File>>()
    var curr: File? = dir.canonicalFile
    while (curr != null) {
        val name = curr.name.ifEmpty { curr.path }
        segments.add(0, name to curr)
        curr = curr.parentFile
    }
    return segments
}

private fun formatTimestamp(millis: Long): String {
    if (millis <= 0) return "--"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val unit = units[digitGroups.coerceIn(0, units.lastIndex)]
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, unit)
}
