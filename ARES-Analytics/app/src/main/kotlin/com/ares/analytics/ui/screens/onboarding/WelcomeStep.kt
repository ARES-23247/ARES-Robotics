package com.ares.analytics.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.BuildConfig
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresCyanGlow
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.rememberAresAppIconPainter
import com.ares.analytics.viewmodel.OnboardingStep

@Composable
fun WelcomeStep(currentStep: OnboardingStep) {
    val heading = when (currentStep) {
        OnboardingStep.PROJECT -> "Choose your robot project"
        OnboardingStep.ROBOT -> "Check the robot details"
        OnboardingStep.OPTIONAL -> "Optional connections"
        OnboardingStep.REVIEW -> "Ready to finish"
    }
    val guidance = when (currentStep) {
        OnboardingStep.PROJECT -> "Pick the folder you use to build your robot. ARES will detect FTC, FRC, or XRP and fill in anything it recognizes."
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (step.ordinal <= currentStep.ordinal) AresCyan else AresSurfaceElevated),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(heading, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            Text(guidance, style = MaterialTheme.typography.bodyMedium, color = AresTextSecondary, lineHeight = 20.sp)
        }

        if (currentStep == OnboardingStep.PROJECT) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AresSurfaceElevated)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("How this system works", color = AresCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "•  You describe robot behavior — routines, subsystems, controller bindings — as documents.",
                    color = AresTextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
                Text(
                    "•  ARES turns those documents into reviewed runtime code and verifies it in simulation before anything reaches a robot.",
                    color = AresTextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
                Text(
                    "•  Team members still write code for unusual hardware or brand-new capabilities.",
                    color = AresTextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
                Text(
                    "New here? The Academy (Help & Learn) opens with \"Why documents instead of programs\".",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
    }
}
