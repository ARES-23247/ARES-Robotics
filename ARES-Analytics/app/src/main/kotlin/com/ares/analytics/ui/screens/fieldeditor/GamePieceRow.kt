
package com.ares.analytics.ui.screens.fieldeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.GamePieceType
import com.ares.analytics.ui.components.forms.AresTextField
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.field.FieldMeasurementUnit

/**
 * UI row component for editing game piece initial field locations $(x, y, z)$ in meters ($m$).
 *
 * Configures game piece type variants (sample/specimen/note/cone/cube), initial field coordinates, and lock status.
 *
 * @param index Zero-based list index of this game piece.
 * @param gp Current [GamePiece] data record.
 * @param gamePieceTypes Canonical workspace catalog used by simulator physics and rendering.
 * @param onUpdate Callback for updating game piece properties.
 * @param onDelete Callback for removing this game piece.
 *
 * @see com.ares.analytics.shared.GamePiece
 */
@Composable
fun GamePieceRow(
    index: Int,
    gp: GamePiece,
    gamePieceTypes: List<GamePieceType>,
    measurementUnit: FieldMeasurementUnit = FieldMeasurementUnit.METERS,
    onUpdate: (Int, GamePiece) -> Unit,
    onDelete: (Int) -> Unit
) {
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
                value = gp.name,
                onValueChange = { newName -> onUpdate(index, gp.copy(name = newName)) },
                label = "Label",
                labelFontSize = 9.sp,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
            )
            Spacer(modifier = Modifier.height(4.dp))
            var expanded by remember { mutableStateOf(false) }
            Box {
                Text(
                    text = "Type: ${gp.type} ▾",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = AresCyan,
                    modifier = Modifier.clickable { expanded = true }
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    gamePieceTypes.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.name, fontSize = 12.sp) },
                            onClick = {
                                expanded = false
                                onUpdate(index, gp.copy(type = type.name, typeId = type.id))
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                var gpXText by remember(gp.id, gp.x, measurementUnit) { mutableStateOf(measurementUnit.fromMeters(gp.x).toString()) }
                AresTextField(
                    value = gpXText,
                    onValueChange = { newVal ->
                        gpXText = newVal
                        newVal.toDoubleOrNull()?.let { parsed -> onUpdate(index, gp.copy(x = measurementUnit.toMeters(parsed))) }
                    },
                    label = "X ($unitLabel)",
                    labelFontSize = 9.sp,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
                )
                var gpYText by remember(gp.id, gp.y, measurementUnit) { mutableStateOf(measurementUnit.fromMeters(gp.y).toString()) }
                AresTextField(
                    value = gpYText,
                    onValueChange = { newVal ->
                        gpYText = newVal
                        newVal.toDoubleOrNull()?.let { parsed -> onUpdate(index, gp.copy(y = measurementUnit.toMeters(parsed))) }
                    },
                    label = "Y ($unitLabel)",
                    labelFontSize = 9.sp,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = AresTextPrimary)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                onClick = { onUpdate(index, gp.copy(locked = !gp.locked)) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(if (gp.locked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = "Lock", tint = if (gp.locked) AresCyan else AresTextTertiary, modifier = Modifier.size(16.dp))
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
