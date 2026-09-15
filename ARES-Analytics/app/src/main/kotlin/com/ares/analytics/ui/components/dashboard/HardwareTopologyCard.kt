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
import com.areslib.telemetry.schema.TopologyNode
import com.areslib.telemetry.schema.TopologyNodeType
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.components.core.CardHeader
import com.ares.analytics.ui.theme.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    val selection = rememberHardwareTopologySelection(liveTopology, databaseService, sessionId)
    val topology = selection.topology
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(TopologyCategoryFilter.ALL) }
    var copyFeedback by remember { mutableStateOf<Pair<String, Any>?>(null) }
    val readings = rememberTopologyReadings(nt4ClientService, topology?.nodes.orEmpty(),
        enabled = isConnected && !selection.cached && topology != null)

    AresCard(modifier = modifier) {
        CardHeader(
            title = "Hardware Topology",
            icon = Icons.Default.Hub,
            iconTint = AresCyan,
            statusText = when {
                selection.loading -> "Loading..."
                selection.failed -> "Unavailable"
                topology != null -> "${topology.nodes.size} " + if (selection.cached) "Cached Devices" else "Devices"
                selection.cached -> "No cached map"
                isConnected -> "Listening..."
                else -> "Offline"
            },
            statusColor = when {
                selection.failed -> AresError
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
                                copyFeedback = "JSON Copied!" to Any()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Topology JSON", tint = AresTextSecondary, modifier = Modifier.size(14.dp))
                        }
                        IconButton(
                            onClick = {
                                val mdTable = topologyMarkdown(topology)
                                clipboardManager.setText(AnnotatedString(mdTable))
                                copyFeedback = "Markdown Copied!" to Any()
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
                text = copyFeedback?.first ?: "",
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
                        text = when {
                            selection.failed -> "Unable to load saved hardware topology."
                            selection.loading -> "Loading saved hardware topology..."
                            selection.cached -> "No cached hardware topology available for this robot."
                            isConnected -> "Awaiting Topology/HardwareMap broadcast..."
                            else -> "No hardware topology available for this session."
                        },
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (selection.cached) "Saved maps reflect the latest cached hardware for the session robot."
                            else "Hardware maps publish automatically when robot code registers devices.",
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
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
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
                items(TopologyCategoryFilter.entries) { cat ->
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

            val rows = remember(topology, searchQuery, selectedCategory) {
                topologyDisplayRows(topology.nodes, searchQuery, selectedCategory)
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (rows.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("No hardware nodes match current filter.", color = AresTextTertiary, fontSize = 11.sp)
                        }
                    }
                } else {
                    items(rows, key = { it.node.id }) { row ->
                        TopologyNodeRow(row.node, readings[row.node.id], row.depth)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopologyNodeRow(
    node: TopologyNode,
    reading: TopologyMotorReading?,
    depth: Int,
) {
    val isChild = depth > 0
    val currentAmps = reading?.currentAmps
    val velocity = reading?.velocity
    val connType = node.connectionType

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth.coerceIn(0, 8) * 20).dp)
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
                        text = topologyCurrentText(currentAmps),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (currentAmps > 25.0) AresError else AresGreen
                    )
                }
                if (velocity != null) {
                    Text(
                        text = topologyVelocityText(velocity),
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
