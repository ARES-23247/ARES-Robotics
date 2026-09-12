package com.ares.analytics.service.hardware

import com.ares.analytics.service.BeforeAtomicReplace
import com.ares.analytics.service.drivebase.*
import com.ares.analytics.service.project.persistence.SubsystemProjectRepository
import com.ares.analytics.shared.models.League
import com.areslib.subsystem.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class HardwareEvidenceStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun project(): File = temporary.newFolder().also { root ->
        DrivebaseProjectRepository().saveReviewed(root.path, null, defaultDrivebase("team1-robot", DrivebaseKind.FTC_MECANUM, League.FTC))
        SubsystemProjectRepository().save(root.path, SubsystemTemplates.create(SubsystemTemplate.SIMPLE_ACTUATOR, "arm", "Arm", SubsystemPlatform.FTC).let { document ->
            document.copy(hardware = document.hardware.map { it.copy(connection = it.connection.copy(hardwareMapName = "arm")) })
        })
    }

    private fun service(beforePublish: BeforeAtomicReplace = { _, _ -> }) = HardwareSetupService(
        clock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC),
        beforeEvidencePublish = beforePublish,
    )

    private fun review(snapshot: HardwareSetupSnapshot) = HardwareReviewRequest(snapshot.inventoryHash, "Fixture reviewer", true, true, true, true, true)
    private fun physical(snapshot: HardwareSetupSnapshot) = HardwarePhysicalValidationRequest(
        snapshot.inventoryHash, "Fixture validator", "Synthetic fixture evidence; no physical robot was tested.", true, true, true, true, true,
    )

    private fun ready(root: File): HardwareSetupSnapshot {
        val service = service()
        return service.saveReview(root.path, League.FTC, review(service.inspect(root.path, League.FTC)))
    }

    private fun linkDirectory(link: Path, target: Path) {
        Files.createDirectories(link.parent)
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Junction creation timed out")
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
            } finally { if (process.isAlive) process.destroyForcibly() }
        } else Files.createSymbolicLink(link, target)
    }

    private fun redirectedWrite(physical: Boolean) {
        val root = project()
        val snapshot = if (physical) ready(root) else service().inspect(root.path, League.FTC)
        val kind = if (physical) "physical" else "configuration"
        val external = temporary.newFolder().toPath()
        val link = root.toPath().resolve(".ares/evidence/hardware/$kind")
        linkDirectory(link, external)
        try {
            assertFailsWith<IllegalArgumentException> {
                if (physical) service().savePhysicalValidation(root.path, League.FTC, physical(snapshot))
                else service().saveReview(root.path, League.FTC, review(snapshot))
            }
            assertTrue(external.toFile().listFiles().orEmpty().isEmpty())
        } finally { Files.deleteIfExists(link) }
    }

    @Test fun `configuration writes reject an evidence directory outside the project`() = redirectedWrite(false)
    @Test fun `physical writes reject an evidence directory outside the project`() = redirectedWrite(true)

    private fun redirectedRead(physical: Boolean) {
        val root = project()
        val snapshot = ready(root)
        if (physical) service().savePhysicalValidation(root.path, League.FTC, physical(snapshot))
        val kind = if (physical) "physical" else "configuration"
        val source = root.toPath().resolve(".ares/evidence/hardware/$kind")
        val external = temporary.newFolder().toPath().resolve("records")
        Files.move(source, external)
        linkDirectory(source, external)
        try {
            assertFailsWith<IllegalArgumentException> { service().inspect(root.path, League.FTC) }
            assertNotNull(service().deploymentBlockReason(root.path, League.FTC))
        } finally { Files.deleteIfExists(source) }
    }

    @Test fun `configuration reads cannot inherit evidence from a linked outside directory`() = redirectedRead(false)
    @Test fun `physical reads cannot inherit evidence from a linked outside directory`() = redirectedRead(true)

    @Test
    fun `record appearing after preparation is never replaced`() {
        val root = project()
        var destination: Path? = null
        val service = service { _, target ->
            destination = target
            Files.writeString(target, "Owned by a concurrent writer", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
        val request = review(service.inspect(root.path, League.FTC))
        assertFailsWith<Exception> { service.saveReview(root.path, League.FTC, request) }
        assertEquals("Owned by a concurrent writer", Files.readString(requireNotNull(destination)))
    }

    @Test
    fun `two concurrent identical appends have one winner`() {
        val root = project()
        val request = review(service().inspect(root.path, League.FTC))
        val barrier = CyclicBarrier(2)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                workers.submit<Boolean> {
                    val service = service { _, _ -> barrier.await(5, TimeUnit.SECONDS) }
                    runCatching { service.saveReview(root.path, League.FTC, request) }.isSuccess
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
            val records = root.resolve(".ares/evidence/hardware/configuration").listFiles().orEmpty()
            assertEquals(1, records.count { it.extension == "json" })
            assertTrue(records.none { it.extension == "tmp" })
            assertEquals(HardwareReviewStatus.CURRENT, service().inspect(root.path, League.FTC).reviewStatus)
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a project opened through a directory link retains its own evidence`() {
        val root = project()
        val alias = temporary.root.toPath().resolve("project-alias")
        linkDirectory(alias, root.toPath())
        try {
            val service = service()
            val reviewed = service.saveReview(alias.toString(), League.FTC, review(service.inspect(alias.toString(), League.FTC)))
            assertEquals(HardwareReviewStatus.CURRENT, reviewed.reviewStatus)
            assertEquals(HardwareReviewStatus.CURRENT, service.inspect(root.path, League.FTC).reviewStatus)
        } finally { Files.deleteIfExists(alias) }
    }

    @Test
    fun `a failed publication leaves no new evidence or temporary record`() {
        val root = project()
        val service = service { _, _ -> throw java.io.IOException("Publication rejected") }
        assertFailsWith<java.io.IOException> {
            service.saveReview(root.path, League.FTC, review(service.inspect(root.path, League.FTC)))
        }
        assertTrue(root.resolve(".ares/evidence/hardware/configuration").listFiles().orEmpty().isEmpty())
        assertEquals(HardwareReviewStatus.NOT_REVIEWED, service.inspect(root.path, League.FTC).reviewStatus)
    }
}
