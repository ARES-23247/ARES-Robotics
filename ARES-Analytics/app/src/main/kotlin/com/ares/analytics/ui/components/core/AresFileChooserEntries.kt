package com.ares.analytics.ui.components.core

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AresFileChooserEntries(state: AresFileChooserState, modifier: Modifier = Modifier) = with(state) {
// File List Area
Column(
    modifier = Modifier
        .then(modifier)
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
                                    if (mode == AresFileChooserMode.SAVE_FILE) fileNameInput = file.name
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
