package com.ares.analytics.ui.components.linkage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.ares.analytics.ui.components.core.AresDoubleField
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.areslib.math.kinematics.TwoDofLinkageKinematics
import com.areslib.math.kinematics.TwoDofLinkageParameters
import com.areslib.math.kinematics.TwoDofLinkagePlant
import com.areslib.math.kinematics.TwoDofLinkagePlantParameters
import com.areslib.subsystem.SubsystemLinkageDocument
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Editable geometry, joint binding, gravity, and deterministic dynamics lab for a serial 2-DOF arm. */
@Composable
fun LinkageEditorCanvas(
    linkage: SubsystemLinkageDocument,
    actuatorIds: List<String>,
    angleMeasurementFieldIds: List<String>,
    onLinkageChanged: (SubsystemLinkageDocument) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PrecisionManufacturing, null, tint = AresCyan, modifier = Modifier.size(20.dp))
                    Column {
                        Text("2-joint linkage", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("Geometry, actuator bindings, gravity feedforward, and mock physics", color = AresTextSecondary, fontSize = 11.sp)
                    }
                }
                Switch(
                    checked = linkage.enabled,
                    onCheckedChange = { enabled ->
                        onLinkageChanged(
                            linkage.copy(
                                enabled = enabled,
                                joint1ActuatorId = linkage.joint1ActuatorId ?: actuatorIds.getOrNull(0),
                                joint2ActuatorId = linkage.joint2ActuatorId ?: actuatorIds.firstOrNull { it != actuatorIds.getOrNull(0) },
                                joint1AngleFieldId = linkage.joint1AngleFieldId ?: angleMeasurementFieldIds.getOrNull(0),
                                joint2AngleFieldId = linkage.joint2AngleFieldId ?: angleMeasurementFieldIds.firstOrNull { it != angleMeasurementFieldIds.getOrNull(0) },
                            ),
                        )
                    },
                )
            }
            if (!linkage.enabled) {
                Text(
                    "Enable this only for a serial arm with two independently driven joints. A closed-chain four-bar remains an advanced hand-authored mechanism because its dynamics and constraints are different.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                )
                return@Column
            }

            if (actuatorIds.size < 2 || angleMeasurementFieldIds.size < 2) {
                StatusText(
                    "A 2-joint linkage needs two independent motors and two cached Double angle measurements in radians. Add them in Hardware and State & behavior.",
                    error = true,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IdSelector("Joint 1 motor", linkage.joint1ActuatorId, actuatorIds, Modifier.weight(1f)) {
                    onLinkageChanged(linkage.copy(joint1ActuatorId = it))
                }
                IdSelector("Joint 2 motor", linkage.joint2ActuatorId, actuatorIds.filter { it != linkage.joint1ActuatorId }, Modifier.weight(1f)) {
                    onLinkageChanged(linkage.copy(joint2ActuatorId = it))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IdSelector("Joint 1 angle (rad)", linkage.joint1AngleFieldId, angleMeasurementFieldIds, Modifier.weight(1f)) {
                    onLinkageChanged(linkage.copy(joint1AngleFieldId = it))
                }
                IdSelector("Joint 2 angle (rad)", linkage.joint2AngleFieldId, angleMeasurementFieldIds.filter { it != linkage.joint1AngleFieldId }, Modifier.weight(1f)) {
                    onLinkageChanged(linkage.copy(joint2AngleFieldId = it))
                }
            }

            Text("Physical geometry", color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalEditor("Link 1 length (m)", linkage.link1LengthMeters, Modifier.weight(1f)) { onLinkageChanged(linkage.copy(link1LengthMeters = it)) }
                DecimalEditor("Link 2 length (m)", linkage.link2LengthMeters, Modifier.weight(1f)) { onLinkageChanged(linkage.copy(link2LengthMeters = it)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalEditor("Link 1 mass (kg)", linkage.link1MassKg, Modifier.weight(1f)) { onLinkageChanged(linkage.copy(link1MassKg = it)) }
                DecimalEditor("Link 2 mass (kg)", linkage.link2MassKg, Modifier.weight(1f)) { onLinkageChanged(linkage.copy(link2MassKg = it)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalEditor("Link 1 center of mass (m)", linkage.link1CenterOfMassMeters, Modifier.weight(1f)) { onLinkageChanged(linkage.copy(link1CenterOfMassMeters = it)) }
                DecimalEditor("Link 2 center of mass (m)", linkage.link2CenterOfMassMeters, Modifier.weight(1f)) { onLinkageChanged(linkage.copy(link2CenterOfMassMeters = it)) }
            }

            Text("Joint limits and simulation constants", color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalEditor("Joint 1 minimum (deg)", linkage.joint1MinRad * 180.0 / PI, Modifier.weight(1f)) { onLinkageChanged(linkage.copy(joint1MinRad = it * PI / 180.0)) }
                DecimalEditor("Joint 1 maximum (deg)", linkage.joint1MaxRad * 180.0 / PI, Modifier.weight(1f)) { onLinkageChanged(linkage.copy(joint1MaxRad = it * PI / 180.0)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalEditor("Joint 2 minimum (deg)", linkage.joint2MinRad * 180.0 / PI, Modifier.weight(1f)) { onLinkageChanged(linkage.copy(joint2MinRad = it * PI / 180.0)) }
                DecimalEditor("Joint 2 maximum (deg)", linkage.joint2MaxRad * 180.0 / PI, Modifier.weight(1f)) { onLinkageChanged(linkage.copy(joint2MaxRad = it * PI / 180.0)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalEditor("Joint 1 torque / volt (N·m/V)", linkage.joint1TorquePerVoltNm, Modifier.weight(1f)) { onLinkageChanged(linkage.copy(joint1TorquePerVoltNm = it)) }
                DecimalEditor("Joint 2 torque / volt (N·m/V)", linkage.joint2TorquePerVoltNm, Modifier.weight(1f)) { onLinkageChanged(linkage.copy(joint2TorquePerVoltNm = it)) }
            }
            Text(
                "Torque per volt includes motor torque, gearbox ratio, and estimated efficiency. Start from motor data, then tune the simulation against measured motion before trusting it.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )

            LinkagePhysicsLab(linkage)
        }
    }
}

@Composable
private fun LinkagePhysicsLab(linkage: SubsystemLinkageDocument) {
    val plant = remember(linkage) {
        runCatching {
            TwoDofLinkagePlant(
                TwoDofLinkagePlantParameters(
                    linkage = TwoDofLinkageParameters(
                        linkage.link1LengthMeters,
                        linkage.link2LengthMeters,
                        linkage.link1MassKg,
                        linkage.link2MassKg,
                        linkage.link1CenterOfMassMeters,
                        linkage.link2CenterOfMassMeters,
                    ),
                    joint1TorquePerVoltNm = linkage.joint1TorquePerVoltNm,
                    joint2TorquePerVoltNm = linkage.joint2TorquePerVoltNm,
                    joint1ViscousDampingNmPerRadPerSec = linkage.joint1DampingNmPerRadPerSec,
                    joint2ViscousDampingNmPerRadPerSec = linkage.joint2DampingNmPerRadPerSec,
                    joint1MinimumRad = linkage.joint1MinRad,
                    joint1MaximumRad = linkage.joint1MaxRad,
                    joint2MinimumRad = linkage.joint2MinRad,
                    joint2MaximumRad = linkage.joint2MaxRad,
                ),
            )
        }.getOrNull()
    }
    if (plant == null) {
        StatusText("Fix the geometry, masses, limits, and torque constants to enable the physics lab.", error = true)
        return
    }
    val kinematics = remember(linkage) { TwoDofLinkageKinematics(plant.params.linkage) }
    var voltage1 by remember(plant) { mutableStateOf(0.0) }
    var voltage2 by remember(plant) { mutableStateOf(0.0) }
    var running by remember(plant) { mutableStateOf(false) }
    var theta1 by remember(plant) { mutableStateOf(plant.joint1PositionRad) }
    var theta2 by remember(plant) { mutableStateOf(plant.joint2PositionRad) }

    LaunchedEffect(plant, running, voltage1, voltage2) {
        while (running) {
            plant.step(voltage1, voltage2, 0.02)
            theta1 = plant.joint1PositionRad
            theta2 = plant.joint2PositionRad
            delay(20L)
        }
    }

    val pose = kinematics.forwardKinematics(theta1, theta2)
    val torques = kinematics.gravityTorque(theta1, theta2)
    val singular = kinematics.isNearSingularity(theta1, theta2)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("Interactive physics lab", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            Text("Accepted motor volts drive the same plant generated for Mock IO.", color = AresTextSecondary, fontSize = 11.sp)
        }
        StatusText(if (singular) "Near singularity" else "Kinematics healthy", error = singular)
    }
    LinkageCanvas(plant.params.linkage, theta1, theta2)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
        Metric("End effector", "%.2f, %.2f m".format(pose.x, pose.y), AresTextPrimary)
        Metric("Gravity torque 1", "%.2f N·m".format(torques[0]), AresCyan)
        Metric("Gravity torque 2", "%.2f N·m".format(torques[1]), AresGold)
    }
    VoltageSlider("Joint 1 accepted voltage", voltage1) { voltage1 = it }
    VoltageSlider("Joint 2 accepted voltage", voltage2) { voltage2 = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { running = !running }) { Text(if (running) "Pause" else "Run physics") }
        OutlinedButton(onClick = {
            running = false
            voltage1 = 0.0
            voltage2 = 0.0
            plant.reset()
            theta1 = plant.joint1PositionRad
            theta2 = plant.joint2PositionRad
        }) { Text("Reset safely") }
    }
}

@Composable
private fun LinkageCanvas(params: TwoDofLinkageParameters, theta1: Double, theta2: Double) {
    Box(Modifier.fillMaxWidth().height(250.dp).background(AresBackground, RoundedCornerShape(8.dp)).border(1.dp, AresBorder, RoundedCornerShape(8.dp))) {
        Canvas(Modifier.matchParentSize()) {
            val origin = Offset(size.width / 2f, size.height * .76f)
            val scale = size.height * .58f / params.maxReach.toFloat()
            val maxRadius = params.maxReach.toFloat() * scale
            drawCircle(AresCyan.copy(alpha = .08f), maxRadius, origin)
            drawCircle(AresCyan.copy(alpha = .35f), maxRadius, origin, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
            val elbow = Offset(
                origin.x + params.l1.toFloat() * cos(theta1).toFloat() * scale,
                origin.y - params.l1.toFloat() * sin(theta1).toFloat() * scale,
            )
            val end = Offset(
                elbow.x + params.l2.toFloat() * cos(theta1 + theta2).toFloat() * scale,
                elbow.y - params.l2.toFloat() * sin(theta1 + theta2).toFloat() * scale,
            )
            drawLine(AresBorder, Offset(0f, origin.y), Offset(size.width, origin.y), 2f)
            drawLine(AresCyan, origin, elbow, 7f, StrokeCap.Round)
            drawLine(AresGold, elbow, end, 6f, StrokeCap.Round)
            drawCircle(AresTextPrimary, 6f, origin)
            drawCircle(AresCyan, 5f, elbow)
            drawCircle(AresGold, 6f, end)
        }
    }
}

@Composable
private fun VoltageSlider(label: String, value: Double, onChange: (Double) -> Unit) {
    Column {
        Text("$label: %.1f V".format(value), color = AresTextPrimary, fontSize = 12.sp)
        Slider(value.toFloat(), { onChange(it.toDouble()) }, valueRange = -12f..12f)
    }
}

@Composable
private fun Metric(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = AresTextSecondary, fontSize = 10.sp)
        Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DecimalEditor(label: String, value: Double, modifier: Modifier = Modifier, onValidValue: (Double) -> Unit) {
    AresDoubleField(
        label = label,
        value = value,
        modifier = modifier,
        supportingText = { _, isError -> "Enter a finite number".takeIf { isError } },
        onValueChange = onValidValue,
    )
}

@Composable
private fun IdSelector(label: String, selected: String?, options: List<String>, modifier: Modifier, onSelected: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = selected ?: "Not selected",
            onValueChange = {},
            label = { Text(label) },
            readOnly = true,
            trailingIcon = { IconButton(onClick = { expanded = true }) { Icon(Icons.Default.ArrowDropDown, null) } },
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        )
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.distinct().forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelected(option); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun StatusText(message: String, error: Boolean) {
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = (if (error) AresError else AresGreen).copy(alpha = .14f),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (error) Icon(Icons.Default.Warning, null, tint = AresError, modifier = Modifier.size(14.dp))
            Text(message, color = if (error) AresError else AresGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
