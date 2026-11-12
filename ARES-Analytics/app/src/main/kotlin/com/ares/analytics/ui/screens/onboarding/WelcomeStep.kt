package com.ares.analytics.ui.screens.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.BuildConfig
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresCyanGlow
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.rememberAresAppIconPainter
import com.ares.analytics.viewmodel.OnboardingStep

@Composable
fun WelcomeStep(
    currentStep: OnboardingStep,
    canSelectStep: (OnboardingStep) -> Boolean = { false },
    onSelectStep: (OnboardingStep) -> Unit = {},
) {
    val heading = when (currentStep) {
        OnboardingStep.PROJECT -> "Choose your robot project"
        OnboardingStep.ROBOT -> "Check the robot details"
        OnboardingStep.OPTIONAL -> "Optional connections"
        OnboardingStep.REVIEW -> "Ready to finish"
    }
    val guidance = when (currentStep) {
        OnboardingStep.PROJECT -> "Create a robot project, try an editable example, or open a repository on this computer. Setup works offline."
        OnboardingStep.ROBOT -> "Confirm the team, season, and robot. Detected values are already filled in and can be changed."
        OnboardingStep.OPTIONAL -> "Cloud sync and custom connection settings can be added now or later. The dashboard works fully offline."
        OnboardingStep.REVIEW -> "Review the workspace. Robot build tools are optional now and can be added later. Nothing is uploaded unless you choose cloud sync."
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = rememberAresAppIconPainter(),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Column {
                Text(
                    text = "${BuildConfig.PRODUCT_NAME} setup",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AresTextPrimary,
                )
                Text("Step ${currentStep.number} of ${OnboardingStep.entries.size}", color = AresTextSecondary, fontSize = 12.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OnboardingStep.entries.forEach { step ->
                val isActive = step == currentStep
                val isCompleted = step.ordinal < currentStep.ordinal
                val selectable = canSelectStep(step)
                val stepLabel = when (step) {
                    OnboardingStep.PROJECT -> "1. Project"
                    OnboardingStep.ROBOT -> "2. Robot"
                    OnboardingStep.OPTIONAL -> "3. Connect"
                    OnboardingStep.REVIEW -> "4. Finish"
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .then(if (selectable) Modifier.clickable { onSelectStep(step) } else Modifier),
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        isActive -> AresCyanGlow
                        isCompleted -> AresSurfaceElevated
                        else -> AresSurfaceElevated.copy(alpha = 0.6f)
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isActive -> AresCyan
                            isCompleted -> AresGreen.copy(alpha = 0.55f)
                            else -> AresBorder
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (isCompleted) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = AresGreen,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = stepLabel,
                            color = when {
                                isActive -> AresCyan
                                isCompleted -> AresTextPrimary
                                else -> AresTextSecondary
                            },
                            fontSize = 11.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(heading, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            Text(guidance, style = MaterialTheme.typography.bodyMedium, color = AresTextSecondary, lineHeight = 20.sp)
        }
    }
}
