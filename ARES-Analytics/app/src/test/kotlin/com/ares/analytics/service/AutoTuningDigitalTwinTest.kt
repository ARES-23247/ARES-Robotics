package com.ares.analytics.service

import com.areslib.control.assist.SysIdMechanism
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class AutoTuningDigitalTwinTest {
    private lateinit var autoTuner: AutoTunerService
    private lateinit var nt4: Nt4ClientService
    private lateinit var database: DatabaseService
    private lateinit var databaseFile: File
    private val digitalTwin = AutoTuningDigitalTwin()

    @Test
    fun `every novice teaching scenario is deterministic bounded and clearly simulated`() {
        SysIdMechanism.entries.forEach { mechanism ->
            val scenario = AutoTuningDigitalTwin.teachingScenario(mechanism)
            val first = digitalTwin.generateSamples(scenario)
            val second = digitalTwin.generateSamples(scenario)

            assertEquals(first, second)
            assertEquals(mechanism, scenario.plant.mechanism)
            assertTrue(scenario.name.startsWith("teaching-"))
            assertTrue(first.size >= 40)
            assertTrue(first.all { it.voltage.isFinite() && it.velocity.isFinite() && it.accel.isFinite() })
        }
    }

    @Before
    fun setUp() {
        databaseFile = File.createTempFile("auto_tuning_twin", ".duckdb")
        database = DatabaseService(databaseFile.absolutePath)
        nt4 = Nt4ClientService(database)
        autoTuner = AutoTunerService(nt4, SysIdService(database))
    }

    @After
    fun tearDown() = runBlocking { nt4.stop(); database.close(); databaseFile.delete(); Unit }

    @Test
    fun `digital twin generation is deterministic`() {
        val scenario = AutoTuningDigitalTwin.standardMonteCarloSuite(23247, 1).single()
        assertEquals(digitalTwin.generateSamples(scenario), digitalTwin.generateSamples(scenario))
    }

    @Test
    fun `monte carlo recovers varied plants without leaking unsafe recommendations`() {
        val summary = digitalTwin.runMonteCarlo(
            AutoTuningDigitalTwin.standardMonteCarloSuite(seed = 2026, cases = 36),
            autoTuner::analyzeSamples
        )
        assertEquals(36, summary.cases)
        assertTrue(summary.recommendationsProduced >= 34)
        val supported = summary.evaluations.filter { it.scenario.plant.mechanism in
            setOf(SysIdMechanism.LINEAR, SysIdMechanism.ANGULAR, SysIdMechanism.FLYWHEEL) }
        val unsupported = summary.evaluations - supported.toSet()
        assertEquals(18, supported.size)
        assertTrue(unsupported.all { it.recommendation?.quality == RecommendationQuality.REJECTED && it.recommendation.topicValues.isEmpty() })
        // Preserve the previous 28/36 acceptance threshold within the mechanisms with a valid proposal contract.
        assertTrue("Reviewable=${summary.readyOrReviewable}; rejected=" + supported.filter { it.recommendation?.quality == RecommendationQuality.REJECTED }.map { it.scenario.name to it.recommendation?.stepMetrics }, summary.readyOrReviewable >= supported.size * 28 / 36)
        assertTrue(summary.recoveredWithinTolerance >= 28)
        assertTrue(summary.stableClosedLoops >= 24)
        assertEquals(0, summary.unsafeRecommendations)
    }

    @Test
    fun `preflight blocks sparse gapped and one direction drivetrain data`() {
        val rows = List(35) { index ->
            AlignedDataRow(index * 500L, 2.0 + index * 0.01, 0.2 + index * 0.01, 0.0)
        }

        val recommendation = autoTuner.analyzeSamples(SysIdMechanism.LINEAR, rows)

        assertNotNull(recommendation)
        assertEquals(RecommendationQuality.REJECTED, recommendation!!.quality)
        assertTrue(recommendation.dataQuality.blockers.any { it.contains("both directions") })
        assertTrue(recommendation.dataQuality.blockers.any { it.contains("telemetry period") })
    }

    @Test
    fun `apply rechecks the mechanism safety envelope`() = runBlocking {
        val scenario = AutoTuningDigitalTwin.standardMonteCarloSuite(seed = 7, cases = 1).single()
        val recommendation = autoTuner.analyzeSamples(
            scenario.plant.mechanism,
            digitalTwin.generateSamples(scenario)
        )!!
        val tampered = recommendation.copy(
            recommendedGains = recommendation.recommendedGains.copy(kP = recommendation.safetyEnvelope.maxKP + 1.0),
            quality = RecommendationQuality.READY
        )

        autoTuner.approveAndApplyGains(tampered)

        assertEquals(TuningApplyPhase.FAILED, autoTuner.applyState.value.phase)
        assertTrue(nt4.latestValues.keys.none { it.startsWith("Tuning/") })
    }
}
