package com.ares.analytics.ui.components.core

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.components.forms.AresTextField
import com.ares.analytics.ui.theme.*
import java.awt.Dialog
import java.io.File
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AresFileChooserActions(state: AresFileChooserState) = with(state) {
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
                    contentColor = AresOnAccent,
                    disabledContainerColor = AresBorder,
                    disabledContentColor = AresTextSecondary.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(effectiveApproveText, fontWeight = FontWeight.Bold)
            }
        }
    }
// New Folder Dialog Modal
if (showNewFolderDialog) {
    AresDialog(
        title = "Create New Folder",
        onDismiss = { showNewFolderDialog = false },
        confirmText = "Create",
        onConfirm = ::createFolder,
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

    pendingOverwrite?.let { target ->
        AresDialog(title = "Replace existing file?", onDismiss = { pendingOverwrite = null },
            confirmText = "Replace", onConfirm = ::confirmOverwrite) {
            Text("${target.name} already exists. Replace it?", color = AresTextPrimary)
        }
    }
    errorText?.let { message ->
        AresDialog(title = "File chooser", onDismiss = { errorText = null },
            confirmText = "OK", onConfirm = { errorText = null }) {
            Text(message, color = AresTextPrimary)
        }
    }
}
