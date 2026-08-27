package com.ares.analytics.validation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DashboardPerformanceBaselineTest {
    @Test
    fun `smoke report stays within checked in performance baseline`() {
        val reportDirectoryProperty = System.getProperty("ares.validation.reportDir")
        val baselineFileProperty = System.getProperty("ares.validation.baselineFile")
        assumeTrue(
            "Run :app:dashboardPerformanceBaseline to provide the smoke report and baseline.",
            reportDirectoryProperty != null && baselineFileProperty != null
        )
        val reportDirectory = File(requireNotNull(reportDirectoryProperty))
        val baselineFile = File(requireNotNull(baselineFileProperty))
        val reportFile = reportDirectory.resolve("dashboard-validation-smoke.json")
        require(reportFile.isFile) { "Smoke report was not generated at ${reportFile.absolutePath}" }
        require(baselineFile.isFile) { "Performance baseline is missing at ${baselineFile.absolutePath}" }

        val baseline = JSON.decodeFromString<PerformanceBaseline>(baselineFile.readText())
        val report = JSON.parseToJsonElement(reportFile.readText()).jsonObject
        val currentMetrics = report.getValue("metrics").jsonObject
        val failures = baseline.metrics.mapNotNull { (name, budget) ->
            val current = currentMetrics[name]?.jsonPrimitive?.double
                ?: return@mapNotNull "$name was absent from the smoke report"
            val acceptable = when (budget.direction) {
                "higher" -> current >= budget.baseline * (1.0 - budget.allowedRegressionFraction)
                "lower" -> current <= budget.baseline * (1.0 + budget.allowedRegressionFraction)
                else -> error("Unknown performance direction ${budget.direction}")
            }
            if (acceptable) null else "$name=$current regressed beyond baseline=${budget.baseline} (allowance=${budget.allowedRegressionFraction})"
        }
        assertTrue(failures.isEmpty(), failures.joinToString(prefix = "Performance baseline failures:\n- ", separator = "\n- "))
    }

    @Serializable
    private data class PerformanceBaseline(
        val profile: String,
        val metrics: Map<String, MetricBudget>
    )

    @Serializable
    private data class MetricBudget(
        val direction: String,
        val baseline: Double,
        val allowedRegressionFraction: Double
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
