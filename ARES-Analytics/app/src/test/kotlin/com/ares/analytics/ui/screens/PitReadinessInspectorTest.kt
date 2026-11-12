package com.ares.analytics.ui.screens

import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PitReadinessInspectorTest {

    private fun frame(key: String, value: Double = 0.0, text: String? = null) =
        TelemetryFrame(timestampMs = 100L, sessionId = "live", key = key, value = value, stringValue = text)

    @Test
    fun `loadProjectProfile falls back cleanly when workspace has no ares project directory`() = runTest {
        val workspace = WorkspaceConfig(
            id = "w1",
            teamId = "23247",
            seasonId = "2026",
            robotId = "frc-bot",
            robotName = "Pit Log Viewer",
            projectPath = "",
            league = League.FRC,
        )
        val profile = PitReadinessInspector.loadProjectProfile(workspace)
        assertFalse(profile.hasAresProject)
        assertEquals(League.FRC, profile.league)
    }

    @Test
    fun `evaluateProjectAwarePitReadiness inspects configured drivebase subsystems loop timing and league battery floor`() {
        val profile = PitProjectSignalProfile(
            league = League.FTC,
            hasAresProject = true,
            projectLabel = ".ares Project: Mecanum · 2 subsystem(s) · 3 routine(s)",
            drivebaseKind = DrivebaseKind.FTC_MECANUM,
            drivebaseName = "Championship Mecanum",
            driveDevices = listOf(
                PitDriveDeviceSpec("fl_motor", "Front Left", "FRONT_LEFT_MOTOR", "frontLeft", "FTC hardware-map name: frontLeft"),
                PitDriveDeviceSpec("fr_motor", "Front Right", "FRONT_RIGHT_MOTOR", "frontRight", "FTC hardware-map name: frontRight"),
            ),
            subsystems = listOf(
                PitSubsystemSpec("lift", "Linear Lift", listOf("liftLeader"), listOf("heightMeters")),
                PitSubsystemSpec("intake", "Active Intake", listOf("intakeRoller"), listOf("beamBreak")),
            ),
            routineCount = 3,
            autonomousCatalogPresent = true,
            descriptorErrors = emptyList(),
        )

        // Disconnected state never reports observed hardware.
        val disconnected = PitReadinessInspector.evaluateProjectAwarePitReadiness(
            connected = false,
            frames = emptyMap(),
            heartbeatAgeMs = null,
            profile = profile,
        )
        assertTrue(disconnected.all { it.status == SelfTestStatus.WAITING })

        // Stale heartbeat blocks stale observations.
        val stale = PitReadinessInspector.evaluateProjectAwarePitReadiness(
            connected = true,
            frames = mapOf("Robot/BatteryVoltage" to frame("Robot/BatteryVoltage", 13.1)),
            heartbeatAgeMs = HARDWARE_READINESS_HEARTBEAT_TIMEOUT_MS + 50L,
            profile = profile,
        )
        assertTrue(stale.all { it.status == SelfTestStatus.WAITING })

        // Fresh heartbeat evaluates dynamic .ares checks accurately.
        val liveFrames = listOf(
            frame("Robot/BatteryVoltage", 11.7), // Below 12.0 V FTC pit pre-match floor -> WARNING
            frame("ARES/Telemetry/LoopTimeMs", 9.4),
            frame("Hardware/Motors/frontLeft/CurrentAmps", 1.1),
            frame("Subsystems/lift/heightMeters", 0.42),
            frame("ARES/EstimatedPose/0", 0.1),
            frame("ARES/EstimatedPose/1", 0.2),
            frame("ARES/EstimatedPose/2", 0.0),
            frame("Vision/Limelight/FPS", 29.8),
        ).associateBy { it.key }

        val byId = PitReadinessInspector.evaluateProjectAwarePitReadiness(
            connected = true,
            frames = liveFrames,
            heartbeatAgeMs = 40L,
            profile = profile,
        ).associateBy { it.id }

        assertEquals(SelfTestStatus.WARNING, byId.getValue("battery").status)
        assertEquals(SelfTestStatus.OBSERVED, byId.getValue("loop_timing").status)
        assertEquals(SelfTestStatus.OBSERVED, byId.getValue("drive_fl_motor").status)
        assertEquals(SelfTestStatus.WAITING, byId.getValue("drive_fr_motor").status)
        assertEquals(SelfTestStatus.OBSERVED, byId.getValue("subsystem_lift").status)
        assertEquals(SelfTestStatus.WAITING, byId.getValue("subsystem_intake").status)
        assertEquals(SelfTestStatus.OBSERVED, byId.getValue("localization").status)
        assertEquals(SelfTestStatus.OBSERVED, byId.getValue("vision").status)
        assertEquals(SelfTestStatus.OBSERVED, byId.getValue("ares_project_descriptors").status)
    }

    @Test
    fun `buildPitReadinessMarkdownReceipt separates passive NT4 signals from human sign-offs and preserves safety disclaimer`() {
        val profile = PitProjectSignalProfile.leagueFallback(League.FRC)
        val steps = PitReadinessInspector.evaluateProjectAwarePitReadiness(
            connected = false,
            frames = emptyMap(),
            heartbeatAgeMs = null,
            profile = profile,
        )
        val checklist = PitReadinessInspector.defaultManualChecklist(League.FRC).mapIndexed { index, item ->
            if (index < 4) item.copy(checked = true) else item
        }
        val tag = PitMatchTag(
            matchType = PitMatchType.QUALIFICATION,
            matchNumber = "14",
            alliance = PitAllianceColor.BLUE,
            inspectorInitials = "DK",
        )
        val receipt = PitReadinessInspector.buildPitReadinessMarkdownReceipt(
            workspace = WorkspaceConfig(
                id = "w",
                teamId = "23247",
                seasonId = "2026",
                robotId = "hyperion",
                robotName = "Hyperion",
                projectPath = "",
                league = League.FRC,
            ),
            profile = profile,
            matchTag = tag,
            connected = false,
            streamLive = false,
            signalSteps = steps,
            manualChecklist = checklist,
            generatedAtEpochMs = 1_762_000_000_000L,
        )

        assertTrue(receipt.contains("Qual #14 · BLUE"))
        assertTrue(receipt.contains("No actuators were pulsed"))
        assertTrue(receipt.contains("4/6 Signed Off"))
        assertTrue(receipt.contains("- [x] **Fresh battery strapped & main power seated**"))
        assertTrue(receipt.contains("- [ ] **Starting field pose & preload verified**"))
    }
}
