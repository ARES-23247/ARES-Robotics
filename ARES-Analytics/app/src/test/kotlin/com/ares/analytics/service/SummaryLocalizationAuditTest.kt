package com.ares.analytics.service

import com.ares.analytics.shared.models.AnalysisDiagnostic
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.test.*

class SummaryLocalizationAuditTest {
    @Test fun `zero NIS observations are not silently excluded from the recorded mean`() = runTest {
        fixture {
            add("Vision/EKF_NIS", 0, 0.0); add("Vision/EKF_NIS", 100, 2.0)
            assertEquals(1.0, diagnostics().getValue("Diagnostics/EKF/AvgNIS"), 1e-12)
        }
    }

    @Test fun `NIS statistics reject text placeholders and nonfinite observations`() = runTest {
        fixture {
            add("Vision/EKF_NIS", 0, 2.0); add("Vision/EKF_NIS", 100, Double.POSITIVE_INFINITY)
            add("Vision/EKF_NIS", 200, Double.NaN); add("Vision/EKF_NIS", 300, 100.0, "invalid")
            assertEquals(2.0, diagnostics().getValue("Diagnostics/EKF/AvgNIS"))
        }
    }

    @Test fun `recorded NIS alone does not diagnose filter optimality or noise weighting`() = runTest {
        for (value in listOf(2.0, 0.1, 10.0)) fixture {
            add("Vision/EKF_NIS", 0, value)
            val summary = generate()
            assertTrue(summary.tags.none { it in setOf("EKFOptimal", "VisionUnderweighted", "VisionJitter", "CameraExtrinsicSkew") })
        }
    }

    @Test fun `symmetric pose disagreement has zero mean offset but nonzero magnitude`() = runTest {
        fixture {
            pose(0, 0.1, 0.0); pose(100_000, -0.1, 0.0)
            add("Vision/EKF_NIS", 0, 2.0)
            val metrics = diagnostics()
            assertEquals(0.0, metrics.getValue("Diagnostics/EKF/PoseDisagreementBiasM"), 1e-12)
            assertEquals(0.1, metrics.getValue("Diagnostics/EKF/PoseDisagreementMeanM"), 1e-12)
        }
    }

    @Test fun `pose disagreement does not depend on an unrelated NIS topic`() = runTest {
        fixture {
            pose(0, 3.0, 4.0)
            assertEquals(5.0, diagnostics().getValue("Diagnostics/EKF/PoseDisagreementMeanM"))
        }
    }

    @Test fun `different source microseconds cannot form a pose comparison`() = runTest {
        fixture {
            add("Vision/Pose_X", 100, 0.1); add("Vision/Pose_Y", 900, 0.2)
            add("Drive/Pose_X", 100, 0.0); add("Drive/Pose_Y", 100, 0.0)
            add("Vision/EKF_NIS", 0, 2.0)
            assertTrue(diagnostics().keys.none { it.contains("PoseDisagreement") || it.endsWith("ResidualBiasM") })
        }
    }

    @Test fun `inactive camera placeholders cannot become pose disagreement`() = runTest {
        fixture {
            pose(0, 1.0, 1.0); add("Vision/HasTarget", 0, 0.0); add("Vision/EKF_NIS", 0, 2.0)
            assertTrue(diagnostics().keys.none { it.contains("PoseDisagreement") || it.endsWith("ResidualBiasM") })
        }
    }

    @Test fun `canonical published cross track topic supplies path RMS`() = runTest {
        fixture {
            add("Path/Error_CrossTrack", 0, 0.03); add("Path/Error_CrossTrack", 100, -0.04)
            assertEquals(sqrt(0.00125), diagnostics().getValue("Diagnostics/Auto/CrossTrackRMSE"), 1e-12)
        }
    }

    @Test fun `path aliases cannot change the selected source statistic`() = runTest {
        fixture {
            add("Path/Error_CrossTrack", 0, 0.03); add("Path/CrossTrackError", 0, 10.0)
            assertEquals(0.03, diagnostics().getValue("Diagnostics/Auto/CrossTrackRMSE"), 1e-12)
        }
    }

    @Test fun `large finite path errors do not overflow RMS intermediates`() = runTest {
        fixture {
            add("Path/CrossTrackError", 0, 1e200); add("Path/CrossTrackError", 100, -2e200)
            assertEquals(sqrt(2.5), diagnostics().getValue("Diagnostics/Auto/CrossTrackRMSE") / 1e200, 1e-12)
        }
    }

    @Test fun `invalid path observations cannot poison valid RMS evidence`() = runTest {
        fixture {
            add("Path/CrossTrackError", 0, 0.1); add("Path/CrossTrackError", 100, Double.NaN)
            add("Path/CrossTrackError", 200, 100.0, "invalid")
            assertEquals(0.1, diagnostics().getValue("Diagnostics/Auto/CrossTrackRMSE"), 1e-12)
        }
    }

    @Test fun `inactive path samples do not enter tracking diagnostics`() = runTest {
        fixture {
            add("Path/CrossTrackError", 0, 0.2); add("Path/Active", 0, 1.0)
            add("Path/CrossTrackError", 100, 1.0); add("Path/Active", 100, 0.0)
            assertEquals(0.2, diagnostics().getValue("Diagnostics/Auto/CrossTrackRMSE"), 1e-12)
        }
    }

    @Test fun `coach reads generated diagnostics from the analysis store`() = runTest {
        fixture {
            database.replaceAnalysisDiagnostics(session.sessionId, listOf(
                AnalysisDiagnostic(session.sessionId, "Diagnostics/Auto/CrossTrackRMSE", 0.08),
                AnalysisDiagnostic(session.sessionId, "Diagnostics/Auto/MaxCrossTrackM", 0.18),
            ))
            assertTrue(coach().findings.any { it.id == "auto-path-deviation" })
        }
    }

    @Test fun `low NIS does not justify a fixed covariance tuning prescription`() = runTest {
        fixture {
            add("Diagnostics/EKF/AvgNIS", 0, 0.1); database.insertTelemetryFrames(frames)
            val finding = coach().findings.single()
            assertFalse(finding.verificationSteps.any { it.contains("30-50") || it.contains("Decrease R") })
            assertFalse(finding.thresholdContext.contains("indicates"))
        }
    }

    @Test fun `legacy residual magnitude is not diagnosed as camera extrinsic skew`() = runTest {
        fixture {
            add("Diagnostics/EKF/AvgNIS", 0, 2.0); add("Diagnostics/EKF/ResidualBiasM", 0, 0.1)
            database.insertTelemetryFrames(frames)
            val finding = coach().findings.single()
            assertFalse(finding.title.contains("Skew") || finding.title.contains("Detected"))
            assertFalse(finding.observation.contains("Systematic"))
        }
    }

    @Test fun `coach uses the latest aggregate rather than the first recorded one`() = runTest {
        fixture {
            add("Diagnostics/EKF/AvgNIS", 0, 100.0); add("Diagnostics/EKF/AvgNIS", 1_000, 3.0)
            database.insertTelemetryFrames(frames)
            val finding = coach().findings.single()
            assertEquals(DiagnosticSeverity.INFORMATION, finding.severity)
            assertTrue(finding.observation.contains("3.00"))
        }
    }

    @Test fun `a recorded path peak is useful without manufacturing a missing RMS`() = runTest {
        fixture {
            add("Diagnostics/Auto/MaxCrossTrackM", 0, 0.3); database.insertTelemetryFrames(frames)
            val finding = coach().findings.single()
            assertEquals("auto-path-deviation", finding.id)
            assertTrue(finding.observation.contains("unavailable"))
        }
    }

    @Test fun `nested diagnostic suffixes cannot impersonate aggregate metrics`() = runTest {
        fixture {
            add("Diagnostics/EKF/Tuning/AvgNIS", 0, 100.0); database.insertTelemetryFrames(frames)
            assertTrue(coach().findings.isEmpty())
        }
    }

    @Test fun `maximum finite NIS remains finite and negative values are excluded`() {
        val result = calculate(listOf(
            frame("Vision/EKF_NIS", 0, Double.MAX_VALUE),
            frame("Vision/EKF_NIS", 1, Double.MAX_VALUE), frame("Vision/EKF_NIS", 2, -1.0),
        ))
        assertEquals(Double.MAX_VALUE, result["Diagnostics/EKF/AvgNIS"])
        assertEquals(2.0, result["Diagnostics/EKF/NISSamples"])
    }

    @Test fun `invalid preferred aliases do not fall back to more convenient values`() {
        val result = calculate(listOf(
            frame("Vision/EKF_NIS", 0, Double.NaN), frame("EKF/NIS", 0, 2.0),
            frame("Path/Error_CrossTrack", 0, 0.0).copy(stringValue = "missing"),
            frame("Drive/Cross_Track", 0, 0.1),
        ))
        assertTrue(result.isEmpty())
    }

    @Test fun `source time ordering validity and session isolation precede numeric aggregation`() {
        val result = calculate(listOf(
            frame("/EKF/NIS", 0, 10.0).copy(sampleOrder = 1),
            frame("EKF/NIS", 0, Double.NaN).copy(sampleOrder = 2),
            frame("EKF/NIS", 100, 6.0).copy(sampleOrder = 3),
            frame("EKF/NIS", 100, 100.0).copy(sampleOrder = 1),
            frame("EKF/NIS", 200, 1_000.0).copy(sessionId = "another-session"),
            frame("Drive/Cross_Track", 0, -0.25),
        ).reversed())
        assertEquals(6.0, result["Diagnostics/EKF/AvgNIS"])
        assertEquals(1.0, result["Diagnostics/EKF/NISSamples"])
        assertEquals(0.25, result["Diagnostics/Auto/CrossTrackRMSE"])
    }

    @Test fun `packed simulator estimate is selected without using simulator truth`() = runTest {
        fixture {
            pose(0, 5.0, 7.0)
            add("ARES/SimulatorPoseFrame/0", 0, 5.0); add("ARES/SimulatorPoseFrame/1", 0, 7.0)
            add("ARES/SimulatorPoseFrame/3", 0, 2.0); add("ARES/SimulatorPoseFrame/4", 0, 3.0)
            assertEquals(5.0, diagnostics()["Diagnostics/EKF/PoseDisagreementMeanM"])
        }
    }

    @Test fun `estimated pose array alias supplies complete field pose comparison`() = runTest {
        fixture {
            pose(100, 4.0, 6.0)
            add("/ARES/EstimatedPose/0", 100, 1.0); add("/ARES/EstimatedPose/1", 100, 2.0)
            add("Vision/HasTarget", 100, 1.0)
            val result = diagnostics()
            assertEquals(5.0, result["Diagnostics/EKF/PoseDisagreementMeanM"])
            assertEquals(5.0, result["Diagnostics/EKF/PoseDisagreementBiasM"])
            assertEquals(1.0, result["Diagnostics/EKF/PoseDisagreementSamples"])
        }
    }

    @Test fun `incomplete preferred pose samples cannot combine estimator families`() = runTest {
        fixture {
            pose(100, 3.0, 4.0)
            add("ARES/EstimatedPose/0", 100, 0.0); add("ARES/EstimatedPose/1", 900, 0.0)
            assertTrue(diagnostics().keys.none { it.contains("PoseDisagreement") })
        }
    }

    @Test fun `unrepresentable pose differences are absent while finite norms survive`() {
        val result = calculate(listOf(
            frame("Vision/Pose_X", 0, Double.MAX_VALUE), frame("Vision/Pose_Y", 0, 0.0),
            frame("Drive/Pose_X", 0, -Double.MAX_VALUE), frame("Drive/Pose_Y", 0, 0.0),
            frame("Vision/Pose_X", 1, 3e200), frame("Vision/Pose_Y", 1, 4e200),
            frame("Drive/Pose_X", 1, 0.0), frame("Drive/Pose_Y", 1, 0.0),
        ))
        assertEquals(5.0, result.getValue("Diagnostics/EKF/PoseDisagreementMeanM") / 1e200, 1e-12)
        assertEquals(1.0, result["Diagnostics/EKF/PoseDisagreementSamples"])
        assertTrue(result.values.all { it.isFinite() })
    }

    @Test fun `scaled path RMS preserves tiny values and measured zero`() {
        for (scale in listOf(1e-200, 0.0)) {
            val result = calculate(listOf(frame("Path/Error_CrossTrack", 0, 3 * scale), frame("Path/Error_CrossTrack", 1, -4 * scale)))
            assertEquals(if (scale == 0.0) 0.0 else sqrt(12.5), result.getValue("Diagnostics/Auto/CrossTrackRMSE") / if (scale == 0.0) 1.0 else scale, 1e-12)
            assertEquals(2.0, result["Diagnostics/Auto/CrossTrackSamples"])
        }
    }

    @Test fun `present validity flags require exact source time and numeric true`() = runTest {
        fixture {
            pose(100, 1.0, 1.0); add("Vision/HasTarget", 900, 1.0)
            add("Path/Error_CrossTrack", 100, 1.0); add("Path/Active", 100, 1.0, "invalid")
            assertTrue(diagnostics().keys.none { it.contains("PoseDisagreement") || it.contains("CrossTrack") })
        }
    }

    @Test fun `generated diagnostic families supersede conflicting legacy summaries`() = runTest {
        fixture {
            add("Diagnostics/EKF/ResidualBiasM", 0, 10.0)
            add("Diagnostics/Auto/MaxCrossTrackM", 0, 10.0)
            database.insertTelemetryFrames(frames)
            database.replaceAnalysisDiagnostics(session.sessionId, listOf(
                AnalysisDiagnostic(session.sessionId, "Diagnostics/EKF/AvgNIS", 3.0),
                AnalysisDiagnostic(session.sessionId, "Diagnostics/Auto/CrossTrackRMSE", 0.08),
            ))
            val result = coach().findings
            assertEquals(setOf("ekf-recorded-nis", "auto-path-deviation"), result.map { it.id }.toSet())
            assertTrue(result.single { it.id == "auto-path-deviation" }.observation.contains("peak: unavailable"))
        }
    }

    @Test fun `invalid latest coach metrics suppress earlier numeric evidence`() {
        for (invalid in listOf(Double.NaN, -1.0)) {
            val coach = DiagnosticCoachLocalization(listOf(
                frame("Diagnostics/EKF/AvgNIS", 100, 10.0).copy(sampleOrder = 1),
                frame("Diagnostics/EKF/AvgNIS", 100, invalid).copy(sampleOrder = 2),
                frame("Diagnostics/Auto/CrossTrackRMSE", 0, 0.2),
                frame("Diagnostics/Auto/CrossTrackRMSE", 1, 1.0).copy(stringValue = "invalid"),
                frame("Diagnostics/EKF/AvgNIS", 200, 100.0).copy(sessionId = "another-session"),
            ), emptyList(), "localization-audit")
            assertNull(coach.ekfFinding()); assertNull(coach.pathFinding())
        }
        val coach = DiagnosticCoachLocalization(listOf(frame("Diagnostics/EKF/AvgNIS", 0, 5.0)),
            listOf(AnalysisDiagnostic("localization-audit", "Diagnostics/EKF/AvgNIS", 1.0, "invalid")), "localization-audit")
        assertNull(coach.ekfFinding())
    }

    @Test fun `coach reports RMS without inventing a peak or infinite display units`() {
        val coach = DiagnosticCoachLocalization(listOf(frame("Diagnostics/Auto/CrossTrackRMSE", 0, Double.MAX_VALUE)), emptyList(), "localization-audit")
        val finding = assertNotNull(coach.pathFinding())
        assertTrue(finding.observation.contains("peak: unavailable"))
        assertFalse(finding.observation.contains("Infinity"))
    }

    private fun frame(key: String, timeUs: Long, value: Double) = TelemetryFrame(timeUs / 1_000, "localization-audit", key, value, timestampUs = timeUs)
    private fun calculate(frames: List<TelemetryFrame>) = SummaryLocalizationDiagnostics(frames, "localization-audit").calculate()

    private class Fixture(val database: DatabaseService) {
        val session = Session("localization-audit", "team", "season", "robot", 0)
        val frames = mutableListOf<TelemetryFrame>()
        fun add(key: String, timeUs: Long, value: Double, text: String? = null) {
            frames += TelemetryFrame(timeUs / 1_000, session.sessionId, key, value, text, timeUs)
        }
        fun pose(time: Long, x: Double, y: Double) {
            add("Vision/Pose_X", time, x); add("Vision/Pose_Y", time, y)
            add("Drive/Pose_X", time, 0.0); add("Drive/Pose_Y", time, 0.0)
        }
        suspend fun generate(): com.ares.analytics.shared.models.SessionSummary {
            database.insertTelemetryFrames(frames)
            val sysId = SysIdService(database)
            return SummaryEngineService(database, sysId, DriverAnalysisService(database, sysId)).generateSummary(session)
        }
        suspend fun diagnostics(): Map<String, Double> {
            generate()
            return database.getAnalysisDiagnostics(session.sessionId).associate { it.key to it.value }
        }
        suspend fun coach() = DiagnosticCoachService(database).analyze(session.sessionId)
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("ares-localization-audit").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        try { Fixture(database).block() } finally { database.close(); directory.deleteRecursively() }
    }
}
