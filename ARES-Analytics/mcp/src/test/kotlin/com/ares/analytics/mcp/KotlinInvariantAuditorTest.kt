package com.ares.analytics.mcp

import com.ares.analytics.mcp.audit.AuditSeverity
import com.ares.analytics.mcp.audit.KotlinInvariantAuditor
import com.ares.analytics.mcp.engine.ConfirmationTokenManager
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinInvariantAuditorTest {

    @Test
    fun `detects ARES-GC-001 inside hot loop while ignoring comments strings and init methods`() {
        val bad = """
            package org.team.subsystems
            class BadSubsystem {
                fun periodic() {
                    val items = mutableListOf<Double>()
                    val sb = StringBuilder()
                }
            }
        """.trimIndent()
        val findings = KotlinInvariantAuditor.auditText("src/main/kotlin/BadSubsystem.kt", bad)
        assertTrue(findings.count { it.ruleId == "ARES-GC-001" } >= 2)

        val good = """
            package org.team.subsystems
            class GoodSubsystem {
                private val buffer = DoubleArray(4)
                fun robotInit() {
                    val setup = listOf(1.0, 2.0)
                }
                fun periodic() {
                    // listOf(1.0) in comment should not trigger
                    val note = "mutableListOf()"
                    buffer[0] = 1.0
                }
            }
        """.trimIndent()
        assertTrue(KotlinInvariantAuditor.auditText("src/main/kotlin/GoodSubsystem.kt", good).none { it.ruleId == "ARES-GC-001" })
    }

    @Test
    fun `detects ARES-CLK-002 raw clock and sleep calls while allowing RobotClock`() {
        val bad = """
            class ClockViolator {
                fun step() {
                    val t = System.currentTimeMillis()
                    Thread.sleep(5)
                }
            }
        """.trimIndent()
        val findings = KotlinInvariantAuditor.auditText("src/main/kotlin/ClockViolator.kt", bad)
        assertEquals(2, findings.count { it.ruleId == "ARES-CLK-002" })

        val clean = """
            import com.areslib.util.RobotClock
            class ClockPure {
                fun step(): Long = RobotClock.currentTimeMillis()
            }
        """.trimIndent()
        assertTrue(KotlinInvariantAuditor.auditText("src/main/kotlin/ClockPure.kt", clean).none { it.ruleId == "ARES-CLK-002" })
    }

    @Test
    fun `detects ARES-RDX-003 mutable state and hardware imports in reducer`() {
        val bad = """
            package org.team.state
            import com.ctre.phoenix6.hardware.TalonFX
            data class ArmState(var target: Double = 0.0)
        """.trimIndent()
        val findings = KotlinInvariantAuditor.auditText("src/main/kotlin/ArmStateReducer.kt", bad)
        assertEquals(2, findings.count { it.ruleId == "ARES-RDX-003" })

        val clean = """
            package org.team.state
            data class ArmState(val target: Double = 0.0)
        """.trimIndent()
        assertTrue(KotlinInvariantAuditor.auditText("src/main/kotlin/ArmStateReducer.kt", clean).isEmpty())
    }

    @Test
    fun `detects ARES-SAF-004 actuator output without neutral stop or disable reset`() {
        val bad = """
            class UnsafeShooter {
                fun shoot() {
                    motor.setPower(0.9)
                }
            }
        """.trimIndent()
        val findings = KotlinInvariantAuditor.auditText("src/main/kotlin/UnsafeShooter.kt", bad)
        assertEquals(1, findings.count { it.ruleId == "ARES-SAF-004" && it.severity == AuditSeverity.ERROR })

        val safe = """
            class SafeShooter {
                fun shoot() {
                    motor.setPower(0.9)
                }
                fun disable() {
                    motor.setPower(0.0)
                }
            }
        """.trimIndent()
        assertTrue(KotlinInvariantAuditor.auditText("src/main/kotlin/SafeShooter.kt", safe).none { it.ruleId == "ARES-SAF-004" })
        assertTrue(KotlinInvariantAuditor.auditText("src/test/kotlin/SafeShooterTest.kt", bad).none { it.ruleId == "ARES-SAF-004" })
    }

    @Test
    fun `detects ARES-IO-005 multiple uncached hardware reads outside readInputs`() {
        val bad = """
            class UncachedIo {
                fun periodic() {
                    val a = motor.currentPosition
                    val b = motor.getVelocity()
                }
            }
        """.trimIndent()
        val findings = KotlinInvariantAuditor.auditText("src/main/kotlin/UncachedIo.kt", bad)
        assertEquals(1, findings.count { it.ruleId == "ARES-IO-005" && it.severity == AuditSeverity.WARNING })

        val cached = """
            class CachedIo {
                private var pos = 0.0
                private var vel = 0.0
                fun readInputs() {
                    pos = motor.currentPosition
                    vel = motor.getVelocity()
                }
            }
        """.trimIndent()
        assertTrue(KotlinInvariantAuditor.auditText("src/main/kotlin/CachedIo.kt", cached).none { it.ruleId == "ARES-IO-005" })
    }

    @Test
    fun `detects ARES-CRD-006 heading sign and degree traps`() {
        val bad = """
            class BadCoords {
                fun update(yawDeg: Double, pinpointHeading: Double) {
                    val z = robotPoseTargetSpace.rotation.z
                    val h = -pinpointHeading
                    val heading = Math.toDegrees(yawDeg)
                }
            }
        """.trimIndent()
        val findings = KotlinInvariantAuditor.auditText("src/main/kotlin/BadCoords.kt", bad)
        assertEquals(3, findings.count { it.ruleId == "ARES-CRD-006" })
    }

    @Test
    fun `detects ARES-OFF-007 and ARES-SIM-008 while skipping tests and visualizers`() {
        val bad = """
            import io.ktor.client.HttpClient
            class CheatingEstimator {
                val topic = "ARES/TruePose"
                val truth = groundTruthPose
            }
        """.trimIndent()
        val mainFindings = KotlinInvariantAuditor.auditText("src/main/kotlin/CheatingEstimator.kt", bad)
        assertTrue(mainFindings.any { it.ruleId == "ARES-OFF-007" })
        assertTrue(mainFindings.any { it.ruleId == "ARES-SIM-008" })

        val visualizerFindings = KotlinInvariantAuditor.auditText("src/main/kotlin/TelemetryVisualizer.kt", bad)
        assertTrue(visualizerFindings.none { it.ruleId == "ARES-SIM-008" })

        val testFindings = KotlinInvariantAuditor.auditText("src/test/kotlin/EstimatorTest.kt", bad)
        assertTrue(testFindings.none { it.ruleId == "ARES-OFF-007" })
        assertTrue(testFindings.none { it.ruleId == "ARES-SIM-008" })
    }

    @Test
    fun `auditProject skips generated do not edit files and aggregates findings`() {
        val projectRoot = Files.createTempDirectory("ares-mcp-audit-").toFile()
        try {
            val genFile = File(projectRoot, "src/main/kotlin/GenBad.kt").apply { parentFile.mkdirs() }
            genFile.writeText(
                "${ConfirmationTokenManager.GENERATED_DO_NOT_EDIT_MARKER}\nfun periodic() { val x = System.currentTimeMillis() }\n",
            )
            val userFile = File(projectRoot, "src/main/kotlin/UserBad.kt")
            userFile.writeText(
                "${ConfirmationTokenManager.USER_OWNED_MARKER}\nfun periodic() { val t = System.currentTimeMillis() }\n",
            )
            val report = KotlinInvariantAuditor.auditProject(projectRoot)
            assertFalse(report.passed)
            assertEquals(1, report.errorCount)
            assertEquals(1, report.findings.size)
            assertTrue(report.findings.single().file.endsWith("UserBad.kt"))
        } finally {
            projectRoot.deleteRecursively()
        }
    }
}
