package com.ares.analytics.ui.components.core

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import java.io.File
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AresFileChooserNavigation(state: AresFileChooserState) = with(state) {
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
                BasicTextField(
                    value = pathEditText,
                    onValueChange = { pathEditText = it },
                    singleLine = true,
                        textStyle = TextStyle(color = AresTextPrimary, fontSize = 12.sp),
                        cursorBrush = SolidColor(AresCyan),
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
                        Text("\u203a", color = AresTextSecondary, fontSize = 12.sp)
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
        onClick = ::refresh,
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
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                decorationBox = { field ->
                        if (searchQuery.isEmpty()) Text("Filter...", color = AresTextSecondary.copy(alpha = 0.6f), fontSize = 12.sp)
                        field()
                    },
                singleLine = true,
                        textStyle = TextStyle(color = AresTextPrimary, fontSize = 12.sp),
                        cursorBrush = SolidColor(AresCyan),
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
