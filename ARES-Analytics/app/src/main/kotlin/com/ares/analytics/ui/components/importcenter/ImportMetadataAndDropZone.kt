@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.ares.analytics.ui.components.importcenter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import java.awt.datatransfer.DataFlavor
import java.io.File

@Composable
fun ImportDropZoneAndMetadataCard(
    enabled: Boolean,
    pendingMatchNumber: String,
    pendingAlliance: String,
    pendingTagsText: String,
    onMatchNumberChange: (String) -> Unit,
    onAllianceChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    onChooseFilesClick: () -> Unit,
    onFilesDropped: (List<File>) -> Unit,
) {
    var isDragHovering by remember { mutableStateOf(false) }

    val dropTarget = remember(enabled, onFilesDropped) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                if (enabled) isDragHovering = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isDragHovering = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                isDragHovering = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDragHovering = false
                if (!enabled) return false
                val files = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    (event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                        ?.mapNotNull { it as? File }
                        ?.filter(File::isFile)
                        .orEmpty()
                }.getOrDefault(emptyList())
                if (files.isEmpty()) return false
                onFilesDropped(files)
                return true
            }
        }
    }

    val borderColor = if (isDragHovering) AresGreen else AresCyan.copy(alpha = 0.65f)
    val fillColor = if (isDragHovering) AresGreen.copy(alpha = 0.14f) else AresCyan.copy(alpha = 0.08f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(if (isDragHovering) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    enabled && runCatching {
                        event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                    }.getOrDefault(false)
                },
                target = dropTarget,
            ),
        color = fillColor,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isDragHovering) Icons.Default.CloudUpload else Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = if (isDragHovering) AresGreen else AresCyan,
                    modifier = Modifier.size(28.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (isDragHovering) "Drop robot or simulator logs here to import" else "Import a completed run (drag & drop or browse)",
                        color = AresTextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Drag log files directly onto this card or choose files from disk. ARES verifies and archives a copy inside this workspace without altering your originals.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                    Text(
                        "CSV/CSV.GZ · JSONL · Parquet · WPILOG/WPILOGXZ · DSLOG · RLOG · REVLOG · Hoot · Road Runner LOG",
                        color = AresTextTertiary,
                        fontSize = 10.sp,
                    )
                }
                Button(
                    onClick = onChooseFilesClick,
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Choose log files", fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = AresBorder.copy(alpha = 0.65f))

            // Optional Import-Time Metadata Bar (Match #, Alliance, Cohort Tags)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, tint = AresCyan, modifier = Modifier.size(15.dp))
                    Text("Tag on import:", color = AresTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(
                    value = pendingMatchNumber,
                    onValueChange = onMatchNumberChange,
                    label = { Text("Match #", fontSize = 10.sp) },
                    placeholder = { Text("Opt.", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.width(105.dp),
                )
                listOf("" to "Practice", "red" to "Red", "blue" to "Blue").forEach { (code, label) ->
                    FilterChip(
                        selected = pendingAlliance.equals(code, ignoreCase = true),
                        onClick = { onAllianceChange(code) },
                        label = { Text(label, fontSize = 11.sp) },
                    )
                }
                OutlinedTextField(
                    value = pendingTagsText,
                    onValueChange = onTagsChange,
                    label = { Text("Cohort tags (comma-separated)", fontSize = 10.sp) },
                    placeholder = { Text("e.g. intake-v2, quals, new-battery", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.width(280.dp),
                )
            }
        }
    }
}
