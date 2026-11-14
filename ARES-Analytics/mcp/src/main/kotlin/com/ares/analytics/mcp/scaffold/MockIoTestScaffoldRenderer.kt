package com.ares.analytics.mcp.scaffold

/** Renders deterministic fake-IO unit-test scaffolds using `com.areslib.util.RobotClock`. */
object MockIoTestScaffoldRenderer {
    fun renderUnitTest(
        packageName: String,
        className: String,
        subjectClassName: String,
    ): String = """
        |// ARES OWNERSHIP: USER-OWNED
        |package $packageName
        |
        |import com.areslib.util.RobotClock
        |import kotlin.test.Test
        |import kotlin.test.assertEquals
        |
        |/**
        | * Deterministic fake-IO unit test template for $subjectClassName.
        | * Verifies configure -> enable -> bounded output -> stale feedback -> disable neutralization.
        | */
        |class $className {
        |    private var lastAppliedOutput: Double = Double.NaN
        |    private var measuredPosition: Double = 0.0
        |
        |    @Test
        |    fun disablesAndStaleFeedbackFailClosedToZeroOutput() {
        |        RobotClock.useMockTime(1_000L)
        |        try {
        |            val configured = true
        |            var enabled = true
        |            var lastFeedbackMillis = RobotClock.currentTimeMillis()
        |            fun step(target: Double) {
        |                val fresh = (RobotClock.currentTimeMillis() - lastFeedbackMillis) <= 250L
        |                if (!configured || !enabled || !fresh || !target.isFinite()) {
        |                    lastAppliedOutput = 0.0
        |                    return
        |                }
        |                lastAppliedOutput = (target - measuredPosition).coerceIn(-1.0, 1.0)
        |            }
        |
        |            step(0.5)
        |            assertEquals(0.5, lastAppliedOutput, 1e-9)
        |
        |            RobotClock.useMockTime(2_000L)
        |            step(0.5)
        |            assertEquals(0.0, lastAppliedOutput, 1e-9)
        |
        |            lastFeedbackMillis = RobotClock.currentTimeMillis()
        |            enabled = false
        |            step(0.5)
        |            assertEquals(0.0, lastAppliedOutput, 1e-9)
        |        } finally {
        |            RobotClock.useSystemTime()
        |        }
        |    }
        |}
        |
    """.trimMargin()
}
