package com.ares.analytics.ui.screens.fieldeditor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.FTCCoordinateSystem
import com.ares.analytics.shared.FieldImageConfig
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.util.DesktopFileChoosers
import java.io.File

/**
 * Control section for uploading, cropping, rotating, and orienting field background images.
 */
@Composable
fun FieldImageSettingsSection(
    config: FieldImageConfig,
    league: League,
    projectPath: String?,
    onUpdateConfig: (FieldImageConfig) -> Unit,
    onClearImage: () -> Unit,
    onUploadImage: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCropBoundaries by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Field Background Editor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AresTextPrimary)
        HorizontalDivider(color = AresBorder)

        Button(
            onClick = {
                if (!projectPath.isNullOrEmpty()) {
                    DesktopFileChoosers.chooseOpenFile(
                        dialogTitle = "Select Field Image (PNG/JPG)",
                        filterDescription = "Images (*.png, *.jpg, *.jpeg)",
                        extensions = listOf("png", "jpg", "jpeg"),
                    )?.let { selected ->
                        if (selected.exists()) {
                            onUploadImage(selected)
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Upload, contentDescription = null, tint = AresBackground)
            Spacer(Modifier.width(8.dp))
            Text("Upload Field Image", color = AresBackground, fontWeight = FontWeight.Bold)
        }

        if (config.imagePath.isNotBlank()) {
            OutlinedButton(
                onClick = onClearImage,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.HideImage, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Remove background reference")
            }
            Text(
                "Keeps the image file on disk so it can be restored later.",
                color = AresTextSecondary,
                fontSize = 10.sp,
            )
        }

        HorizontalDivider(color = AresBorder)

        Row(
            modifier = Modifier.fillMaxWidth().clickable { showCropBoundaries = !showCropBoundaries }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Image Adjustments", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AresTextPrimary)
            Icon(
                imageVector = if (showCropBoundaries) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Expand/Collapse",
                tint = AresTextSecondary
            )
        }

        AnimatedVisibility(visible = showCropBoundaries) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Orientation", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(0.0, 90.0, 180.0, 270.0).forEach { angle ->
                            val isSelected = config.rotationDegrees == angle
                            Button(
                                onClick = { onUpdateConfig(config.copy(rotationDegrees = angle)) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) AresCyan else AresSurfaceElevated,
                                    contentColor = if (isSelected) AresOnAccent else AresTextPrimary
                                ),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp)
                            ) {
                                Text("${angle.toInt()}°", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Crop Boundaries", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                    Text("Left Crop: ${String.format("%.2f", config.cropLeft)}", fontSize = 11.sp, color = AresTextSecondary)
                    Slider(
                        value = config.cropLeft.toFloat(),
                        onValueChange = { onUpdateConfig(config.copy(cropLeft = it.toDouble().coerceIn(0.0, config.cropRight))) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = AresCyan, activeTrackColor = AresCyan)
                    )

                    Text("Right Crop: ${String.format("%.2f", config.cropRight)}", fontSize = 11.sp, color = AresTextSecondary)
                    Slider(
                        value = config.cropRight.toFloat(),
                        onValueChange = { onUpdateConfig(config.copy(cropRight = it.toDouble().coerceIn(config.cropLeft, 1.0))) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = AresCyan, activeTrackColor = AresCyan)
                    )

                    Text("Top Crop: ${String.format("%.2f", config.cropTop)}", fontSize = 11.sp, color = AresTextSecondary)
                    Slider(
                        value = config.cropTop.toFloat(),
                        onValueChange = { onUpdateConfig(config.copy(cropTop = it.toDouble().coerceIn(0.0, config.cropBottom))) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = AresCyan, activeTrackColor = AresCyan)
                    )

                    Text("Bottom Crop: ${String.format("%.2f", config.cropBottom)}", fontSize = 11.sp, color = AresTextSecondary)
                    Slider(
                        value = config.cropBottom.toFloat(),
                        onValueChange = { onUpdateConfig(config.copy(cropBottom = it.toDouble().coerceIn(config.cropTop, 1.0))) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = AresCyan, activeTrackColor = AresCyan)
                    )
                }
            }
        }

        if (league == League.FTC) {
            HorizontalDivider(color = AresBorder)

            Text("FTC Coordinate System", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AresTextPrimary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FTCCoordinateSystem.entries.forEach { coord ->
                    val isSelected = config.ftcCoordinateSystem == coord
                    Button(
                        onClick = { onUpdateConfig(config.copy(ftcCoordinateSystem = coord)) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) AresCyan else AresSurfaceElevated,
                            contentColor = if (isSelected) AresOnAccent else AresTextPrimary
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        Text(
                            text = when (coord) {
                                FTCCoordinateSystem.DIAMOND -> "Diamond"
                                FTCCoordinateSystem.SQUARE -> "Square"
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
