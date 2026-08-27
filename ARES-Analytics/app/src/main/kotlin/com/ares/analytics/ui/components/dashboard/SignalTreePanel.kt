package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ── Global Drag & Drop Manager ──────────────────────────────────────────────

object DragDropManager {
    var draggedSignalKey by mutableStateOf<String?>(null)
    var dragOffset by mutableStateOf(Offset.Zero)
}

class SignalNode(
    val name: String,
    val fullPath: String,
    private val initialIsLeaf: Boolean = false,
    val children: MutableMap<String, SignalNode> = mutableMapOf()
) {
    val isLeaf: Boolean
        get() = children.isEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalTreePanel(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val activeTopics = remember { mutableStateListOf<String>() }
    val liveValues = remember { mutableStateMapOf<String, Double>() }
    var searchQuery by remember { mutableStateOf("") }

    // Track expanded folders: fullPath -> Boolean
    val expandedStates = remember { mutableStateMapOf<String, Boolean>() }

    // Periodically update active topics
    LaunchedEffect(Unit) {
        while (true) {
            val topics = nt4ClientService.getActiveTopics()
            // Add any new topics
            topics.forEach { topic ->
                if (!activeTopics.contains(topic)) {
                    activeTopics.add(topic)
                }
            }
            // Remove inactive topics
            activeTopics.removeAll { !topics.contains(it) }
            delay(1000)
        }
    }

    // Subscribe to live telemetry flow to update values
    LaunchedEffect(Unit) {
        nt4ClientService.uiTelemetryFlow.collect { frame ->
            liveValues[frame.key] = frame.value
        }
    }

    // Build the hierarchical tree from active topics and search query
    val rootNode = remember(activeTopics.toList(), searchQuery) {
        val filtered = if (searchQuery.isEmpty()) {
            activeTopics.toList()
        } else {
            activeTopics.filter { it.contains(searchQuery, ignoreCase = true) }
        }
        val root = SignalNode("", "", false)
        for (topic in filtered) {
            val parts = topic.split("/").filter { it.isNotEmpty() }
            var current = root
            var currentPath = ""
            for (i in parts.indices) {
                val part = parts[i]
                currentPath += "/$part"
                val isLeaf = (i == parts.lastIndex)
                current = current.children.getOrPut(part) {
                    SignalNode(part, currentPath, isLeaf)
                }
            }
        }
        root
    }

    // Flatten tree to visible items based on expansion state
    fun getVisibleItems(node: SignalNode, depth: Int): List<Pair<SignalNode, Int>> {
        val items = mutableListOf<Pair<SignalNode, Int>>()

        // Sort children alphabetically
        val sortedChildren = node.children.values.sortedBy { it.name }
        for (child in sortedChildren) {
            items.add(child to depth)
            val isExpanded = expandedStates[child.fullPath] ?: (searchQuery.isNotEmpty())
            if (!child.isLeaf && isExpanded) {
                items.addAll(getVisibleItems(child, depth + 1))
            }
        }
        return items
    }
    val visibleItems = remember(rootNode, expandedStates.toMap(), searchQuery) {
        getVisibleItems(rootNode, 0)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SIGNAL TREE EXPLORER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AresCyan)

        // Search text field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search signals...", color = AresTextTertiary, fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = AresTextSecondary, modifier = Modifier.size(16.dp)) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AresTextPrimary,
                unfocusedTextColor = AresTextPrimary,
                focusedContainerColor = AresBackground,
                unfocusedContainerColor = AresBackground,
                focusedBorderColor = AresCyan,
                unfocusedBorderColor = AresBorder
            ),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

        // Signal Tree View List
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (visibleItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (searchQuery.isEmpty()) "No active telemetry signals." else "No signals match search.",
                        color = AresTextTertiary,
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visibleItems) { (node, depth) ->
                        SignalTreeRow(
                            node = node,
                            depth = depth,
                            isExpanded = expandedStates[node.fullPath] ?: (searchQuery.isNotEmpty()),
                            onToggleExpand = {
                                expandedStates[node.fullPath] = !(expandedStates[node.fullPath] ?: false)
                            },
                            liveValue = liveValues[node.fullPath.removePrefix("/")]
                        )
                    }
                }
            }

            // Drag Shadow Popup Overlay
            DragDropManager.draggedSignalKey?.let { draggedKey ->
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(
                        DragDropManager.dragOffset.x.roundToInt() + 15,
                        DragDropManager.dragOffset.y.roundToInt() + 15
                    )
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(brush = androidx.compose.ui.graphics.SolidColor(AresCyan)),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.DragIndicator, null, tint = AresCyan, modifier = Modifier.size(14.dp))
                            Text(draggedKey.substringAfterLast("/"), color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SignalTreeRow(
    node: SignalNode,
    depth: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    liveValue: Double?
) {
    // Determine Signal Type and Icon
    val (icon, iconColor) = remember(node.fullPath, node.isLeaf) {
        if (!node.isLeaf) {
            Icons.Default.Folder to AresTextSecondary
        } else {
            val pathLower = node.fullPath.lowercase()
            when {
                pathLower.contains("pose") || pathLower.contains("translation") || pathLower.contains("rotation") -> {
                    Icons.Default.Hub to AresGold
                }
                pathLower.contains("enabled") || pathLower.contains("active") || pathLower.contains("button") -> {
                    Icons.Default.ToggleOn to AresGreen
                }
                else -> {
                    Icons.Default.Pin to AresCyan
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable { if (!node.isLeaf) onToggleExpand() }
            .pointerInput(node.fullPath) {
                if (node.isLeaf) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            DragDropManager.draggedSignalKey = node.fullPath
                            DragDropManager.dragOffset = offset
                        },
                        onDragEnd = {
                            DragDropManager.draggedSignalKey = null
                        },
                        onDragCancel = {
                            DragDropManager.draggedSignalKey = null
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            DragDropManager.dragOffset += dragAmount
                        }
                    )
                }
            }
            .padding(vertical = 4.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Expand/Collapse caret icon
        if (!node.isLeaf) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowRight,
                contentDescription = null,
                tint = AresTextTertiary,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Spacer(Modifier.width(16.dp))
        }

        // Signal type icon
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(16.dp)
        )

        Spacer(Modifier.width(6.dp))

        // Signal Name
        Text(
            text = node.name,
            color = AresTextPrimary,
            fontSize = 12.sp,
            fontWeight = if (!node.isLeaf) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (node.isLeaf) FontFamily.Monospace else FontFamily.SansSerif,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Live value badge
        if (node.isLeaf && liveValue != null) {
            Text(
                text = String.format("%.3f", liveValue),
                color = AresCyan,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(AresCyanGlow, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
