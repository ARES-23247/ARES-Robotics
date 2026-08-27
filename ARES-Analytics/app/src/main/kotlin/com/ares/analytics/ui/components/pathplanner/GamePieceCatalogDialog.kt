package com.ares.analytics.ui.components.pathplanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.GamePieceType
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary

/**
 * Dialog allowing team members and drive teams to define, inspect, and customize Game Piece types
 * with visual geometry and Dyn4j rigid body physics properties for future seasons.
 */
@Composable
fun GamePieceCatalogDialog(
    gamePieceTypes: List<GamePieceType>,
    onTypesChanged: (List<GamePieceType>) -> Unit,
    onDismiss: () -> Unit,
) {
    var editingType by remember { mutableStateOf<GamePieceType?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Category, contentDescription = null, tint = AresCyan, modifier = Modifier.size(22.dp))
                    Text(
                        text = "Game Piece Type Catalog",
                        color = AresTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = AresTextSecondary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Define the pieces team members can place on this field. Dimensions and physics are saved with the workspace and used by the simulator.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                )

                if (editingType == null && !isCreatingNew) {
                    // List of registered game piece types
                    gamePieceTypes.forEach { type ->
                        val parsedColor = remember(type.colorHex) {
                            val clean = type.colorHex.removePrefix("#").trim()
                            val fullHex = if (clean.length == 6) "FF$clean" else clean
                            val intVal = fullHex.toLongOrNull(16) ?: 0xFFFFD700
                            Color(intVal)
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AresSurface,
                            border = BorderStroke(1.dp, AresBorder),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .background(parsedColor, CircleShape)
                                            .border(1.dp, AresBorder, CircleShape),
                                    )
                                    Column {
                                        Text(type.name, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text(
                                            type.catalogSummary(),
                                            color = AresTextSecondary,
                                            fontSize = 11.sp,
                                        )
                                        Text(
                                            "Stable ID: ${type.id}",
                                            color = AresTextSecondary,
                                            fontSize = 10.sp,
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = { editingType = type },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = AresCyan, modifier = Modifier.size(16.dp))
                                    }
                                    if (gamePieceTypes.size > 1) {
                                        IconButton(
                                            onClick = { onTypesChanged(gamePieceTypes.filter { it.id != type.id }) },
                                            modifier = Modifier.size(24.dp),
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AresError, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val nextId = nextGamePieceTypeId(gamePieceTypes)
                            editingType = GamePieceType(
                                id = nextId,
                                name = "New Game Piece",
                                shape = "circle",
                                diameter = 0.15,
                                width = 0.15,
                                height = 0.15,
                                colorHex = "#00E5FF",
                                massKg = 0.20,
                                friction = 0.6,
                                restitution = 0.3,
                            )
                            isCreatingNew = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("+ Add Game Piece Type", fontSize = 12.sp)
                    }
                } else {
                    // Editor Form for GamePieceType
                    val current = editingType ?: return@Column
                    var name by remember(current.id) { mutableStateOf(current.name) }
                    var shape by remember(current.id) { mutableStateOf(current.shape) }
                    var diameter by remember(current.id) { mutableStateOf(current.diameter.toString()) }
                    var colorHex by remember(current.id) { mutableStateOf(current.colorHex) }
                    var massKg by remember(current.id) { mutableStateOf(current.massKg.toString()) }
                    var friction by remember(current.id) { mutableStateOf(current.friction.toString()) }
                    var restitution by remember(current.id) { mutableStateOf(current.restitution.toString()) }
                    val normalizedColor = colorHex.trim().let { if (it.startsWith("#")) it else "#$it" }
                    val diameterValue = diameter.toDoubleOrNull()
                    val massValue = massKg.toDoubleOrNull()
                    val frictionValue = friction.toDoubleOrNull()
                    val restitutionValue = restitution.toDoubleOrNull()
                    val nameError = when {
                        name.isBlank() -> "Enter a name students will recognize."
                        gamePieceTypes.any { it.id != current.id && it.name.equals(name.trim(), ignoreCase = true) } ->
                            "Another game-piece type already uses this name."
                        else -> null
                    }
                    val diameterError = if (diameterValue == null || !diameterValue.isFinite() || diameterValue <= 0.0) {
                        "Enter a positive size in metres."
                    } else {
                        null
                    }
                    val massError = if (massValue == null || !massValue.isFinite() || massValue <= 0.0) {
                        "Enter a positive mass in kilograms."
                    } else {
                        null
                    }
                    val frictionError = unitIntervalError(frictionValue, "Friction")
                    val restitutionError = unitIntervalError(restitutionValue, "Restitution")
                    val colorError = if (!normalizedColor.matches(Regex("#[0-9A-Fa-f]{6}"))) {
                        "Use a six-digit color such as #00E5FF."
                    } else {
                        null
                    }
                    val canSave = listOf(
                        nameError,
                        diameterError,
                        massError,
                        frictionError,
                        restitutionError,
                        colorError,
                    ).all { it == null }

                    Surface(
                        color = AresSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, AresBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Stable workspace ID", color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(current.id, color = AresTextSecondary, fontSize = 11.sp)
                            Text(
                                "ARES keeps this ID unchanged so placed pieces and saved simulations remain connected when you rename the display name.",
                                color = AresTextSecondary,
                                fontSize = 10.sp,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Display name") },
                        supportingText = { nameError?.let { Text(it) } },
                        isError = nameError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    var shapeDropdownExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = shape,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Collision Shape") },
                            modifier = Modifier.fillMaxWidth().clickable { shapeDropdownExpanded = true },
                        )
                        DropdownMenu(
                            expanded = shapeDropdownExpanded,
                            onDismissRequest = { shapeDropdownExpanded = false },
                        ) {
                            listOf("circle", "box", "sphere", "cylinder").forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.replaceFirstChar(Char::uppercase)) },
                                    onClick = {
                                        shape = s
                                        shapeDropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = diameter,
                            onValueChange = { diameter = it },
                            label = { Text(if (shape == "box") "Size (m)" else "Diameter (m)") },
                            supportingText = { diameterError?.let { Text(it) } },
                            isError = diameterError != null,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = colorHex,
                            onValueChange = { colorHex = it },
                            label = { Text("Color (#RRGGBB)") },
                            supportingText = { colorError?.let { Text(it) } },
                            isError = colorError != null,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = massKg,
                            onValueChange = { massKg = it },
                            label = { Text("Mass (kg)") },
                            supportingText = { massError?.let { Text(it) } },
                            isError = massError != null,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = friction,
                            onValueChange = { friction = it },
                            label = { Text("Friction (0–1)") },
                            supportingText = { frictionError?.let { Text(it) } },
                            isError = frictionError != null,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = restitution,
                            onValueChange = { restitution = it },
                            label = { Text("Bounce (0–1)") },
                            supportingText = { restitutionError?.let { Text(it) } },
                            isError = restitutionError != null,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                editingType = null
                                isCreatingNew = false
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = {
                                val updated = current.copy(
                                    name = name.trim(),
                                    shape = shape,
                                    diameter = requireNotNull(diameterValue),
                                    width = requireNotNull(diameterValue),
                                    height = requireNotNull(diameterValue),
                                    colorHex = normalizedColor.uppercase(),
                                    massKg = requireNotNull(massValue),
                                    friction = requireNotNull(frictionValue),
                                    restitution = requireNotNull(restitutionValue),
                                )
                                if (isCreatingNew) {
                                    onTypesChanged(gamePieceTypes + updated)
                                } else {
                                    onTypesChanged(gamePieceTypes.map { if (it.id == updated.id) updated else it })
                                }
                                editingType = null
                                isCreatingNew = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AresCyan,
                                contentColor = AresOnAccent,
                            ),
                            enabled = canSave,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Save Type", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (editingType == null && !isCreatingNew) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AresCyan,
                        contentColor = AresOnAccent,
                    ),
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }
        },
        containerColor = AresBackground,
        shape = RoundedCornerShape(12.dp),
    )
}

private fun nextGamePieceTypeId(existing: List<GamePieceType>): String {
    val used = existing.mapTo(mutableSetOf()) { it.id }
    var index = 1
    while ("custom-piece-$index" in used) index += 1
    return "custom-piece-$index"
}

private fun unitIntervalError(value: Double?, label: String): String? = when {
    value == null || !value.isFinite() -> "$label must be a number from 0 to 1."
    value !in 0.0..1.0 -> "$label must stay between 0 and 1."
    else -> null
}

private fun GamePieceType.catalogSummary(): String = when (shape) {
    "box" -> "Box · ${width} × ${height} m · ${massKg} kg"
    "sphere" -> "Sphere · Ø ${diameter} m · ${massKg} kg"
    "cylinder" -> "Cylinder · Ø ${diameter} × ${height} m · ${massKg} kg"
    else -> "Circle · Ø ${diameter} m · ${massKg} kg"
}
