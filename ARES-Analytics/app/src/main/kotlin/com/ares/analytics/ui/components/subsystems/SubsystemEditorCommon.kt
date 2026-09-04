package com.ares.analytics.ui.components.subsystems

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.components.core.AresEditorCard
import com.ares.analytics.ui.components.core.AresSelectionField
import com.ares.analytics.ui.components.core.AresSelectionFieldLayout
import com.ares.analytics.ui.components.core.AresDoubleField
import com.ares.analytics.ui.components.core.AresNullableDoubleField
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemHardwareDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.supportsPlatform

fun SubsystemHardwareKind.isActuator(): Boolean =
    this == SubsystemHardwareKind.MOTOR ||
    this == SubsystemHardwareKind.POSITIONAL_SERVO ||
    this == SubsystemHardwareKind.CONTINUOUS_SERVO ||
    this == SubsystemHardwareKind.DIGITAL_OUTPUT ||
    this == SubsystemHardwareKind.PWM_OUTPUT ||
    this == SubsystemHardwareKind.INDICATOR_LIGHT ||
    this == SubsystemHardwareKind.BUZZER ||
    this == SubsystemHardwareKind.PRISM_DRIVER ||
    this == SubsystemHardwareKind.SOLENOID

fun SubsystemHardwareDocument.connectionLabel(platform: SubsystemPlatform): String = when (platform) {
    SubsystemPlatform.FTC -> connection.hardwareMapName?.let { "hwMap: $it" } ?: "unconfigured"
    SubsystemPlatform.XRP -> connection.channel?.let { "XRP channel $it" } ?: "XRP built-in"
    SubsystemPlatform.FRC -> when (kind) {
        SubsystemHardwareKind.MOTOR -> "CAN ${connection.canId ?: 0} (${connection.canBus})"
        SubsystemHardwareKind.SOLENOID -> "${connection.pneumaticsModuleType?.name ?: "pneumatics"} CAN ${connection.canId ?: 0}, channel ${connection.channel ?: 0}"
        SubsystemHardwareKind.QUADRATURE_ENCODER -> "DIO ${connection.channel ?: 0}/${connection.secondaryChannel ?: 1}"
        SubsystemHardwareKind.IMU -> "onboard SPI"
        else -> "channel ${connection.channel ?: 0}"
    }
}

fun supportedHardwareKinds(platform: SubsystemPlatform): List<SubsystemHardwareKind> =
    SubsystemHardwareKind.entries.filter { it.supportsPlatform(platform) }

fun SubsystemControlStrategy.requiresMeasurement(): Boolean =
    this in setOf(
        SubsystemControlStrategy.POSITION_PID,
        SubsystemControlStrategy.PROFILED_POSITION_PID,
        SubsystemControlStrategy.VELOCITY_PID,
        SubsystemControlStrategy.BANG_BANG,
    )

@Composable
fun EditorCard(
    title: String,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AresEditorCard(
        contentPadding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = AresCyan, modifier = Modifier.size(16.dp))
            Text(title, color = AresTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
fun SelectableRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) AresCyan else AresBorder,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick),
        color = if (selected) AresCyan.copy(alpha = 0.10f) else AresSurface,
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(subtitle, color = AresTextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AresTextPrimary, fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun TextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
fun DoubleInput(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
) {
    AresDoubleField(
        label = label,
        value = value,
        modifier = Modifier.fillMaxWidth(),
        labelFontSize = 11.sp,
        onValueChange = onValueChange,
    )
}

@Composable
fun NullableDoubleInput(
    label: String,
    value: Double?,
    onValueChange: (Double?) -> Unit,
) {
    AresNullableDoubleField(
        label = label,
        value = value,
        modifier = Modifier.fillMaxWidth(),
        labelFontSize = 11.sp,
        onValueChange = onValueChange,
    )
}

@Composable
fun IntInput(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label, fontSize = 11.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
fun LongInput(
    label: String,
    value: Long,
    onValueChange: (Long) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toLongOrNull()?.let(onValueChange)
        },
        label = { Text(label, fontSize = 11.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
fun NullableLongInput(
    label: String,
    value: Long?,
    onValueChange: (Long?) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (it.isBlank()) onValueChange(null) else it.toLongOrNull()?.let(onValueChange)
        },
        label = { Text(label, fontSize = 11.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
fun FieldGuidance(text: String) {
    Text(text, color = AresTextTertiary, fontSize = 10.sp, lineHeight = 14.sp)
}

@Composable
fun <T : Enum<T>> EnumSelector(
    label: String,
    selected: T,
    entries: List<T>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$label: ${selected.name.replace('_', ' ').lowercase()}", fontSize = 11.sp, color = AresTextPrimary)
                Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp), tint = AresTextSecondary)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.name.replace('_', ' ').lowercase(), fontSize = 11.sp) },
                    onClick = {
                        expanded = false
                        onSelect(item)
                    }
                )
            }
        }
    }
}

@Composable
fun DropdownSelector(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    AresSelectionField(
        label = label,
        selected = selected,
        choices = options.map { it to it },
        layout = AresSelectionFieldLayout.INLINE,
        shape = RoundedCornerShape(6.dp),
        onSelect = onSelect,
    )
}

@Composable
fun StableIdLabel(
    title: String,
    id: String,
    description: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = AresTextSecondary, fontSize = 11.sp)
            Text(id, color = AresCyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(description, color = AresTextTertiary, fontSize = 10.sp)
    }
}

fun SubsystemHardwareKind.uiLabel(): String = when (this) {
    SubsystemHardwareKind.MOTOR -> "Motor (DC / Brushless)"
    SubsystemHardwareKind.POSITIONAL_SERVO -> "Positional Servo (PWM 0-1)"
    SubsystemHardwareKind.CONTINUOUS_SERVO -> "Continuous Servo (CR)"
    SubsystemHardwareKind.DIGITAL_INPUT -> "Digital Input / Limit Switch"
    SubsystemHardwareKind.DIGITAL_OUTPUT -> "Digital GPIO Output"
    SubsystemHardwareKind.ANALOG_INPUT -> "Analog Sensor / Potentiometer"
    SubsystemHardwareKind.PWM_OUTPUT -> "PWM GPIO Output"
    SubsystemHardwareKind.COLOR_SENSOR -> "Color / Proximity Sensor (I2C)"
    SubsystemHardwareKind.ABSOLUTE_ENCODER -> "Absolute Encoder (angle)"
    SubsystemHardwareKind.QUADRATURE_ENCODER -> "Quadrature Encoder (position + speed)"
    SubsystemHardwareKind.DISTANCE_SENSOR -> "Distance Sensor"
    SubsystemHardwareKind.IMU -> "IMU / Gyroscope"
    SubsystemHardwareKind.SOLENOID -> "Pneumatic Solenoid (FRC)"
    SubsystemHardwareKind.INDICATOR_LIGHT -> "RGB Indicator Light (Servo PWM)"
    SubsystemHardwareKind.BUZZER -> "Built-in Buzzer"
    SubsystemHardwareKind.PRISM_DRIVER -> "goBILDA Prism LED Driver (PWM pulse width)"
}

@Composable
fun AddHardwareButton(
    viewModel: SubsystemGeneratorViewModel,
    platform: SubsystemPlatform,
    label: String = "+ Add hardware",
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 11.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            supportedHardwareKinds(platform).forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind.uiLabel(), fontSize = 11.sp) },
                    onClick = {
                        expanded = false
                        viewModel.addHardware(kind)
                    }
                )
            }
        }
    }
}

@Composable
fun ConceptCard(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AresSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(body, color = AresTextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}
