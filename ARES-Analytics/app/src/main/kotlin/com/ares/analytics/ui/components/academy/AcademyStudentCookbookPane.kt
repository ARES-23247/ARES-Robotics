@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components.academy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

enum class StudentCookbookCategory(val label: String) {
    ALL("All Recipes"),
    QUICK_START("First 15 Minutes"),
    FTC("FTC Recipes"),
    FRC("FRC Recipes"),
    XRP("XRP Recipes"),
    CODE_BRIDGE("GUI ↔ Code Bridge"),
}

data class AcademyStudentCookbookEntry(
    val id: String,
    val category: StudentCookbookCategory,
    val badge: String,
    val title: String,
    val summary: String,
    val steps: List<String>,
    val safeFileHint: String,
    val codeSnippet: String,
    val actionLabel: String,
    val destination: NavigationTarget? = null,
    val launchesSimulator: Boolean = false,
)

fun studentCookbookEntries(): List<AcademyStudentCookbookEntry> = listOf(
    AcademyStudentCookbookEntry(
        id = "first_15_minutes_simulator",
        category = StudentCookbookCategory.QUICK_START,
        badge = "NO ROBOT NEEDED",
        title = "First 15 Minutes: Drive in the Local Simulator",
        summary = "Start the built-in physics simulator, drive with WASD on the Pit Dashboard, and watch real-time pose and heading telemetry.",
        steps = listOf(
            "Click 'Start Local Simulator' below (or on the Pit Dashboard).",
            "Choose the 'Student Quick-Start (4 Cards)' layout preset on the Pit Dashboard.",
            "Click 'Arm Keyboard Drive' on the top bar and use W/A/S/D to drive and Q/E to rotate.",
            "Observe X, Y, and CCW+ heading update in real time on the 2D Field Map.",
        ),
        safeFileHint = ".ares/robot_config.json (saved automatically by Studio)",
        codeSnippet = """
            // Inside your TeleOp loop, ARES reads gamepad sticks and dispatches an immutable action:
            val driveCommand = ChassisSpeeds(
                vxMetersPerSecond = -gamepad.leftStickY * maxSpeedMetersPerSec,
                vyMetersPerSecond = -gamepad.leftStickX * maxSpeedMetersPerSec,
                omegaRadiansPerSecond = -gamepad.rightStickX * maxAngularRadPerSec,
            )
        """.trimIndent(),
        actionLabel = "Start Local Simulator",
        launchesSimulator = true,
    ),
    AcademyStudentCookbookEntry(
        id = "ftc_roller_intake",
        category = StudentCookbookCategory.FTC,
        badge = "FTC · OPEN-LOOP VOLTAGE",
        title = "FTC Roller Intake (Collect & Eject Game Pieces)",
        summary = "Build a 1-motor intake in Step 2 (Subsystems) using the 'FTC Roller Intake' 1-click preset, then add safe helper functions.",
        steps = listOf(
            "Open Step 2 (Subsystems) and click the 'FTC Roller Intake' preset chip in Step 1.",
            "Verify the REV Control Hub motor port (Port 0) in Step 2 (Motors & Sensors).",
            "Click 'Save & create files' to generate IntakeState and IntakeAction.",
            "Paste the helper below into your team's safe IntakeExtensions.kt file.",
        ),
        safeFileHint = "TeamCode/src/main/kotlin/.../subsystems/IntakeExtensions.kt (USER-OWNED)",
        codeSnippet = """
            // Safe Student Extension File: IntakeExtensions.kt
            // ARES never overwrites this file when you click 'Save & create files'.
            fun IntakeState.isIntaking(): Boolean =
                enabled && !faulted && targetVoltage > 1.0

            fun intakeCollectVoltage(): Double = 8.0   // Pull game piece in
            fun intakeEjectVoltage(): Double = -6.0    // Reverse to clear jam
        """.trimIndent(),
        actionLabel = "Open Step 2: Subsystems",
        destination = NavigationTarget.SUBSYSTEM_GEN,
    ),
    AcademyStudentCookbookEntry(
        id = "ftc_scoring_arm",
        category = StudentCookbookCategory.FTC,
        badge = "FTC · POSITION PID + GRAVITY",
        title = "FTC Scoring Arm (0° Stow to 115° Basket)",
        summary = "Control a single-jointed scoring arm with soft angle limits (0°..115°) and gravity feedforward (kG) so it holds position.",
        steps = listOf(
            "Open Step 2 (Subsystems) and click the 'FTC Scoring Arm (0–115°)' preset chip.",
            "In Step 4 (Tune & Verify), use the 'Arm / elevator sags' symptom helper if gravity pulls the arm down.",
            "Use the readiness check below in your autonomous routine before releasing a game piece.",
        ),
        safeFileHint = "TeamCode/src/main/kotlin/.../subsystems/ScoringArmExtensions.kt (USER-OWNED)",
        codeSnippet = """
            // Safe Student Extension File: ScoringArmExtensions.kt
            import kotlin.math.abs

            fun ScoringArmState.isAtScoringAngle(toleranceDeg: Double = 2.5): Boolean =
                enabled && !faulted && abs(targetAngleDeg - measuredAngleDeg) <= toleranceDeg
        """.trimIndent(),
        actionLabel = "Open Step 2: Subsystems",
        destination = NavigationTarget.SUBSYSTEM_GEN,
    ),
    AcademyStudentCookbookEntry(
        id = "frc_flywheel_shooter",
        category = StudentCookbookCategory.FRC,
        badge = "FRC · VELOCITY PID + kV",
        title = "FRC Flywheel Shooter (Spin Up & Wait for RPM Lock)",
        summary = "Use velocity feedforward (kS, kV) and proportional trim (kP) to spin a shooter wheel to 4200 RPM before feeding a game piece.",
        steps = listOf(
            "Open Step 2 (Subsystems) and click the 'FRC Flywheel Shooter' preset chip.",
            "In Step 4 (Tune & Verify), tune kV first so the wheel reaches ~95% of target RPM, then add a small kP.",
            "Check isFlywheelReadyToShoot() before running the indexer.",
        ),
        safeFileHint = "src/main/kotlin/.../subsystems/ShooterExtensions.kt (USER-OWNED)",
        codeSnippet = """
            // Safe Student Extension File: ShooterExtensions.kt
            import kotlin.math.abs

            fun ShooterState.isFlywheelReadyToShoot(minRpm: Double = 3800.0, toleranceRpm: Double = 75.0): Boolean =
                enabled && !faulted &&
                    targetVelocityRpm >= minRpm &&
                    abs(targetVelocityRpm - measuredVelocityRpm) <= toleranceRpm
        """.trimIndent(),
        actionLabel = "Open Step 2: Subsystems",
        destination = NavigationTarget.SUBSYSTEM_GEN,
    ),
    AcademyStudentCookbookEntry(
        id = "xrp_ultrasonic_ranger",
        category = StudentCookbookCategory.XRP,
        badge = "XRP · MICROPYTHON SENSOR",
        title = "XRP Ultrasonic Rangefinder (Stop Before Obstacle)",
        summary = "Read the XRP ultrasonic distance sensor in centimeters and stop or turn smoothly when a wall is closer than 20 cm.",
        steps = listOf(
            "Open Step 2 (Subsystems) with an XRP project and click the 'XRP Ultrasonic Ranger' preset chip.",
            "Click 'Save & create files' to generate the MicroPython sensor adapter.",
            "Add the obstacle check below to your safe src/subsystems/rangefinder_extensions.py file.",
        ),
        safeFileHint = "src/subsystems/rangefinder_extensions.py (USER-OWNED)",
        codeSnippet = """
            # Safe Student Extension File: src/subsystems/rangefinder_extensions.py
            # ARES never overwrites this file when you click 'Save & create files'.
            def should_stop_for_wall(distance_cm: float, stop_threshold_cm: float = 20.0) -> bool:
                \"\"\"Returns True when the XRP is within stop_threshold_cm of an obstacle.\"\"\"
                return 0.0 < distance_cm <= stop_threshold_cm
        """.trimIndent(),
        actionLabel = "Open Step 2: Subsystems",
        destination = NavigationTarget.SUBSYSTEM_GEN,
    ),
    AcademyStudentCookbookEntry(
        id = "gui_to_code_bridge_guide",
        category = StudentCookbookCategory.CODE_BRIDGE,
        badge = "ARCHITECTURE · SAFE FILES",
        title = "How the Visual GUI Connects to Your Robot Code",
        summary = "Understand which files ARES regenerates automatically and which files belong 100% to your student team.",
        steps = listOf(
            "1. Visual Descriptor (.ares/subsystems/*.aressubsystem): Saved when you edit motors, ports, and limits in the GUI.",
            "2. Auto-Generated Plumbing (build/generated/ares/): Recreated on every build. Never hand-edit files inside build/generated/.",
            "3. Safe Team Extensions (*Extensions.kt / *_extensions.py): Marked USER-OWNED. ARES never overwrites your custom code here.",
        ),
        safeFileHint = "Click 'GUI ↔ Code Bridge' inside Step 2 (Subsystems) to copy a tailored template for any mechanism.",
        codeSnippet = """
            // Project Layout Overview:
            // ├── .ares/
            // │   ├── robot_config.json                  <-- Saved by Step 1 & Step 3 (Drivetrain & Ports)
            // │   └── subsystems/intake.aressubsystem    <-- Saved by Step 2 (Subsystem Builder GUI)
            // ├── build/generated/ares/                  <-- AUTO-GENERATED (Read-Only State/Actions/Registry)
            // └── src/main/kotlin/.../subsystems/
            //     └── IntakeExtensions.kt                <-- SAFE TO EDIT (Your team's custom functions!)
        """.trimIndent(),
        actionLabel = "Open Step 5: Autonomous Routines",
        destination = NavigationTarget.PATH_PLANNER,
    ),
)

@Composable
fun StudentCookbookPane(
    onBack: () -> Unit,
    onOpenScreen: (NavigationTarget) -> Unit,
    onStartSimulator: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by remember { mutableStateOf(StudentCookbookCategory.ALL) }
    val allEntries = remember { studentCookbookEntries() }
    val filteredEntries = remember(selectedCategory) {
        if (selectedCategory == StudentCookbookCategory.ALL) {
            allEntries
        } else {
            allEntries.filter { it.category == selectedCategory }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(AresBackground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Back to Academy")
                }
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AresCyan, modifier = Modifier.size(22.dp))
                Column {
                    Text(
                        text = "Student Cookbook & First-15-Minutes Quick-Start",
                        color = AresTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Text(
                        text = "Copy-ready FTC, FRC, and XRP mechanism recipes plus safe GUI-to-Code extension templates.",
                        color = AresTextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        com.ares.analytics.ui.components.core.AresPresetChipBar(
            items = StudentCookbookCategory.entries,
            labelFor = { it.label },
            isSelected = { selectedCategory == it },
            onSelect = { selectedCategory = it },
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(filteredEntries, key = AcademyStudentCookbookEntry::id) { entry ->
                StudentCookbookEntryCard(
                    entry = entry,
                    onOpenScreen = onOpenScreen,
                    onStartSimulator = onStartSimulator,
                )
            }
        }
    }
}

@Composable
private fun StudentCookbookEntryCard(
    entry: AcademyStudentCookbookEntry,
    onOpenScreen: (NavigationTarget) -> Unit,
    onStartSimulator: () -> Unit,
) {
    var copied by remember(entry.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(text = entry.badge, color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(text = entry.title, color = AresTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(text = entry.summary, color = AresTextSecondary, fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        if (entry.launchesSimulator) onStartSimulator()
                        else if (entry.destination != null) onOpenScreen(entry.destination)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) {
                    Icon(
                        imageVector = if (entry.launchesSimulator) Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.Launch,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(entry.actionLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                entry.steps.forEachIndexed { index, step ->
                    Text(text = "${index + 1}. $step", color = AresTextPrimary, fontSize = 11.sp)
                }
            }

            Surface(
                color = AresSurfaceElevated,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = AresGreen, modifier = Modifier.size(15.dp))
                            Text(
                                text = entry.safeFileHint,
                                color = AresGreen,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                com.ares.analytics.ui.util.copyTextToSystemClipboard(entry.codeSnippet)
                                copied = true
                            },
                            modifier = Modifier.height(26.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            border = BorderStroke(1.dp, if (copied) AresGreen else AresCyan.copy(alpha = 0.5f)),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = if (copied) AresGreen else AresCyan,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = if (copied) "Copied Snippet!" else "Copy Code Snippet",
                                color = if (copied) AresGreen else AresTextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }


                    Text(
                        text = entry.codeSnippet,
                        color = AresTextPrimary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AresBackground, RoundedCornerShape(6.dp))
                            .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}
