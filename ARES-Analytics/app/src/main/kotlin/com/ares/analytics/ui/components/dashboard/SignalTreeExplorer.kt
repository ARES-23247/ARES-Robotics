package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*

data class FlattenedSignalItem(
    val node: SignalNode,
    val depth: Int,
    val currentPath: String,
    val cleanPath: String,
    val isLeaf: Boolean,
    val isExpanded: Boolean,
)

private fun collectVisibleNodes(
    node: SignalNode,
    depth: Int,
    path: String,
    expandedStates: Map<String, Boolean>,
    result: MutableList<FlattenedSignalItem>
) {
    node.children.values.sortedBy { it.name }.forEach { child ->
        val currentPath = if (path.isEmpty()) child.name else "$path/${child.name}"
        val isLeaf = child.isLeaf
        val cleanPath = child.fullPath.removePrefix("/")
        val isExpanded = expandedStates[currentPath] ?: false

        result.add(
            FlattenedSignalItem(
                node = child,
                depth = depth,
                currentPath = currentPath,
                cleanPath = cleanPath,
                isLeaf = isLeaf,
                isExpanded = isExpanded,
            )
        )

        if (!isLeaf && isExpanded) {
            collectVisibleNodes(child, depth + 1, currentPath, expandedStates, result)
        }
    }
}

@Composable
fun SignalTreeExplorer(
    rootNode: SignalNode,
    selectedKeys: List<String>,
    onKeySelected: (String) -> Unit,
    onDragStart: (String, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val expandedStates = remember { mutableStateMapOf<String, Boolean>() }

    val visibleNodes = remember(rootNode, expandedStates.toMap()) {
        val list = mutableListOf<FlattenedSignalItem>()
        collectVisibleNodes(rootNode, 0, "", expandedStates, list)
        list
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(
            items = visibleNodes,
            key = { it.currentPath }
        ) { item ->
            var nodeOffset by remember { mutableStateOf(Offset.Zero) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (item.depth * 8).dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (item.isLeaf && selectedKeys.contains(item.cleanPath)) AresCyan.copy(alpha = 0.1f)
                        else Color.Transparent
                    )
                    .onGloballyPositioned { coords ->
                        nodeOffset = coords.positionInWindow()
                    }
                    .pointerInput(item.isLeaf) {
                        if (item.isLeaf) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    onDragStart(item.cleanPath, nodeOffset + offset)
                                },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount)
                                }
                            )
                        }
                    }
                    .clickable {
                        if (item.isLeaf) {
                            onKeySelected(item.cleanPath)
                        } else {
                            expandedStates[item.currentPath] = !item.isExpanded
                        }
                    }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!item.isLeaf) {
                    Icon(
                        imageVector = if (item.isExpanded) Icons.Default.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowRight,
                        contentDescription = null,
                        tint = AresTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = AresCyan.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.width(16.dp))
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        tint = AresAmber.copy(alpha = 0.8f),
                        modifier = Modifier.size(12.dp)
                    )
                }

                Text(
                    text = item.node.name,
                    color = if (item.isLeaf && selectedKeys.contains(item.cleanPath)) AresCyan else AresTextPrimary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (item.isLeaf) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add",
                        tint = AresTextTertiary,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onKeySelected(item.cleanPath) }
                    )
                }
            }
        }
    }
}
