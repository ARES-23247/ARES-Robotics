package com.ares.analytics.ui.screens.fieldeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import com.ares.analytics.ui.components.forms.AresTextField
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.field.FieldMeasurementUnit

/**
 * UI row editor component for field obstacles (rectangles, circles, custom polygons).
 *
 * Configures obstacle dimensions ($width, height, radius$) in meters ($m$), center coordinates $(x, y)$, and rotation angles ($\theta$).
 *
 * ### Physical Units:
 * - Position $(x, y)$ & Dimensions $(w, h, r)$: Meters ($m$)
 * - Rotation ($\theta$): Degrees ($^\circ$)
 *
 * @param index Zero-based list index of this obstacle.
 * @param obs Current [Obstacle] data model.
 * @param fieldWidthM Field width boundary in meters ($m$).
 * @param fieldHeightM Field height boundary in meters ($m$).
 * @param league Active robotics league ([League.FTC] / [League.FRC]).
 * @param onUpdate Callback triggered when obstacle properties change.
 * @param onDelete Callback triggered when obstacle is removed.
 * @param onAdd Callback triggered to add a new obstacle.
 * @param onMirrorX Callback to mirror obstacle horizontally.
 * @param onMirrorY Callback to mirror obstacle vertically.
 *
 * @see com.ares.analytics.shared.Obstacle
 */
@Composable
fun ObstacleRow(
    index: Int,
    obs: Obstacle,
    fieldWidthM: Double,
    fieldHeightM: Double,
    league: League,
    measurementUnit: FieldMeasurementUnit = FieldMeasurementUnit.METERS,
    onUpdate: (Int, Obstacle) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: (Obstacle) -> Unit,
    onMirrorX: (Obstacle, Double, League) -> Obstacle,
    onMirrorY: (Obstacle, Double, League) -> Obstacle
) {
    fun display(meters: Double): String = measurementUnit.fromMeters(meters).toString()
    fun meters(value: String): Double? = value.toDoubleOrNull()?.let(measurementUnit::toMeters)
    val unitLabel = measurementUnit.abbreviation
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AresSurfaceElevated)
            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AresTextField(
                value = obs.name,
                onValueChange = { newName ->
                    val updated = when (obs) {
                        is Obstacle.Circle -> obs.copy(name = newName)
                        is Obstacle.Rectangle -> obs.copy(name = newName)
                        is Obstacle.Polygon -> obs.copy(name = newName)
                    }
                    onUpdate(index, updated)
                },
                label = "Label",
                labelFontSize = 9.sp,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
            )
            Spacer(modifier = Modifier.height(2.dp))
            val details = when (obs) {
                is Obstacle.Circle -> "Circle | r=${String.format("%.2f%s", measurementUnit.fromMeters(obs.radius), unitLabel)}"
                is Obstacle.Rectangle -> "Rect | ${String.format("%.2f%s", measurementUnit.fromMeters(obs.width), unitLabel)}x${String.format("%.2f%s", measurementUnit.fromMeters(obs.height), unitLabel)} @ ${String.format("%.0f°", obs.rotation)}"
                is Obstacle.Polygon -> "Poly | ${obs.vertices.size} vertices"
            }
            Text(details, fontSize = 10.sp, color = AresTextSecondary)
            Spacer(modifier = Modifier.height(4.dp))

            // Color picker dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text("Color:", fontSize = 10.sp, color = AresTextSecondary)
                listOf(
                    "#E53935", // Red
                    "#FB8C00", // Orange
                    "#FDD835", // Yellow
                    "#43A047", // Green
                    "#1E88E5", // Blue
                    "#00ACC1", // Cyan
                    "#8E24AA", // Purple
                    "#E91E63"  // Pink
                ).forEach { colorHex ->
                    val color = try {
                        val clean = colorHex.removePrefix("#")
                        Color(0xFF000000 or clean.toLong(16))
                    } catch (e: Exception) {
                        AresRed
                    }
                    val isSelected = obs.colorHex.equals(colorHex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (isSelected) 1.5.dp else 0.dp,
                                color = if (isSelected) Color.White else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable {
                                val updated = when (obs) {
                                    is Obstacle.Circle -> obs.copy(colorHex = colorHex)
                                    is Obstacle.Rectangle -> obs.copy(colorHex = colorHex)
                                    is Obstacle.Polygon -> obs.copy(colorHex = colorHex)
                                }
                                onUpdate(index, updated)
                            }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            when (obs) {
                is Obstacle.Circle -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        var cxText by remember(obs.id, obs.centerX, measurementUnit) { mutableStateOf(display(obs.centerX)) }
                        AresTextField(
                            value = cxText,
                            onValueChange = { newVal ->
                                cxText = newVal
                                meters(newVal)?.let { parsed -> onUpdate(index, obs.copy(centerX = parsed)) }
                            },
                            label = "X ($unitLabel)",
                            labelFontSize = 9.sp,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
                        )
                        var cyText by remember(obs.id, obs.centerY, measurementUnit) { mutableStateOf(display(obs.centerY)) }
                        AresTextField(
                            value = cyText,
                            onValueChange = { newVal ->
                                cyText = newVal
                                meters(newVal)?.let { parsed -> onUpdate(index, obs.copy(centerY = parsed)) }
                            },
                            label = "Y ($unitLabel)",
                            labelFontSize = 9.sp,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    var radiusText by remember(obs.id, obs.radius, measurementUnit) { mutableStateOf(display(obs.radius)) }
                    AresTextField(
                        value = radiusText,
                        onValueChange = { newVal ->
                            radiusText = newVal
                            meters(newVal)?.let { parsed -> onUpdate(index, obs.copy(radius = parsed)) }
                        },
                        label = "Radius ($unitLabel)",
                        labelFontSize = 9.sp,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
                    )
                }
                is Obstacle.Rectangle -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        var cxText by remember(obs.id, obs.centerX, measurementUnit) { mutableStateOf(display(obs.centerX)) }
                        AresTextField(
                            value = cxText,
                            onValueChange = { newVal ->
                                cxText = newVal
                                meters(newVal)?.let { parsed -> onUpdate(index, obs.copy(centerX = parsed)) }
                            },
                            label = "X ($unitLabel)",
                            labelFontSize = 9.sp,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
                        )
                        var cyText by remember(obs.id, obs.centerY, measurementUnit) { mutableStateOf(display(obs.centerY)) }
                        AresTextField(
                            value = cyText,
                            onValueChange = { newVal ->
                                cyText = newVal
                                meters(newVal)?.let { parsed -> onUpdate(index, obs.copy(centerY = parsed)) }
                            },
                            label = "Y ($unitLabel)",
                            labelFontSize = 9.sp,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    var rectWText by remember(obs.id, obs.width, measurementUnit) { mutableStateOf(display(obs.width)) }
                    var rectHText by remember(obs.id, obs.height, measurementUnit) { mutableStateOf(display(obs.height)) }
                    var rotationText by remember(obs.id, obs.rotation) { mutableStateOf(obs.rotation.toString()) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AresTextField(
                            value = rectWText,
                            onValueChange = { newVal ->
                                rectWText = newVal
                                meters(newVal)?.let { parsed -> onUpdate(index, obs.copy(width = parsed)) }
                            },
                            label = "W ($unitLabel)",
                            labelFontSize = 9.sp,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
                        )
                        AresTextField(
                            value = rectHText,
                            onValueChange = { newVal ->
                                rectHText = newVal
                                meters(newVal)?.let { parsed -> onUpdate(index, obs.copy(height = parsed)) }
                            },
                            label = "H ($unitLabel)",
                            labelFontSize = 9.sp,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
                        )
                        AresTextField(
                            value = rotationText,
                            onValueChange = { newVal ->
                                rotationText = newVal
                                newVal.toDoubleOrNull()?.let { parsed -> onUpdate(index, obs.copy(rotation = parsed)) }
                            },
                            label = "Rot (deg)",
                            labelFontSize = 9.sp,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
                        )
                    }
                }
                is Obstacle.Polygon -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Vertices:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = AresTextSecondary)
                    Spacer(modifier = Modifier.height(2.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        obs.vertices.forEachIndexed { vIdx, vertex ->
                            key(vIdx) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "#${vIdx + 1}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AresTextTertiary,
                                        modifier = Modifier.width(20.dp)
                                    )
                                    var vxText by remember(obs.id, vIdx, vertex.x, measurementUnit) { mutableStateOf(display(vertex.x)) }
                                    AresTextField(
                                        value = vxText,
                                        onValueChange = { newVal ->
                                            vxText = newVal
                                            meters(newVal)?.let { parsed ->
                                                val updatedVertices = obs.vertices.toMutableList()
                                                updatedVertices[vIdx] = PathPoint(parsed, vertex.y)
                                                onUpdate(index, obs.copy(vertices = updatedVertices))
                                            }
                                        },
                                        label = "X ($unitLabel)",
                                        labelFontSize = 8.sp,
                                        modifier = Modifier.weight(1f),
                                        textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
                                    )
                                    var vyText by remember(obs.id, vIdx, vertex.y, measurementUnit) { mutableStateOf(display(vertex.y)) }
                                    AresTextField(
                                        value = vyText,
                                        onValueChange = { newVal ->
                                            vyText = newVal
                                            meters(newVal)?.let { parsed ->
                                                val updatedVertices = obs.vertices.toMutableList()
                                                updatedVertices[vIdx] = PathPoint(vertex.x, parsed)
                                                onUpdate(index, obs.copy(vertices = updatedVertices))
                                            }
                                        },
                                        label = "Y ($unitLabel)",
                                        labelFontSize = 8.sp,
                                        modifier = Modifier.weight(1f),
                                        textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
                                    )

                                    IconButton(
                                        onClick = {
                                            val updatedVertices = obs.vertices.toMutableList()
                                            updatedVertices.removeAt(vIdx)
                                            onUpdate(index, obs.copy(vertices = updatedVertices))
                                        },
                                        enabled = obs.vertices.size > 3,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Delete Vertex",
                                            tint = if (obs.vertices.size > 3) AresError else AresTextTertiary.copy(alpha = 0.5f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }

                        TextButton(
                            onClick = {
                                val updatedVertices = obs.vertices.toMutableList()
                                val last = obs.vertices.lastOrNull() ?: PathPoint(0.0, 0.0)
                                updatedVertices.add(PathPoint(last.x + 0.2, last.y + 0.2))
                                onUpdate(index, obs.copy(vertices = updatedVertices))
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            modifier = Modifier.align(Alignment.Start)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(12.dp), tint = AresCyan)
                            Spacer(Modifier.width(2.dp))
                            Text("Add Vertex", fontSize = 10.sp, color = AresCyan)
                        }
                    }
                }
            }
        }
        var flipMenuExpanded by remember { mutableStateOf(false) }
        Box {
            IconButton(
                onClick = { flipMenuExpanded = true },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.Flip, contentDescription = "Flip Menu", tint = AresCyan, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(
                expanded = flipMenuExpanded,
                onDismissRequest = { flipMenuExpanded = false },
                modifier = Modifier.background(AresSurfaceElevated)
            ) {
                DropdownMenuItem(
                    text = { Text("Mirror Horizontally", color = AresTextPrimary) },
                    onClick = {
                        flipMenuExpanded = false
                        val mirrored = onMirrorX(obs, fieldWidthM, league)
                        onUpdate(index, mirrored)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Mirror Vertically", color = AresTextPrimary) },
                    onClick = {
                        flipMenuExpanded = false
                        val mirrored = onMirrorY(obs, fieldHeightM, league)
                        onUpdate(index, mirrored)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Duplicate & Mirror X", color = AresTextPrimary) },
                    onClick = {
                        flipMenuExpanded = false
                        val mirrored = onMirrorX(obs, fieldWidthM, league)
                        val copy = when (mirrored) {
                            is Obstacle.Circle -> mirrored.copy(id = "circle_${System.currentTimeMillis()}", name = "${obs.name} Mirrored X")
                            is Obstacle.Rectangle -> mirrored.copy(id = "rect_${System.currentTimeMillis()}", name = "${obs.name} Mirrored X")
                            is Obstacle.Polygon -> mirrored.copy(id = "poly_${System.currentTimeMillis()}", name = "${obs.name} Mirrored X")
                        }
                        onAdd(copy)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Duplicate & Mirror Y", color = AresTextPrimary) },
                    onClick = {
                        flipMenuExpanded = false
                        val mirrored = onMirrorY(obs, fieldHeightM, league)
                        val copy = when (mirrored) {
                            is Obstacle.Circle -> mirrored.copy(id = "circle_${System.currentTimeMillis()}", name = "${obs.name} Mirrored Y")
                            is Obstacle.Rectangle -> mirrored.copy(id = "rect_${System.currentTimeMillis()}", name = "${obs.name} Mirrored Y")
                            is Obstacle.Polygon -> mirrored.copy(id = "poly_${System.currentTimeMillis()}", name = "${obs.name} Mirrored Y")
                        }
                        onAdd(copy)
                    }
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                onClick = {
                    val updated = when (obs) {
                        is Obstacle.Circle -> obs.copy(locked = !obs.locked)
                        is Obstacle.Rectangle -> obs.copy(locked = !obs.locked)
                        is Obstacle.Polygon -> obs.copy(locked = !obs.locked)
                    }
                    onUpdate(index, updated)
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(if (obs.locked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = "Lock", tint = if (obs.locked) AresCyan else AresTextTertiary, modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = { onDelete(index) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AresError, modifier = Modifier.size(16.dp))
            }
        }
    }
}
