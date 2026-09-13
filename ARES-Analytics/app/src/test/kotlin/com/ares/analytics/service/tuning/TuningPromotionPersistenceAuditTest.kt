package com.ares.analytics.service.tuning

import com.ares.analytics.util.Sha256
import com.areslib.tuning.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class TuningPromotionPersistenceAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private val declaration = TuningParameterDeclaration(
        "drive.gain", "drive.gain", "drive.primary", "Gain", "Test gain", TuningParameterType.DOUBLE,
        defaultValue = TuningValue(doubleValue = 2.0), applyPolicy = TuningApplyPolicy.LIVE_SAFE,
    )
    private val declarations = listOf(declaration)
    private val profile = TuningProfileDocument(
        uid = "profile.test", profileId = "test", displayName = "Test", description = "Test profile",
        projectId = "robot.project", authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
        values = listOf(TuningAssignment(declaration.uid, TuningValue(doubleValue = 2.0))),
    )
    private fun change(value: Double = 3.0) = TuningProfileChange(
        declaration.uid, declaration.key, declaration.displayName, TuningValue(doubleValue = 2.0),
        TuningValue(doubleValue = value), "unit", TuningValueOwner.ROBOT_PROFILE, declaration.applyPolicy,
        TuningValueProvenance("manual", "Reviewed experiment"),
    )
    private fun project(): File = temporary.newFolder().also { root ->
        File(root, ".ares/tuning/test.arestuning").apply {
            parentFile.mkdirs(); writeText(TuningProfileDocumentCodec.encode(profile, declarations))
        }
    }
    private fun promote(root: File, repository: TuningProfileRepository = TuningProfileRepository(),
        changes: List<TuningProfileChange> = listOf(change()), catalog: List<TuningParameterDeclaration> = declarations,
    ) = repository.promote(root.path, profile, TuningProfileDocumentCodec.contentHash(profile, catalog), catalog,
        changes, "Reviewer", "Validated experiment")
    private fun fileSnapshot(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }

    @Test fun `review tokens distinguish exact floating point proposals below display precision`() {
        val repository = TuningProfileRepository()
        assertNotEquals(
            repository.reviewToken(profile, declarations, listOf(change(3.000001)), "Reviewer", "Review"),
            repository.reviewToken(profile, declarations, listOf(change(3.000002)), "Reviewer", "Review"),
        )
    }

    @Test fun `review token fields cannot be redistributed through delimiter text`() {
        val repository = TuningProfileRepository()
        assertNotEquals(
            repository.reviewToken(profile, declarations, listOf(change()), "A|B", "C"),
            repository.reviewToken(profile, declarations, listOf(change()), "A", "B|C"),
        )
    }

    @Test fun `review token binds displayed policy and ownership`() {
        val repository = TuningProfileRepository(); val original = change()
        assertNotEquals(
            repository.reviewToken(profile, declarations, listOf(original), "A", "Review"),
            repository.reviewToken(profile, declarations, listOf(original.copy(policy = TuningApplyPolicy.CALIBRATION_ONLY,
                owner = TuningValueOwner.VENDOR_SOURCE)), "A", "Review"),
        )
    }

    @Test fun `evidence cannot leave the project through a linked directory`() {
        val root = project(); val outside = temporary.newFolder(); val evidence = File(outside, "run.log")
        evidence.writeText("evidence bytes")
        val link = File(root, "evidence")
        linkDirectory(link, outside)
        try {
            val proposal = change().copy(provenance = TuningValueProvenance("live robot", "Reviewed",
                "evidence/run.log", Sha256.fileHex(evidence)))
            assertTrue(TuningProfileRepository().evidenceErrors(root.path, listOf(proposal)).isNotEmpty())
        } finally { Files.deleteIfExists(link.toPath()) }
    }

    @Test fun `attached manual evidence must have matching bytes and a relative path`() {
        val root = project(); val evidence = File(root, "run.log").apply { writeText("evidence bytes") }
        val repository = TuningProfileRepository()
        for (provenance in listOf(
            TuningValueProvenance("manual", "Reviewed", "run.log", "0".repeat(64)),
            TuningValueProvenance("manual", "Reviewed", evidence.absolutePath.replace('\\', '/'), Sha256.fileHex(evidence)),
            TuningValueProvenance("manual", "Reviewed", "run.log", null),
        )) assertTrue(repository.evidenceErrors(root.path, listOf(change().copy(provenance = provenance))).isNotEmpty())
    }

    @Test fun `failed canonical replacement cannot publish successful review history`() {
        val root = project(); val before = fileSnapshot(root)
        val repository = TuningProfileRepository()
        repository.beforeCanonicalReplace = { throw java.io.IOException("replace unavailable") }
        assertFailsWith<java.io.IOException> { promote(root, repository) }
        assertEquals(before, fileSnapshot(root))
    }

    @Test fun `competing repositories cannot both promote the same reviewed revision`() {
        val root = project(); val first = TuningProfileRepository(); val second = TuningProfileRepository()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val secondStarted = CountDownLatch(1)
        first.beforeCanonicalReplace = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val executor = Executors.newFixedThreadPool(2)
        val firstResult = executor.submit<Result<TuningProfileDocument>> { runCatching { promote(root, first) } }
        lateinit var secondResult: java.util.concurrent.Future<Result<TuningProfileDocument>>
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            secondResult = executor.submit<Result<TuningProfileDocument>> {
                secondStarted.countDown(); runCatching { promote(root, second, listOf(change(4.0))) }
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { secondResult.get(200, TimeUnit.MILLISECONDS) }
        } finally {
            release.countDown()
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertTrue(firstResult.get().isSuccess)
        assertTrue(secondResult.get().exceptionOrNull() is IllegalArgumentException)
        val saved = TuningProfileDocumentCodec.decode(File(root, ".ares/tuning/test.arestuning").readText(), declarations)
        assertEquals(3.0, saved.values.single().value.doubleValue)
    }

    @Test fun `review identity is independent of display locale`() {
        val repository = TuningProfileRepository(); val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            val token = repository.reviewToken(profile, declarations, listOf(change(3.125)), "Reviewer", "Review")
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals(token, repository.reviewToken(profile, declarations, listOf(change(3.125)), "Reviewer", "Review"))
        } finally { java.util.Locale.setDefault(previous) }
    }

    @Test fun `valid manual and calibration evidence retains its verified hash`() {
        for (policy in listOf(TuningApplyPolicy.LIVE_SAFE, TuningApplyPolicy.CALIBRATION_ONLY)) {
            val root = project(); val evidence = File(root, "run.log").apply { writeText("verified observation") }
            val hash = Sha256.fileHex(evidence)
            val proposal = change().copy(policy = policy, provenance = TuningValueProvenance("manual", "Reviewed",
                "run.log", hash.uppercase()))
            val saved = promote(root, changes = listOf(proposal), catalog = listOf(declaration.copy(applyPolicy = policy)))
            assertEquals(3.0, saved.values.single().value.doubleValue)
            val promotion = assertNotNull(saved.promotion)
            assertEquals(hash, promotion.evidenceSha256[promotion.evidencePaths.indexOf("run.log")])
        }
    }

    @Test fun `duplicate or invalid typed changes fail before publishing history`() {
        for (changes in listOf(listOf(change(), change(4.0)), listOf(change().copy(after = TuningValue(textValue = "wrong type"))))) {
            val root = project(); val before = fileSnapshot(root)
            assertFailsWith<IllegalArgumentException> { promote(root, changes = changes) }
            assertEquals(before, fileSnapshot(root))
        }
    }

    @Test fun `project root aliases work but canonical history and recovery aliases cannot escape`() {
        val actual = project(); val alias = File(temporary.newFolder(), "project")
        linkDirectory(alias, actual)
        try { assertEquals(3.0, promote(alias).values.single().value.doubleValue) }
        finally { Files.deleteIfExists(alias.toPath()) }
        for (relative in listOf(".ares/tuning", ".ares/history/tuning", ".ares/recovery/transactions")) {
            val root = project(); val outside = temporary.newFolder(); val redirected = File(root, relative)
            val target = File(outside, "target")
            if (redirected.exists()) Files.move(redirected.toPath(), target.toPath()) else target.mkdirs()
            redirected.parentFile.mkdirs()
            File(target, "keep").writeText("outside bytes")
            val before = fileSnapshot(outside)
            linkDirectory(redirected, target)
            try {
                assertFailsWith<IllegalArgumentException> { promote(root) }
                assertEquals(before, fileSnapshot(outside))
            } finally { Files.deleteIfExists(redirected.toPath()) }
        }
    }

    @Test fun `failed Windows replacement keeps original canonical bytes and can recover after unlock`() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = project(); val before = fileSnapshot(root)
        val canonical = File(root, ".ares/tuning/test.arestuning")
        java.nio.channels.FileChannel.open(canonical.toPath(), java.nio.file.StandardOpenOption.READ,
            com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE).use {
            assertFailsWith<java.io.IOException> { promote(root) }
            assertEquals(before.getValue(".ares/tuning/test.arestuning"), canonical.readBytes().toList())
            assertTrue(File(root, ".ares/history").walkTopDown().none { it.isFile })
        }
        com.ares.analytics.service.project.persistence.ProjectMutationTransaction.recover(root)
        assertEquals(before, fileSnapshot(root))
    }

    @Test fun `promotion enforces declaration policy even when supplied change claims live safe`() {
        for (policy in listOf(TuningApplyPolicy.CALIBRATION_ONLY, TuningApplyPolicy.READ_ONLY_VENDOR)) {
            val root = project(); val before = fileSnapshot(root)
            assertFailsWith<IllegalArgumentException> {
                promote(root, catalog = listOf(declaration.copy(applyPolicy = policy)))
            }
            assertEquals(before, fileSnapshot(root))
        }
    }

    private fun linkDirectory(link: File, target: File) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.path, target.path).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
            } finally { if (process.isAlive) process.destroyForcibly() }
        } else Files.createSymbolicLink(link.toPath(), target.toPath())
    }
}
