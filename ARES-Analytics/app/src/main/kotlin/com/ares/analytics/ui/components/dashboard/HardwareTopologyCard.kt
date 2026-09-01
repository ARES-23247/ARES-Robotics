package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.areslib.telemetry.schema.HardwareTopology
import com.areslib.telemetry.schema.TopologyNode
import com.areslib.telemetry.schema.TopologyNodeType
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.components.core.CardHeader
import com.ares.analytics.ui.theme.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class TopologyCategoryFilter(val displayName: String) {
    ALL("All"),
    CONTROLLERS("Controllers"),
    MOTORS("Motors"),
    SERVOS("Servos"),
    SENSORS("Sensors"),
    VISION("Vision & IMU")
}
private val prettyJson = Json { prettyPrint = true }

@Composable
fun HardwareTopologyCard(
    nt4ClientService: Nt4ClientService,
    databaseService: DatabaseService,
    sessionId: String?,
    modifier: Modifier = Modifier
) {
    // LocalClipboardManager: the non-deprecated LocalClipboard needs ClipEntry.ofPlainText,
    // which this Compose version does not ship. Revisit at the next Compose bump.
    @Suppress("DEPRECATION") val clipboardManager = LocalClipboardManager.current
    val liveTopology by nt4ClientService.latestTopology.collectAsState()
    val isConnected by nt4ClientService.isConnected.collectAsState()

    var historicalTopology by remember { mutableStateOf<HardwareTopology?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(TopologyCategoryFilter.ALL) }
    var copyFeedback by remember { mutableStateOf<String?>(null) }

    // Load from DB if historical session is chosen
    LaunchedEffect(sessionId) {
        if (sessionId != null && sessionId != "live-telemetry") {
            val summary = databaseService.getSessionSummary(sessionId)
            historicalTopology = databaseService.getTopology(summary?.robotId ?: "")
        } else {
            historicalTopology = null
        }
    }

    val topology = if (sessionId != null && sessionId != "live-telemetry") {
        historicalTopology
    } else {
        liveTopology ?: historicalTopology
    }

    AresCard(modifier = modifier) {
        CardHeader(
            title = "Hardware Topology",
            icon = Icons.Default.Hub,
            iconTint = AresCyan,
            statusText = when {
                topology != null -> "${topology.nodes.size} Devices"
                isConnected -> "Listening..."
                else -> "Offline"
            },
            statusColor = when {
                topology != null -> AresGreen
                isConnected -> AresGold
                else -> AresTextTertiary
            },
            trailingContent = {
                if (topology != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val jsonStr = prettyJson.encodeToString(topology)
                                clipboardManager.setText(AnnotatedString(jsonStr))
                                copyFeedback = "JSON Copied!"
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Topology JSON", tint = AresTextSecondary, modifier = Modifier.size(14.dp))
                        }
                        IconButton(
                            onClick = {
                                val mdTable = buildString {
                                    appendLine("# Hardware Map: ${topology.robotId}")
                                    appendLine("| Name | Type | Bus / Port | ID | Connection |")
                                    appendLine("| :--- | :--- | :--- | :--- | :--- |")
                                    topology.nodes.forEach { n ->
                                        val busOrPort = n.canBus ?: n.port?.let { "Port $it" } ?: "—"
                                        val idStr = n.canId?.let { "CAN $it" } ?: "—"
                                        appendLine("| ${n.displayName} | ${n.type.name} | $busOrPort | $idStr | ${n.connectionType ?: "Internal"} |")
                                    }
                                }
                                clipboardManager.setText(AnnotatedString(mdTable))
                                copyFeedback = "Markdown Copied!"
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Description, contentDescription = "Export Markdown Table", tint = AresTextSecondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        )

        Spacer(Modifier.height(8.dp))

        if (copyFeedback != null) {
            LaunchedEffect(copyFeedback) {
                kotlinx.coroutines.delay(2500)
                copyFeedback = null
            }
            Text(
                text = copyFeedback ?: "",
                color = AresCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        if (topology == null || topology.nodes.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        Icons.Default.Hub,
                        contentDescription = null,
                        tint = AresTextTertiary,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = if (isConnected) "Awaiting Topology/HardwareMap broadcast..." else "No hardware topology available for this session.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Hardware maps publish automatically when robot code registers devices.",
                        color = AresTextTertiary,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            // Search and Category Filter Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search node, CAN ID, or port...", fontSize = 11.sp, color = AresTextTertiary) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(42.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AresCyan,
                        unfocusedBorderColor = AresBorder,
                        focusedContainerColor = AresSurface,
                        unfocusedContainerColor = AresSurface
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = AresTextPrimary)
                )
            }

            Spacer(Modifier.height(6.dp))

            // Category Filter Pills
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            ) {
                items(TopologyCategoryFilter.values()) { cat ->
                    val isSelected = selectedCategory == cat
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) AresCyan.copy(alpha = 0.2f) else AresSurface)
                            .border(1.dp, if (isSelected) AresCyan else AresBorder, RoundedCornerShape(6.dp))
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = cat.displayName,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) AresCyan else AresTextSecondary
                        )
                    }
                }
            }

            // Filter nodes
            val filteredNodes = remember(topology, searchQuery, selectedCategory) {
                topology.nodes.filter { node ->
                    val matchesSearch = searchQuery.isBlank() ||
                        node.displayName.contains(searchQuery, ignoreCase = true) ||
                        node.id.contains(searchQuery, ignoreCase = true) ||
                        node.canId?.toString()?.contains(searchQuery) == true ||
                        node.port?.toString()?.contains(searchQuery) == true

                    val matchesCategory = when (selectedCategory) {
                        TopologyCategoryFilter.ALL -> true
                        TopologyCategoryFilter.CONTROLLERS -> node.type in listOf(
                            TopologyNodeType.ROBORIO, TopologyNodeType.CONTROL_HUB,
                            TopologyNodeType.EXPANSION_HUB, TopologyNodeType.CANIVORE,
                            TopologyNodeType.SRS_HUB, TopologyNodeType.POWER_DISTRIBUTION
                        )
                        TopologyCategoryFilter.MOTORS -> node.type in listOf(
                            TopologyNodeType.MOTOR, TopologyNodeType.CAN_MOTOR_CONTROLLER
                        )
                        TopologyCategoryFilter.SERVOS -> node.type == TopologyNodeType.SERVO
                        TopologyCategoryFilter.SENSORS -> node.type in listOf(
                            TopologyNodeType.COLOR_SENSOR, TopologyNodeType.DISTANCE_SENSOR,
                            TopologyNodeType.BEAM_BREAK, TopologyNodeType.ANALOG_SENSOR,
                            TopologyNodeType.CAN_CODER
                        )
                        TopologyCategoryFilter.VISION -> node.type in listOf(
                            TopologyNodeType.CAMERA, TopologyNodeType.ODOMETRY_COMPUTER,
                            TopologyNodeType.IMU, TopologyNodeType.PIGEON_IMU
                        )
                    }

                    matchesSearch && matchesCategory
                }
            }

            // Hierarchy Grouping: Controllers & standalone roots
            val rootControllers = remember(filteredNodes) {
                val controllerTypes = setOf(
                    TopologyNodeType.ROBORIO, TopologyNodeType.CONTROL_HUB,
                    TopologyNodeType.EXPANSION_HUB, TopologyNodeType.CANIVORE,
                    TopologyNodeType.SRS_HUB, TopologyNodeType.POWER_DISTRIBUTION
                )
                filteredNodes.filter { it.type in controllerTypes || it.parentId == null }
            }

            val childNodesByParent = remember(topology.nodes) {
                topology.nodes.filter { it.parentId != null }.groupBy { it.parentId!! }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (filteredNodes.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No hardware nodes match current filter.", color = AresTextTertiary, fontSize = 11.sp)
                        }
                    }
                } else if (searchQuery.isNotBlank() || selectedCategory != TopologyCategoryFilter.ALL) {
                    // Flat display when searching / filtering
                    items(filteredNodes) { node ->
                        TopologyNodeRow(
                            node = node,
                            nt4ClientService = nt4ClientService,
                            isChild = false
                        )
                    }
                } else {
                    // Hierarchical tree display
                    rootControllers.forEach { root ->
                        item(key = root.id) {
                            TopologyNodeRow(
                                node = root,
                                nt4ClientService = nt4ClientService,
                                isChild = false
                            )
                        }

                        val children = childNodesByParent[root.id].orEmpty()
                        items(children, key = { it.id }) { child ->
                            TopologyNodeRow(
                                node = child,
                                nt4ClientService = nt4ClientService,
                                isChild = true
                            )
                        }
                    }

                    // Orphans / unparented non-controller devices if any
                    val orphans = filteredNodes.filter { 
                        it.parentId != null && rootControllers.none { r -> r.id == it.parentId } 
                    }
                    items(orphans, key = { it.id }) { orphan ->
                        TopologyNodeRow(
                            node = orphan,
                            nt4ClientService = nt4ClientService,
                            isChild = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopologyNodeRow(
    node: TopologyNode,
    nt4ClientService: Nt4ClientService,
    isChild: Boolean
) {
    // Check if live telemetry matches this node
    val latestValues = nt4ClientService.latestValues
    val currentAmps = latestValues["Hardware/Motors/${node.displayName}/CurrentAmps"]?.value
        ?: latestValues["Hardware/Motors/${node.id}/CurrentAmps"]?.value
    val velocity = latestValues["Hardware/Motors/${node.displayName}/Velocity"]?.value
        ?: latestValues["Hardware/Motors/${node.id}/Velocity"]?.value
    val connType = node.connectionType

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (isChild) 20.dp else 0.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isChild) AresSurface.copy(alpha = 0.5f) else AresSurface)
            .border(1.dp, if (isChild) AresBorder.copy(alpha = 0.6f) else AresBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isChild) {
            Icon(
                Icons.Default.SubdirectoryArrowRight,
                contentDescription = null,
                tint = AresTextTertiary,
                modifier = Modifier.size(14.dp)
            )
        }

        // Device Icon
        Icon(
            imageVector = getNodeIcon(node.type),
            contentDescription = node.type.name,
            tint = getNodeColor(node.type),
            modifier = Modifier.size(16.dp)
        )

        // Device Name & ID
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = node.displayName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AresTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = node.type.name.replace("_", " "),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = getNodeColor(node.type),
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(getNodeColor(node.type).copy(alpha = 0.12f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                if (node.canId != null) {
                    Text(
                        text = "CAN ID ${node.canId}" + if (node.canBus != null) " (${node.canBus})" else "",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AresCyan
                    )
                } else if (node.port != null) {
                    Text(
                        text = "Port ${node.port}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AresGold
                    )
                }

                if (connType != null) {
                    Text(
                        text = connType,
                        fontSize = 9.sp,
                        color = AresTextTertiary
                    )
                }
            }
        }

        // Live Telemetry Value if active
        if (currentAmps != null || velocity != null) {
            Column(horizontalAlignment = Alignment.End) {
                if (currentAmps != null) {
                    Text(
                        text = String.format("%.2f A", currentAmps),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (currentAmps > 25.0) AresError else AresGreen
                    )
                }
                if (velocity != null) {
                    Text(
                        text = String.format("%.1f rad/s", velocity),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AresTextSecondary
                    )
                }
            }
        }
    }
}

private fun getNodeIcon(type: TopologyNodeType): ImageVector = when (type) {
    TopologyNodeType.ROBORIO, TopologyNodeType.CONTROL_HUB, TopologyNodeType.EXPANSION_HUB,
    TopologyNodeType.CANIVORE, TopologyNodeType.SRS_HUB -> Icons.Default.Memory
    TopologyNodeType.POWER_DISTRIBUTION -> Icons.Default.Power
    TopologyNodeType.MOTOR, TopologyNodeType.CAN_MOTOR_CONTROLLER -> Icons.Default.ElectricBolt
    TopologyNodeType.SERVO -> Icons.Default.Settings
    TopologyNodeType.CAMERA -> Icons.Default.Videocam
    TopologyNodeType.IMU, TopologyNodeType.PIGEON_IMU -> Icons.Default.CompassCalibration
    TopologyNodeType.ODOMETRY_COMPUTER -> Icons.Default.Route
    TopologyNodeType.COLOR_SENSOR, TopologyNodeType.DISTANCE_SENSOR,
    TopologyNodeType.BEAM_BREAK, TopologyNodeType.ANALOG_SENSOR,
    TopologyNodeType.CAN_CODER -> Icons.Default.Sensors
}

private fun getNodeColor(type: TopologyNodeType): Color = when (type) {
    TopologyNodeType.ROBORIO, TopologyNodeType.CONTROL_HUB, TopologyNodeType.EXPANSION_HUB,
    TopologyNodeType.CANIVORE, TopologyNodeType.SRS_HUB -> AresCyan
    TopologyNodeType.POWER_DISTRIBUTION -> AresGold
    TopologyNodeType.MOTOR, TopologyNodeType.CAN_MOTOR_CONTROLLER -> AresGreen
    TopologyNodeType.SERVO -> AresAmber
    TopologyNodeType.CAMERA -> AresCyan
    TopologyNodeType.IMU, TopologyNodeType.PIGEON_IMU -> AresGold
    TopologyNodeType.ODOMETRY_COMPUTER -> AresGreen
    TopologyNodeType.COLOR_SENSOR, TopologyNodeType.DISTANCE_SENSOR,
    TopologyNodeType.BEAM_BREAK, TopologyNodeType.ANALOG_SENSOR,
    TopologyNodeType.CAN_CODER -> AresTextSecondary
}
