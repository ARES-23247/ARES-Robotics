package com.ares.analytics.ui.help

import com.ares.analytics.service.ComparisonClaimKind
import com.ares.analytics.service.GuidedComparisonFinding
import com.ares.analytics.service.RunComparisonEvidenceLink
import com.ares.analytics.service.tuning.ExperimentDirection
import com.ares.analytics.service.tuning.ExperimentMetricGoal
import com.ares.analytics.service.tuning.ExperimentMetricOption
import com.ares.analytics.service.tuning.ExperimentMetricStatistic
import com.ares.analytics.service.tuning.GuidedTuningExperimentSeed
import com.ares.analytics.service.tuning.ResolvedTuningValue
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.ui.components.dashboard.ReplayDiagnosticKind
import com.ares.analytics.ui.components.dashboard.buildReplayDiagnosticBookmarks
import com.ares.analytics.viewmodel.FieldEditorState
import com.ares.analytics.viewmodel.field.StudentPracticeFieldPreset
import com.ares.analytics.viewmodel.field.applyStudentPracticeFieldPreset
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentState
import com.ares.analytics.viewmodel.tuning.StudentSymptomExperimentStarter
import com.ares.analytics.viewmodel.tuning.applySymptomStarterToExperimentState
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Wave5TuningFieldReplayAndMaintainabilityTest {

    @Test
    fun `applySymptomStarterToExperimentState pre-fills question hypothesis direction parameter and metric without auto-starting capture`() {
        val kpDeclaration = TuningParameterDeclaration(
            uid = "arm.kp",
            componentUid = "arm",
            key = "Tuning/Arm/kP",
            displayName = "Arm kP",
            description = "Arm proportional gain",
            type = TuningParameterType.DOUBLE,
            defaultValue = TuningValue(doubleValue = 2.0),
            minimum = 0.0,
            maximum = 20.0,
            unit = "V/rad",
            applyPolicy = TuningApplyPolicy.LIVE_SAFE,
        )
        val kdDeclaration = TuningParameterDeclaration(
            uid = "arm.kd",
            componentUid = "arm",
            key = "Tuning/Arm/kD",
            displayName = "Arm kD",
            description = "Arm derivative gain",
            type = TuningParameterType.DOUBLE,
            defaultValue = TuningValue(doubleValue = 0.1),
            minimum = 0.0,
            maximum = 5.0,
            unit = "V/(rad/s)",
            applyPolicy = TuningApplyPolicy.LIVE_SAFE,
        )
        val kgDeclaration = TuningParameterDeclaration(
            uid = "arm.kg",
            componentUid = "arm",
            key = "Tuning/Arm/kG",
            displayName = "Arm kG",
            description = "Arm gravity feedforward",
            type = TuningParameterType.DOUBLE,
            defaultValue = TuningValue(doubleValue = 0.4),
            minimum = 0.0,
            maximum = 3.0,
            unit = "V",
            applyPolicy = TuningApplyPolicy.LIVE_SAFE,
        )
        val kvDeclaration = TuningParameterDeclaration(
            uid = "flywheel.kv",
            componentUid = "flywheel",
            key = "Tuning/Flywheel/kV",
            displayName = "Flywheel kV",
            description = "Flywheel velocity feedforward",
            type = TuningParameterType.DOUBLE,
            defaultValue = TuningValue(doubleValue = 0.12),
            minimum = 0.0,
            maximum = 1.0,
            unit = "V/(rad/s)",
            applyPolicy = TuningApplyPolicy.LIVE_SAFE,
        )
        val editableRows = listOf(kpDeclaration, kdDeclaration, kgDeclaration, kvDeclaration).map { decl ->
            ResolvedTuningValue(
                declaration = decl,
                sourceValue = decl.defaultValue.doubleValue,
                sourceTypedValue = decl.defaultValue,
                sourceProfileId = null,
                liveValue = null,
                liveTypedValue = null,
                proposedTypedValue = null,
                provenance = null,
                status = com.ares.analytics.service.tuning.TuningValueStatus.DEFAULT,
            )
        }
        val initialState = GuidedTuningExperimentState(
            seed = GuidedTuningExperimentSeed(
                finding = GuidedComparisonFinding(
                    id = "finding-1",
                    kind = ComparisonClaimKind.OBSERVATION,
                    title = "Tracking lag observed",
                    explanation = "Mechanism lagged behind target during step.",
                    evidence = RunComparisonEvidenceLink("session-base", 1000L, 500L, listOf("Subsystems/Arm/Error")),
                ),
                baselineSessionId = "session-base",
                availableMetrics = listOf(
                    ExperimentMetricOption(
                        id = "mechanism_tracking_error",
                        label = "Mechanism tracking error",
                        unit = "rad",
                        statistic = ExperimentMetricStatistic.P95,
                        goal = ExperimentMetricGoal.LOWER_IS_BETTER,
                    ),
                ),
            ),
        )

        val sluggishState = applySymptomStarterToExperimentState(
            state = initialState,
            starter = StudentSymptomExperimentStarter.STOPS_SHORT_SLUGGISH,
            editableRows = editableRows,
        )
        assertEquals("arm.kp", sluggishState.parameterUid)
        assertEquals(ExperimentDirection.INCREASE, sluggishState.direction)
        assertEquals("mechanism_tracking_error", sluggishState.metricId)
        assertTrue(sluggishState.question.isNotBlank())
        assertTrue(sluggishState.hypothesis.isNotBlank())
        assertNotNull(sluggishState.proposalPreview)
        assertNull(sluggishState.experiment, "Starter must not auto-create or start capture before student confirms")

        val oscillateState = applySymptomStarterToExperimentState(
            state = initialState,
            starter = StudentSymptomExperimentStarter.OSCILLATES_AROUND_TARGET,
            editableRows = editableRows,
        )
        assertEquals("arm.kd", oscillateState.parameterUid)
        assertEquals(ExperimentDirection.INCREASE, oscillateState.direction)

        val droopState = applySymptomStarterToExperimentState(
            state = initialState,
            starter = StudentSymptomExperimentStarter.ARM_ELEVATOR_GRAVITY_DROOP,
            editableRows = editableRows,
        )
        assertEquals("arm.kg", droopState.parameterUid)
        assertEquals(ExperimentDirection.INCREASE, droopState.direction)

        val flywheelState = applySymptomStarterToExperimentState(
            state = initialState,
            starter = StudentSymptomExperimentStarter.SLOW_FLYWHEEL_RECOVERY,
            editableRows = editableRows,
        )
        assertEquals("flywheel.kv", flywheelState.parameterUid)
        assertEquals(ExperimentDirection.INCREASE, flywheelState.direction)
    }

    @Test
    fun `applyStudentPracticeFieldPreset builds Vision Calibration Slalom and Clear Floor layouts across leagues`() {
        val baseState = FieldEditorState()

        val visionFtc = applyStudentPracticeFieldPreset(
            state = baseState,
            preset = StudentPracticeFieldPreset.VISION_CALIBRATION_TARGET_1M,
            league = League.FTC,
        )
        assertEquals(1, visionFtc.aprilTags.size)
        assertTrue(visionFtc.fieldWaypoints.isNotEmpty())
        val startWaypoint = visionFtc.fieldWaypoints.first()
        val tag = visionFtc.aprilTags.first()
        assertEquals(1.0, tag.x - startWaypoint.x, 1e-3)
        assertEquals(0.0, tag.y - startWaypoint.y, 1e-3)

        val slalomFrc = applyStudentPracticeFieldPreset(
            state = baseState,
            preset = StudentPracticeFieldPreset.THREE_CONE_SLALOM_COURSE,
            league = League.FRC,
        )
        assertEquals(3, slalomFrc.obstacles.size)
        assertEquals(4, slalomFrc.fieldWaypoints.size)

        val clearXrp = applyStudentPracticeFieldPreset(
            state = slalomFrc,
            preset = StudentPracticeFieldPreset.CLEAR_PRACTICE_FLOOR,
            league = League.XRP,
        )
        assertTrue(clearXrp.obstacles.isEmpty())
        assertTrue(clearXrp.gamePieces.isEmpty())
        assertTrue(clearXrp.aprilTags.isEmpty())
        assertTrue(clearXrp.fieldWaypoints.isNotEmpty())
    }

    @Test
    fun `buildReplayDiagnosticBookmarks identifies voltage sag loop overruns tracking spikes and alert triggers`() {
        val frames = listOf(
            TelemetryFrame(timestampMs = 1_000L, sessionId = "s1", key = "Hardware/BatteryVoltage", value = 12.4),
            TelemetryFrame(timestampMs = 2_200L, sessionId = "s1", key = "Hardware/BatteryVoltage", value = 9.4),
            TelemetryFrame(timestampMs = 3_500L, sessionId = "s1", key = "LoopTimeMs", value = 31.5),
            TelemetryFrame(timestampMs = 4_800L, sessionId = "s1", key = "Drive/TrackingError", value = 0.28),
        )
        val alerts = listOf(
            AlertRecord(
                alertId = "a1",
                sessionId = "s1",
                ruleKey = "/Diagnostics/MotorStall/intake",
                triggerTimestampMs = 6_000L,
                resolveTimestampMs = 6_600L,
                durationMs = 600L,
                peakValue = 45.0,
                triaged = false,
            ),
        )

        val bookmarks = buildReplayDiagnosticBookmarks(
            sessionId = "s1",
            league = League.FTC,
            frames = frames,
            alerts = alerts,
        )

        assertTrue(bookmarks.any { it.kind == ReplayDiagnosticKind.VOLTAGE_SAG && it.timestampMs == 2_200L })
        assertTrue(bookmarks.any { it.kind == ReplayDiagnosticKind.LOOP_OVERRUN && it.timestampMs == 3_500L })
        assertTrue(bookmarks.any { it.kind == ReplayDiagnosticKind.TRACKING_SPIKE && it.timestampMs == 4_800L })
        assertTrue(bookmarks.any { it.kind == ReplayDiagnosticKind.ALERT_TRIGGER && it.timestampMs == 6_000L })
    }

    @Test
    fun `all production Kotlin files in ARES-Analytics are 750 lines or fewer`() {
        val srcRoot = sequenceOf(
            File("src/main/kotlin"),
            File("app/src/main/kotlin"),
            File("ARES-Analytics/app/src/main/kotlin"),
        ).firstOrNull { it.isDirectory }
        assertNotNull(srcRoot, "Could not locate app/src/main/kotlin directory")

        val oversized = srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val count = file.useLines { it.count() }
                if (count > 750) "${file.relativeTo(srcRoot).invariantSeparatorsPath} ($count lines)" else null
            }
            .toList()

        assertTrue(
            oversized.isEmpty(),
            "Expected all production Kotlin files to be <= 750 lines, but found:\n" + oversized.joinToString("\n"),
        )
    }
}
