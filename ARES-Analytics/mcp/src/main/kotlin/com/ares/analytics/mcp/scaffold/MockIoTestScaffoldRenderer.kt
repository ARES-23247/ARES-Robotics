package com.ares.analytics.mcp.scaffold

/** Renders deterministic fake-IO unit-test scaffolds using `com.areslib.util.RobotClock`. */
object MockIoTestScaffoldRenderer {
    fun renderUnitTest(
        packageName: String,
        className: String,
        subjectClassName: String,
        ftc: Boolean,
    ): String = """
        |// ARES OWNERSHIP: USER-OWNED
        |package $packageName
        |
        |import com.areslib.util.RobotClock
        |import ${if (ftc) "org.junit.Test" else "org.junit.jupiter.api.Test"}
        |import ${if (ftc) "org.junit.Assert.assertEquals" else "org.junit.jupiter.api.Assertions.assertEquals"}
        |
        |class $className {
        |    @Test
        |    fun disablesAndStaleFeedbackFailClosedToZeroOutput() {
        |        var lastOutput = Double.NaN
        |        val adapter = $subjectClassName({ lastOutput = it }, { 0.0 })
        |        RobotClock.useMockTime(1_000L)
        |        try {
        |            adapter.configure()
        |            adapter.readInputs()
        |            adapter.enable()
        |            adapter.${if (ftc) "setTargetPower" else "setTargetVoltage"}(0.5)
        |            adapter.writeOutputs()
        |            assertEquals(0.5, lastOutput, 1e-9)
        |
        |            RobotClock.useMockTime(2_000L)
        |            adapter.writeOutputs()
        |            assertEquals(0.0, lastOutput, 1e-9)
        |
        |            adapter.readInputs()
        |            adapter.writeOutputs()
        |            assertEquals(0.0, lastOutput, 1e-9)
        |            adapter.enable()
        |            adapter.${if (ftc) "setTargetPower" else "setTargetVoltage"}(0.5)
        |            adapter.writeOutputs()
        |            assertEquals(0.5, lastOutput, 1e-9)
        |            adapter.disable()
        |            assertEquals(0.0, lastOutput, 1e-9)
        |        } finally {
        |            RobotClock.useSystemTime()
        |        }
        |    }
        |}
        |
    """.trimMargin()
}
