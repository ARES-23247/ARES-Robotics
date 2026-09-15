package com.ares.analytics.service

import com.ares.analytics.service.tuning.*
import com.areslib.control.assist.SysIdMechanism
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.*
import kotlin.test.*

class AutoTunerProposalAuditTest {
    private fun smoothRun(): List<AlignedDataRow> = List(400) { i ->
        val t = i * 0.02
        val velocity = 2 * sin(2 * t) + 0.3 * sin(5 * t)
        val accel = 4 * cos(2 * t) + 1.5 * cos(5 * t)
        AlignedDataRow(i * 20L, 0.2 * sign(velocity) + 1.8 * velocity + 0.25 * accel, velocity, accel)
    }
    private fun test(block: suspend (AutoTunerService, TuningProposalInbox, Nt4ClientService) -> Unit) = runTest {
        val file = File.createTempFile("tuner-proposal-audit", ".duckdb")
        val db = DatabaseService(file.path)
        val client = Nt4ClientService(db)
        val inbox = TuningProposalInbox()
        try { block(AutoTunerService(client, SysIdService(db), inbox), inbox, client) }
        finally { client.stop(); db.close(); file.delete() }
    }
    @Test fun `identifiable smooth excitation permits drivetrain feedforward without a step`() = test { tuner, inbox, client ->
        for (mechanism in listOf(SysIdMechanism.LINEAR, SysIdMechanism.ANGULAR)) {
            val rec = assertNotNull(tuner.analyzeSamples(mechanism, smoothRun()))
            assertFalse(rec.stepMetrics.isUsable)
            assertEquals(1.8, rec.recommendedkV, 1e-10)
            assertEquals(0.25, rec.recommendedkA, 1e-10)
            assertEquals(RecommendationQuality.READY, rec.quality)
            assertEquals(0.75 * rec.rSquared + 0.25 * rec.dataQuality.score, rec.confidence, 1e-12)
            tuner.approveAndApplyGains(rec)
            assertEquals(TuningApplyPhase.RECOMMENDED, tuner.applyState.value.phase)
            assertTrue(inbox.deliverNext { assertEquals(rec.topicValues, it.values); assertEquals(3, it.values.size); true })
        }
        assertTrue(client.latestValues.isEmpty())
    }
    @Test fun `flywheel still requires an identified feedback model`() = test { tuner, inbox, _ ->
        val rec = assertNotNull(tuner.analyzeSamples(SysIdMechanism.FLYWHEEL, smoothRun()))
        assertTrue(rec.dataQuality.passed); assertTrue(rec.rSquared > 0.99)
        assertEquals(RecommendationQuality.REJECTED, rec.quality)
        tuner.approveAndApplyGains(rec)
        assertEquals(TuningApplyPhase.FAILED, tuner.applyState.value.phase); assertEquals(0, inbox.pendingCount.value)
    }
    @Test fun `unused diagnostic feedback gains cannot reject valid drivetrain coefficients`() = test { tuner, inbox, _ ->
        val rec = assertNotNull(tuner.analyzeSamples(SysIdMechanism.LINEAR, smoothRun()))
        tuner.approveAndApplyGains(rec.copy(recommendedGains=AutoTunerPIDFGains(100.0, 200.0, 0.0)))
        assertEquals(TuningApplyPhase.RECOMMENDED, tuner.applyState.value.phase)
        assertTrue(inbox.deliverNext { assertEquals(rec.topicValues, it.values); true })
    }
    @Test fun `a full inbox is a failed submission and can be retried after review`() = test { tuner, inbox, _ ->
        val rec = assertNotNull(tuner.analyzeSamples(SysIdMechanism.LINEAR, smoothRun()))
        repeat(TuningProposalInbox.CAPACITY) { assertTrue(inbox.submit(ExternalTuningProposal("test", "pending", mapOf("key" to it.toDouble())))) }
        tuner.approveAndApplyGains(rec)
        assertEquals(TuningApplyPhase.FAILED, tuner.applyState.value.phase)
        assertTrue(tuner.applyState.value.message.contains("full"))
        assertEquals(TuningProposalInbox.CAPACITY, inbox.pendingCount.value)
        assertTrue(inbox.deliverNext { true })
        tuner.approveAndApplyGains(rec)
        assertEquals(TuningApplyPhase.RECOMMENDED, tuner.applyState.value.phase)
    }
    @Test fun `flywheel feedback coefficients still recheck the canonical envelope`() = test { tuner, inbox, _ ->
        val scenario = AutoTuningDigitalTwin.teachingScenario(SysIdMechanism.FLYWHEEL)
        val rec = assertNotNull(tuner.analyzeSamples(SysIdMechanism.FLYWHEEL, AutoTuningDigitalTwin().generateSamples(scenario), "test-fixture"))
        assertNotEquals(RecommendationQuality.REJECTED, rec.quality)
        val invalidKP = rec.safetyEnvelope.maxKP + 1.0
        tuner.approveAndApplyGains(rec.copy(recommendedGains=rec.recommendedGains.copy(kP=invalidKP),
            topicValues=rec.topicValues + (TuningParameterKeys.FLYWHEEL_VELOCITY_KP to invalidKP)))
        assertEquals(TuningApplyPhase.FAILED, tuner.applyState.value.phase)
        assertEquals(0, inbox.pendingCount.value)
    }
    @Test fun `nonfinite and poor fits are still refused without requiring feedback`() = test { tuner, inbox, _ ->
        val rec = assertNotNull(tuner.analyzeSamples(SysIdMechanism.LINEAR, smoothRun()))
        for (r2 in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.44)) {
            tuner.approveAndApplyGains(rec.copy(rSquared=r2))
            assertEquals(TuningApplyPhase.FAILED, tuner.applyState.value.phase)
        }
        assertEquals(0, inbox.pendingCount.value)
    }
}
