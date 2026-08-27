package com.ares.analytics.ui.components.routine

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.AutonomousTourStep
import com.ares.analytics.viewmodel.PathPlannerIntent

/**
 * Interactive step-by-step guided overlay for novice robot programmers learning the Routine Builder.
 */
@Composable
fun FirstAutonomousTourOverlay(
    currentStep: AutonomousTourStep?,
    onIntent: (PathPlannerIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = currentStep != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        if (currentStep == null) return@AnimatedVisibility

        val steps = AutonomousTourStep.entries
        val currentIndex = steps.indexOf(currentStep)
        val isFirst = currentIndex == 0
        val isLast = currentIndex == steps.size - 1

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
                .padding(24.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 360.dp, max = 580.dp)
                    .clip(RoundedCornerShape(16.dp)),
                color = AresSurfaceElevated,
                border = BorderStroke(1.5.dp, AresCyan),
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header with Icon, Step badge, and Close Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(AresCyan.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.School,
                                    contentDescription = "Guided Tour",
                                    tint = AresCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text(
                                    "Autonomous Builder Tour",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AresCyan,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Step ${currentIndex + 1} of ${steps.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AresTextSecondary
                                )
                            }
                        }

                        IconButton(
                            onClick = { onIntent(PathPlannerIntent.DismissTour) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close tour",
                                tint = AresTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Step Progress Dots
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        steps.forEachIndexed { index, _ ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        when {
                                            index == currentIndex -> AresCyan
                                            index < currentIndex -> AresGreen
                                            else -> AresBorder
                                        }
                                    )
                            )
                        }
                    }

                    // Content Title and Description
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = currentStep.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = AresTextPrimary
                        )
                        Text(
                            text = currentStep.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AresTextSecondary,
                            lineHeight = 20.sp
                        )
                    }

                    // Action Controls: Back, Next, Finish
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onIntent(PathPlannerIntent.DismissTour) }
                        ) {
                            Text("Skip Tour", color = AresTextTertiary, fontSize = 13.sp)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isFirst) {
                                OutlinedButton(
                                    onClick = { onIntent(PathPlannerIntent.PreviousTourStep) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AresTextPrimary),
                                    border = BorderStroke(1.dp, AresBorder)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Back")
                                }
                            }

                            Button(
                                onClick = {
                                    if (isLast) {
                                        onIntent(PathPlannerIntent.DismissTour)
                                    } else {
                                        onIntent(PathPlannerIntent.NextTourStep)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AresCyan,
                                    contentColor = AresOnAccent
                                )
                            ) {
                                if (isLast) {
                                    Icon(Icons.Default.Check, contentDescription = "Done", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Finish Tour")
                                } else {
                                    Text("Next")
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
