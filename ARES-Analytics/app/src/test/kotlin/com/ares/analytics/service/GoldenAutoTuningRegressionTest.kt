package com.ares.analytics.service

import com.areslib.control.assist.SysIdMechanism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class GoldenAutoTuningRegressionTest {
    private lateinit var autoTuner: AutoTunerService

    @Before
    fun setUp() {
        val database = DatabaseService(File.createTempFile("golden_auto_tuning", ".duckdb").apply { deleteOnExit() }.absolutePath)
        autoTuner = AutoTunerService(Nt4ClientService(database), SysIdService(database))
    }

    @Test
    fun `versioned linear csv preserves the approved recommendation`() {
        val recommendation = analyzeGolden("autotuning/golden-linear-v2.csv", SysIdMechanism.LINEAR)

        assertRecommendation(recommendation, expectedKS = 0.4, expectedKV = 1.6, expectedKA = 0.32)
        assertTrue(recommendation.dataQuality.blockers.isEmpty())
        assertTrue(recommendation.dataQuality.sampleCount >= 100)
    }

    @Test
    fun `versioned flywheel jsonl preserves the approved recommendation`() {
        val recommendation = analyzeGolden("autotuning/golden-flywheel-v2.jsonl", SysIdMechanism.FLYWHEEL)

        assertRecommendation(recommendation, expectedKS = 0.25, expectedKV = 0.045, expectedKA = 0.012)
        assertTrue(recommendation.dataQuality.blockers.isEmpty())
        assertTrue(recommendation.dataQuality.sampleCount >= 100)
    }

    private fun assertRecommendation(
        recommendation: AutoTunerService.TuningRecommendation,
        expectedKS: Double,
        expectedKV: Double,
        expectedKA: Double
    ) {
        assertNotEquals(RecommendationQuality.REJECTED, recommendation.quality)
        assertTrue(recommendation.rSquared > 0.999999)
        assertEquals(expectedKS, recommendation.recommendedkS, 1e-6)
        assertEquals(expectedKV, recommendation.recommendedkV, 1e-6)
        assertEquals(expectedKA, recommendation.recommendedkA, 1e-6)
        assertEquals(6, recommendation.topicValues.size)
        assertTrue(recommendation.topicValues.keys.all { !it.startsWith('/') })
        assertTrue(recommendation.safetyEnvelope.violations(
            recommendation.recommendedkS,
            recommendation.recommendedkV,
            recommendation.recommendedkA,
            recommendation.recommendedGains
        ).isEmpty())
    }

    private fun analyzeGolden(
        resourcePath: String,
        mechanism: SysIdMechanism
    ): AutoTunerService.TuningRecommendation {
        val contents = requireNotNull(javaClass.classLoader.getResourceAsStream(resourcePath)) {
            "Missing golden fixture $resourcePath"
        }.bufferedReader().use { it.readText() }
        val suffix = resourcePath.substringAfterLast('.', "log")
        val file = File.createTempFile("golden-auto-tuning-", ".$suffix").apply {
            deleteOnExit()
            writeText(contents)
        }
        return requireNotNull(autoTuner.analyzeLogFile(file, mechanism))
    }
}
