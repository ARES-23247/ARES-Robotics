package com.ares.analytics.ui.help

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.learning.AutonomousMissionCheckpointIds
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.areslib.routine.RoutineValidationIssue
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Plain-English translation of technical ARES fail-closed, lease, hardware port, and build
 * diagnostics so novice students immediately understand:
 * 1. What happened (without intimidating jargon)
 * 2. How to fix it (concrete next action in the GUI)
 */
data class StudentDiagnosticExplanation(
    val categoryTitle: String,
    val whatHappened: String,
    val howToFixIt: String,
    val rawDiagnostic: String,
)

/**
 * Translates a raw technical diagnostic or status string into a student-friendly
 * explanation with a clear "What happened" and "How to fix it" action.
 */
fun translateDiagnosticForStudent(rawMessage: String): StudentDiagnosticExplanation {
    val trimmed = rawMessage.trim()
    val lower = trimmed.lowercase()

    return when {
        lower.contains("disarmed") ||
            lower.contains("neutral control lease") ||
            lower.contains("restore the safe control lease") ||
            lower.contains("arm control") ||
            lower.contains("stale") -> StudentDiagnosticExplanation(
            categoryTitle = "Safety Disarm (Outputs Neutral)",
            whatHappened = "ARES paused robot motion because the keyboard/gamepad control lease is not armed or focus left the simulator window.",
            howToFixIt = "Click 'Arm control' in the simulator bar and keep the Studio window focused while driving with WASD.",
            rawDiagnostic = trimmed,
        )

        lower.contains("port") ||
            lower.contains("can id") ||
            lower.contains("unassigned") ||
            lower.contains("hardwaremap") ||
            lower.contains("expansion hub") ||
            lower.contains("control hub") ||
            lower.contains("pwm") -> StudentDiagnosticExplanation(
            categoryTitle = "Hardware Port Not Assigned",
            whatHappened = "A motor or sensor in your subsystem does not have a valid physical port or CAN ID assigned yet.",
            howToFixIt = "Open '2. Hardware & IO' or the Port Map tab and give every required motor and sensor a unique port number.",
            rawDiagnostic = trimmed,
        )

        lower.contains("blocked") ||
            lower.contains("opmode") ||
            lower.contains("teleop") ||
            lower.contains("start driving") ||
            lower.contains("autonomous") -> StudentDiagnosticExplanation(
            categoryTitle = "Robot Mode Needs Attention",
            whatHappened = "The simulator server is connected, but no TeleOp/Autonomous routine is actively running—or a safety check paused the routine.",
            howToFixIt = "Select a TeleOp or Autonomous mode in the dropdown and click 'Start driving' (click 'Stop' first if a routine was blocked).",
            rawDiagnostic = trimmed,
        )

        lower.contains("feedback") ||
            lower.contains("encoder") ||
            lower.contains("homing") ||
            lower.contains("nan") ||
            lower.contains("limit") ||
            lower.contains("fault") -> StudentDiagnosticExplanation(
            categoryTitle = "Sensor Feedback Safety Check",
            whatHappened = "A position or velocity controller (PID) needs valid sensor readings before ARES will send power to the motor.",
            howToFixIt = "Check your sensor wiring in '2. Hardware & IO', or switch to an Open-Loop Voltage rule if this mechanism does not use an encoder.",
            rawDiagnostic = trimmed,
        )

        lower.contains("verify") ||
            lower.contains("build") ||
            lower.contains("compile") ||
            lower.contains("gradle") -> StudentDiagnosticExplanation(
            categoryTitle = "Project Verification Required",
            whatHappened = "Your robot project or subsystem settings changed and need a quick build check before the simulator launches.",
            howToFixIt = "Click 'Verify & launch' (or 'Save & create files' in the Subsystem Builder) and wait for the green build status.",
            rawDiagnostic = trimmed,
        )

        else -> StudentDiagnosticExplanation(
            categoryTitle = "Diagnostic Guidance",
            whatHappened = trimmed.ifBlank { "ARES reported a status check on the current robot or simulator session." },
            howToFixIt = "Check the highlighted control bar or validation card above, fix any flagged setting, and try the action again.",
            rawDiagnostic = trimmed,
        )
    }
}

@Composable
fun StudentFriendlyDiagnosticCallout(
    explanation: StudentDiagnosticExplanation,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AresSurfaceElevated,
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Lightbulb,
                contentDescription = null,
                tint = AresAmber,
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${explanation.categoryTitle}: ${explanation.whatHappened}",
                    color = AresTextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "How to fix: ${explanation.howToFixIt}",
                    color = AresCyan,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
