package com.ares.analytics.mcp

import com.ares.analytics.mcp.audit.KotlinInvariantAuditor
import com.ares.analytics.mcp.bridge.StudioBridgeClient
import com.ares.analytics.mcp.engine.ConfirmationTokenManager
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import com.ares.analytics.mcp.engine.JGitSnapshotSafety
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FreshAuditMcpRegressionTest {

    private fun resolveTemplateDir(): File = listOf(
        File("../app/src/main/resources/project-templates"),
        File("ARES-Analytics/app/src/main/resources/project-templates"),
        File("app/src/main/resources/project-templates"),
    ).first { it.isDirectory }.canonicalFile

    @Test
    fun `auditor rejects path traversal outside project root`() {
        val tempRoot = Files.createTempDirectory("ares-mcp-audit-traversal-").toFile()
        try {
            val projectDir = File(tempRoot, "project").apply { mkdirs() }
            val outsideFile = File(tempRoot, "Secret.kt").apply {
                writeText("class Secret { fun periodic() { System.currentTimeMillis() } }")
            }
            assertTrue(outsideFile.isFile)
            assertFailsWith<IllegalArgumentException> {
                KotlinInvariantAuditor.auditProject(projectDir, listOf("../Secret.kt"))
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `gc and clock rules strip multiline block comments and raw strings without false positives`() {
        val cleanSource = """
            package org.team.subsystems
            class CleanLoop {
                fun periodic() {
                    /*
                     * Block comment mentioning mutableListOf() and System.currentTimeMillis()
                     * must not trigger ARES-GC-001 or ARES-CLK-002.
                     */
                    val doc = ${"\"\"\""}
                        mutableListOf<Int>()
                        Thread.sleep(5)
                    ${"\"\"\""}
                    val x = doc.length
                }
            }
        """.trimIndent()
        val findings = KotlinInvariantAuditor.auditText("src/main/kotlin/CleanLoop.kt", cleanSource)
        assertTrue(
            findings.none { it.ruleId == "ARES-GC-001" || it.ruleId == "ARES-CLK-002" },
            "Unexpected findings in block comment / raw string: $findings",
        )
    }

    @Test
    fun `rapid back-to-back snapshots produce unique monotonic refs`() {
        val tempRoot = Files.createTempDirectory("ares-mcp-snap-seq-").toFile()
        try {
            File(tempRoot, "README.md").writeText("hello")
            val first = JGitSnapshotSafety.createSnapshot(tempRoot, slug = "same-slug", initGitIfMissing = true)
            val second = JGitSnapshotSafety.createSnapshot(tempRoot, slug = "same-slug", initGitIfMissing = false)
            assertTrue(first.created)
            assertTrue(second.created)
            assertNotEquals(first.refName, second.refName)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `safeResolveChild blocks traversal and enforces requiredSubdir containment`() {
        val tempRoot = Files.createTempDirectory("ares-mcp-resolve-").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                JGitSnapshotSafety.safeResolveChild(tempRoot, "../outside.txt")
            }
            assertFailsWith<IllegalArgumentException> {
                JGitSnapshotSafety.safeResolveChild(tempRoot, ".ares/other/doc.json", requiredSubdir = ".ares/subsystems")
            }
            val ok = JGitSnapshotSafety.safeResolveChild(tempRoot, ".ares/subsystems/arm.aressubsystem", requiredSubdir = ".ares/subsystems")
            assertEquals("arm.aressubsystem", ok.name)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `mutators reject traversal document ids and malicious scaffold paths or packages`(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("ares-mcp-mutator-sec-").toFile()
        try {
            val bridgeDir = File(tempRoot, "bridge").apply { mkdirs() }
            val engine = HeadlessProjectEngine(
                bridgeClient = StudioBridgeClient(bridgeDir = bridgeDir),
                tokens = ConfirmationTokenManager(),
                templateDir = resolveTemplateDir(),
            )
            val mutators = HeadlessProjectMutators(engine)
            val projectDir = File(tempRoot, "frc-proj")
            engine.initProject(
                targetDir = projectDir,
                leagueRaw = "FRC",
                displayName = "AuditBot",
                teamId = "23247",
                seasonId = "2026",
            )

            assertFailsWith<IllegalArgumentException> {
                mutators.mutateSubsystem(
                    projectRoot = projectDir,
                    operationRaw = "DELETE",
                    documentIdHint = "../project",
                )
            }
            assertFailsWith<IllegalArgumentException> {
                mutators.scaffoldKotlin(
                    projectRoot = projectDir,
                    leagueRaw = "FRC",
                    kindRaw = "HARDWARE_IO",
                    name = "SafeIo",
                    packageName = "org.team; import evil",
                    modeRaw = "PREVIEW",
                )
            }
            assertFailsWith<IllegalArgumentException> {
                mutators.scaffoldKotlin(
                    projectRoot = projectDir,
                    leagueRaw = "FRC",
                    kindRaw = "HARDWARE_IO",
                    name = "SafeIo",
                    packageName = "org.team.robot",
                    relativePathOverride = "build.gradle",
                    modeRaw = "PREVIEW",
                )
            }
            Unit
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
