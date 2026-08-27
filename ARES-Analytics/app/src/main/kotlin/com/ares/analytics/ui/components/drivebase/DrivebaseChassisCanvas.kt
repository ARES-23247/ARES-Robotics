package com.ares.analytics.ui.components.drivebase

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.drivebase.DriveHardwareDeclaration
import com.ares.analytics.service.drivebase.cornerDriveHardware
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderState

@Composable
fun InteractiveChassisCanvas(
    state: DrivebaseBuilderState,
    onSelectHardware: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cornerHardware = state.draft.cornerDriveHardware()
    val fl = cornerHardware.getOrNull(0)
    val fr = cornerHardware.getOrNull(1)
    val rl = cornerHardware.getOrNull(2)
    val rr = cornerHardware.getOrNull(3)

    BoxWithConstraints(
        modifier
            .background(AresSurfaceElevated, RoundedCornerShape(8.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        val padX = widthPx * 0.22f
        val padY = heightPx * 0.22f
        val left = padX
        val right = widthPx - padX
        val top = padY
        val bottom = heightPx - padY

        val wheelW = 26f
        val wheelH = 46f

        Canvas(Modifier.fillMaxSize()) {
            // Draw Robot Frame / Bumper Perimeter
            drawRoundRect(
                color = AresBorder,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = CornerRadius(10f, 10f),
                style = Stroke(2.5f)
            )

            // Center Crosshair / Center of Rotation
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            drawLine(AresBorder, Offset(cx - 15, cy), Offset(cx + 15, cy), 1.5f)
            drawLine(AresBorder, Offset(cx, cy - 15), Offset(cx, cy + 15), 1.5f)
            drawCircle(AresCyan.copy(alpha = 0.3f), 8f, Offset(cx, cy))

            // Forward Orientation Arrow
            val arrowTop = top - 24f
            val arrowBot = top + 10f
            drawLine(AresCyan, Offset(cx, arrowBot), Offset(cx, arrowTop), 3.5f)
            drawLine(AresCyan, Offset(cx, arrowTop), Offset(cx - 8f, arrowTop + 8f), 3.5f)
            drawLine(AresCyan, Offset(cx, arrowTop), Offset(cx + 8f, arrowTop + 8f), 3.5f)

            // Draw 4 Corner Wheel Modules
            val wheelConfigs = listOf(
                Pair(Offset(left, top), fl),
                Pair(Offset(right, top), fr),
                Pair(Offset(left, bottom), rl),
                Pair(Offset(right, bottom), rr),
            )

            wheelConfigs.forEach { (pos, device) ->
                val isSelected = device != null && device.id == state.selectedHardwareId
                val wheelColor = if (isSelected) AresCyan else if (device?.inverted == true) AresError else AresGreen
                val bgWheelColor = if (isSelected) AresCyan.copy(alpha = 0.25f) else AresSurface

                // Wheel Body
                drawRoundRect(
                    color = bgWheelColor,
                    topLeft = Offset(pos.x - wheelW / 2, pos.y - wheelH / 2),
                    size = Size(wheelW, wheelH),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                drawRoundRect(
                    color = wheelColor,
                    topLeft = Offset(pos.x - wheelW / 2, pos.y - wheelH / 2),
                    size = Size(wheelW, wheelH),
                    cornerRadius = CornerRadius(4f, 4f),
                    style = Stroke(if (isSelected) 3f else 1.5f)
                )

                // Tread / Roller stripes
                for (i in -1..1) {
                    val ty = pos.y + i * 11f
                    drawLine(
                        wheelColor.copy(alpha = 0.7f),
                        Offset(pos.x - wheelW / 2 + 4, ty - 4),
                        Offset(pos.x + wheelW / 2 - 4, ty + 4),
                        1.5f
                    )
                }

                // Selection Halo
                if (isSelected) {
                    drawCircle(AresCyan.copy(alpha = 0.2f), 30f, pos)
                }
            }
        }

        // Clickable Corner Wheel Labels
        val density = LocalDensity.current
        val corners = listOf(
            Triple(left, top, fl),
            Triple(right, top, fr),
            Triple(left, bottom, rl),
            Triple(right, bottom, rr),
        )
        corners.forEachIndexed { idx, (x, y, dev) ->
            val label = listOf("FL", "FR", "RL", "RR")[idx]
            val xDp = with(density) { (x - 20).toDp() }
            val yDp = with(density) { (y - 20).toDp() }
            Box(
                modifier = Modifier
                    .offset(xDp, yDp)
                    .size(40.dp)
                    .clickable { dev?.id?.let { onSelectHardware(it) } },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = if (dev?.id == state.selectedHardwareId) AresCyan else AresSurface.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, if (dev?.id == state.selectedHardwareId) AresCyan else AresBorder),
                ) {
                    Text(
                        label,
                        color = if (dev?.id == state.selectedHardwareId) AresOnAccent else AresTextPrimary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun MotorGridCard(
    cornerCode: String,
    defaultCornerName: String,
    device: DriveHardwareDeclaration?,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleInvert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) AresCyan else AresBorder,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onSelect),
        color = if (isSelected) AresCyan.copy(alpha = 0.08f) else AresSurfaceElevated,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = if (isSelected) AresCyan else AresBorder,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            cornerCode,
                            color = if (isSelected) AresOnAccent else AresTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        device?.displayName ?: defaultCornerName,
                        color = AresTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
                Text(
                    device?.canId?.let { "CAN $it${device.canBus?.let { bus -> " · $bus" }.orEmpty()}" }
                        ?: "hw: ${device?.hardwareName ?: "none"}",
                    color = AresCyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Direction Toggle Chip
                val isInverted = device?.inverted == true
                Surface(
                    modifier = Modifier.clickable(onClick = onToggleInvert),
                    shape = RoundedCornerShape(4.dp),
                    color = if (isInverted) AresError.copy(alpha = 0.15f) else AresGreen.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, if (isInverted) AresError else AresGreen),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).background(if (isInverted) AresError else AresGreen, CircleShape))
                        Text(
                            if (isInverted) "INVERTED" else "NORMAL",
                            color = if (isInverted) AresError else AresGreen,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Text(
                    if (isSelected) "● Selected" else "Click to edit",
                    color = if (isSelected) AresCyan else AresTextTertiary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
fun AuxHardwareRow(
    device: DriveHardwareDeclaration,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleInvert: () -> Unit,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) AresCyan else AresBorder,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onSelect),
        color = if (isSelected) AresCyan.copy(alpha = 0.08f) else AresSurfaceElevated,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val (badgeText, badgeColor) = when (device.role) {
                    com.ares.analytics.service.drivebase.DriveHardwareRole.LIMELIGHT -> "CAM" to AresCyan
                    com.ares.analytics.service.drivebase.DriveHardwareRole.ODOMETRY -> "ODOM" to AresGold
                    com.ares.analytics.service.drivebase.DriveHardwareRole.GYRO -> "GYRO" to AresCyan
                    com.ares.analytics.service.drivebase.DriveHardwareRole.DISTANCE_SENSOR -> "DIST" to AresGreen
                    com.ares.analytics.service.drivebase.DriveHardwareRole.DRIVE_MOTOR -> "MTR" to AresTextSecondary
                    else -> "AUX" to AresTextSecondary
                }
                Surface(
                    color = badgeColor.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(3.dp),
                ) {
                    Text(
                        badgeText,
                        color = badgeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                Text(device.displayName, color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    device.canId?.let { "CAN $it${device.canBus?.let { bus -> " · $bus" }.orEmpty()}" }
                        ?: "hw: ${device.hardwareName}",
                    color = AresCyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val isInverted = device.inverted
                Surface(
                    modifier = Modifier.clickable(onClick = onToggleInvert),
                    shape = RoundedCornerShape(3.dp),
                    color = if (isInverted) AresError.copy(alpha = 0.15f) else AresGreen.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, if (isInverted) AresError else AresGreen),
                ) {
                    Text(
                        if (isInverted) "INV" else "NORM",
                        color = if (isInverted) AresError else AresGreen,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                if (onRemove != null) {
                    androidx.compose.material3.IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(20.dp),
                    ) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Default.Close,
                            contentDescription = "Remove sensor",
                            tint = AresTextTertiary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}
