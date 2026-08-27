package com.ares.analytics.service.tuning

import com.areslib.tuning.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.viewmodel.nextTuningRequestNonce
import com.ares.analytics.viewmodel.recordTuningPromotionCheckpoint

class TuningProfileAuthoringTest {
    private val gain = declaration("drive.translation.kp", "drive.translation.kP", TuningApplyPolicy.LIVE_SAFE, 2.0, 0.0, 50.0)
    private val vendor = declaration("drive.vendor.radius", "drive.vendor.wheelRadius", TuningApplyPolicy.READ_ONLY_VENDOR, .05, .01, .25)
    private val declarations = listOf(gain, vendor)
    private val parent = profile("profile.base", "base", null, listOf(TuningAssignment(gain.uid, TuningValue(doubleValue = 2.0))))
    private val competition = profile("profile.competition", "competition", parent.uid, emptyList())

    @Test
    fun `profile switch resolution retains shallow inheritance`() {
        val rows = resolveTuningProfile(competition, listOf(parent, competition), declarations, emptyMap(), emptyMap())
        val row = rows.first { it.declaration.uid == gain.uid }
        assertEquals(2.0, row.sourceValue)
        assertEquals(parent.uid, row.sourceProfileId)
        assertEquals(TuningValueStatus.INHERITED, row.status)
    }

    @Test
    fun `typed source values remain visible without numeric inference`() {
        val enabled = TuningParameterDeclaration(
            "drive.enabled", "drive.enabled", "drive.primary", "Enabled", "Boolean test",
            TuningParameterType.BOOLEAN, null, defaultValue = TuningValue(booleanValue = true), applyPolicy = TuningApplyPolicy.RESTART_REQUIRED
        )
        val typedProfile = profile("profile.typed", "typed", null, listOf(TuningAssignment(enabled.uid, TuningValue(booleanValue = false))))
        val row = resolveTuningProfile(typedProfile, listOf(typedProfile), listOf(enabled), emptyMap(), emptyMap()).single()
        assertNull(row.sourceValue)
        assertEquals("false", row.sourceTypedValue?.displayValue())
    }

    @Test
    fun `transport separates current requested and nonce topics by stable declaration UID`() {
        assertEquals("Tuning/Parameters/${gain.uid}/Current", TuningTransport.current(gain))
        assertEquals("Tuning/Parameters/${gain.uid}/ConsumerSupported", TuningTransport.consumerSupported(gain))
        assertEquals("Tuning/Parameters/${gain.uid}/Requested", TuningTransport.requested(gain))
        assertEquals("Tuning/Parameters/${gain.uid}/RequestNonce", TuningTransport.requestNonce(gain))
        assertEquals("Tuning/Parameters/${gain.uid}/ProcessedNonce", TuningTransport.processedNonce(gain))
        assertEquals("Tuning/Parameters/${gain.uid}/LastResult", TuningTransport.lastResult(gain))
        assertNotEquals(gain.key, TuningTransport.current(gain))
    }

    @Test
    fun `workspace load rejects duplicate profile identities and broken inheritance`() {
        val duplicateRoot = Files.createTempDirectory("ares-duplicate-profile").toFile()
        val duplicateDir = File(duplicateRoot, ".ares/tuning").apply { mkdirs() }
        duplicateDir.resolve("a.arestuning").writeText(TuningProfileDocumentCodec.encode(profile("profile.a", "competition", null, emptyList()), emptyList()))
        duplicateDir.resolve("b.arestuning").writeText(TuningProfileDocumentCodec.encode(profile("profile.b", "competition", null, emptyList()), emptyList()))
        val duplicateFailure = TuningProfileRepository().load(duplicateRoot.path).exceptionOrNull()
        assertNotNull(duplicateFailure)
        assertTrue(duplicateFailure.message.orEmpty().contains("IDs must be unique"))

        val brokenRoot = Files.createTempDirectory("ares-broken-profile").toFile()
        val brokenDir = File(brokenRoot, ".ares/tuning").apply { mkdirs() }
        brokenDir.resolve("broken.arestuning").writeText(
            TuningProfileDocumentCodec.encode(profile("profile.broken", "broken", "profile.missing", emptyList()), emptyList())
        )
        val brokenFailure = TuningProfileRepository().load(brokenRoot.path).exceptionOrNull()
        assertNotNull(brokenFailure)
        assertTrue(brokenFailure.message.orEmpty().contains("missing", ignoreCase = true))
    }

    @Test
    fun `request nonce resumes above robot observation and never wraps`() {
        assertEquals(43L, nextTuningRequestNonce(local = 0L, observed = 42.0))
        assertEquals(101L, nextTuningRequestNonce(local = 100L, observed = 42.0))
        assertFailsWith<IllegalArgumentException> { nextTuningRequestNonce(9_007_199_254_740_991L, 2.0) }
    }

    @Test
    fun `typed proposals validate double int boolean text and enum without coercion`() {
        val typed = listOf(
            declaration("typed.double", "typed.double", TuningApplyPolicy.LIVE_SAFE, 1.0, 0.0, 2.0),
            TuningParameterDeclaration("typed.int", "typed.int", "drive.primary", "Int", "int", TuningParameterType.INT, minimum = 0.0, maximum = 5.0, defaultValue = TuningValue(intValue = 1), applyPolicy = TuningApplyPolicy.RESTART_REQUIRED),
            TuningParameterDeclaration("typed.boolean", "typed.boolean", "drive.primary", "Boolean", "boolean", TuningParameterType.BOOLEAN, defaultValue = TuningValue(booleanValue = false), applyPolicy = TuningApplyPolicy.DISABLED_ONLY),
            TuningParameterDeclaration("typed.text", "typed.text", "drive.primary", "Text", "text", TuningParameterType.TEXT, defaultValue = TuningValue(textValue = "a"), applyPolicy = TuningApplyPolicy.REBUILD_REQUIRED),
            TuningParameterDeclaration("typed.enum", "typed.enum", "drive.primary", "Enum", "enum", TuningParameterType.ENUM, defaultValue = TuningValue(textValue = "coast"), enumOptions = listOf("coast", "brake"), applyPolicy = TuningApplyPolicy.CALIBRATION_ONLY)
        )
        val base = profile("profile.typed-all", "typed-all", null, typed.map { TuningAssignment(it.uid, it.defaultValue) })
        val proposals = mapOf(
            "typed.double" to TuningValue(doubleValue = 1.5), "typed.int" to TuningValue(intValue = 3),
            "typed.boolean" to TuningValue(booleanValue = true), "typed.text" to TuningValue(textValue = "student"),
            "typed.enum" to TuningValue(textValue = "brake")
        )
        val provenance = proposals.keys.associateWith { TuningValueProvenance("manual", "typed test") }
        val (changes, errors) = buildTuningReview(base, listOf(base), typed, proposals, provenance)
        assertTrue(errors.isEmpty())
        assertEquals(proposals.values.toSet(), changes.map { it.after }.toSet())

        val bad = proposals + ("typed.int" to TuningValue(doubleValue = 2.5)) + ("typed.enum" to TuningValue(textValue = "invalid"))
        val (_, badErrors) = buildTuningReview(base, listOf(base), typed, bad, provenance)
        assertTrue(badErrors.any { it.contains("declared int") })
        assertTrue(badErrors.any { it.contains("Choose one of") })
    }

    @Test
    fun `out of range undeclared and vendor proposals block promotion`() {
        val proposals = mapOf(gain.key to TuningValue(doubleValue = 100.0), vendor.key to TuningValue(doubleValue = .04), "drive.notDeclared.value" to TuningValue(doubleValue = 1.0))
        val provenance = proposals.keys.associateWith { TuningValueProvenance("test", "Evidence") }
        val (changes, errors) = buildTuningReview(competition, listOf(parent, competition), declarations, proposals, provenance)
        assertTrue(changes.isEmpty())
        assertTrue(errors.any { it.contains("at most") })
        assertTrue(errors.any { it.contains("vendor-owned") })
        assertTrue(errors.any { it.contains("not declared") })
    }

    @Test
    fun `shared apply policies are exhaustively represented`() {
        fun capability(policy: TuningApplyPolicy) = when (policy) {
            TuningApplyPolicy.LIVE_SAFE -> "live"
            TuningApplyPolicy.DISABLED_ONLY -> "disabled"
            TuningApplyPolicy.RESTART_REQUIRED -> "restart"
            TuningApplyPolicy.REBUILD_REQUIRED -> "rebuild"
            TuningApplyPolicy.CALIBRATION_ONLY -> "calibration"
            TuningApplyPolicy.READ_ONLY_VENDOR -> "read-only"
        }
        assertEquals(TuningApplyPolicy.entries.size, TuningApplyPolicy.entries.map(::capability).distinct().size)
    }

    @Test
    fun `review is side effect free and promotion writes canonical codec profile plus history`() {
        val root = Files.createTempDirectory("ares-tuning").toFile()
        val canonical = File(root, ".ares/tuning/competition.arestuning")
        canonical.parentFile.mkdirs()
        canonical.writeText(TuningProfileDocumentCodec.encode(competition, declarations))
        val repository = TuningProfileRepository()
        val proposal = mapOf(gain.key to TuningValue(doubleValue = 3.0))
        val provenance = mapOf(gain.key to TuningValueProvenance("simulator", "Stable direction lab run"))
        val (changes, errors) = buildTuningReview(competition, listOf(parent, competition), declarations, proposal, provenance)
        val before = canonical.readText()
        assertTrue(errors.isEmpty())
        assertEquals(before, canonical.readText(), "Review must be side-effect free")
        val beforeHash = TuningProfileDocumentCodec.contentHash(competition, declarations)

        val promoted = repository.promote(root.path, competition, beforeHash, declarations, changes, "Student A", "Reviewed simulator evidence")

        assertEquals(3.0, promoted.values.first { it.parameterUid == gain.uid }.value.doubleValue)
        val promotion = assertNotNull(promoted.promotion)
        assertEquals("Student A", promotion.reviewedBy)
        val snapshot = File(root, promotion.evidencePaths.first())
        assertTrue(snapshot.isFile)
        val snapshotProfile = TuningProfileDocumentCodec.decode(snapshot.readText(), declarations)
        assertEquals(TuningProfileAuthority.LOCAL_EXPERIMENTAL, snapshotProfile.authority)
        assertEquals(promotion.sourceContentSha256, TuningProfileDocumentCodec.contentHash(snapshotProfile, declarations))
        assertTrue(File(root, ".ares/history/tuning/${competition.uid}/${beforeHash.take(16)}.arestuning").isFile)
        assertEquals(promoted, TuningProfileDocumentCodec.decode(canonical.readText(), declarations))
        assertFalse(File(canonical.parentFile, ".${canonical.name}.tmp").exists())
    }

    @Test
    fun `reviewed promotion checkpoints canonical profile and immutable tuning history only`() = runBlocking {
        var capturedProject = ""
        var capturedLabel = ""
        var capturedPaths = emptySet<String>()
        val recorder = ProjectCheckpointRecorder { projectPath, label, pathScopes ->
            capturedProject = projectPath
            capturedLabel = label
            capturedPaths = pathScopes
            null
        }

        recordTuningPromotionCheckpoint(
            recorder = recorder,
            projectPath = "C:/robot",
            profileDisplayName = "Competition",
            reviewSummary = "Validated in the simulator",
        )

        assertEquals("C:/robot", capturedProject)
        assertEquals("Promoted Competition tuning profile: Validated in the simulator", capturedLabel)
        assertEquals(setOf(".ares/tuning", ".ares/history/tuning"), capturedPaths)
    }

    @Test
    fun `live or calibration provenance without verified evidence cannot promote`() {
        val root = Files.createTempDirectory("ares-evidence").toFile()
        val canonical = File(root, ".ares/tuning/${competition.uid}.arestuning")
        canonical.parentFile.mkdirs()
        canonical.writeText(TuningProfileDocumentCodec.encode(competition, declarations))
        val change = TuningProfileChange(gain.uid, gain.key, gain.displayName, TuningValue(doubleValue = 2.0), TuningValue(doubleValue = 3.0), "unit", TuningValueOwner.ROBOT_PROFILE, gain.applyPolicy, TuningValueProvenance("Live robot observation", "copied by student"))
        assertFailsWith<IllegalArgumentException> {
            TuningProfileRepository().promote(root.path, competition, TuningProfileDocumentCodec.contentHash(competition, declarations), declarations, listOf(change), "Student A", "No evidence")
        }
    }

    @Test
    fun `stale reviewed hash cannot replace canonical profile or create proposal snapshot`() {
        val root = Files.createTempDirectory("ares-stale-review").toFile()
        val canonical = File(root, ".ares/tuning/${competition.uid}.arestuning")
        canonical.parentFile.mkdirs()
        canonical.writeText(TuningProfileDocumentCodec.encode(competition, declarations))
        val change = TuningProfileChange(gain.uid, gain.key, gain.displayName, TuningValue(doubleValue = 2.0), TuningValue(doubleValue = 3.0), "unit", TuningValueOwner.ROBOT_PROFILE, gain.applyPolicy, TuningValueProvenance("manual", "reviewed"))
        val before = canonical.readText()
        assertFailsWith<IllegalArgumentException> {
            TuningProfileRepository().promote(root.path, competition, "0".repeat(64), declarations, listOf(change), "Student", "Stale review")
        }
        assertEquals(before, canonical.readText())
        assertFalse(File(root, ".ares/history/tuning/${competition.uid}/proposals").exists())
    }

    private fun declaration(uid: String, key: String, policy: TuningApplyPolicy, default: Double, min: Double, max: Double) =
        TuningParameterDeclaration(uid, key, "drive.primary", uid, "Test declaration for $uid", TuningParameterType.DOUBLE, "unit", min, max, TuningValue(doubleValue = default), applyPolicy = policy)

    private fun profile(uid: String, id: String, parent: String?, values: List<TuningAssignment>) = TuningProfileDocument(
        uid = uid, profileId = id, displayName = id.replaceFirstChar(Char::uppercase), description = "Test profile",
        projectUid = "robot.project", drivebaseUid = "drive.primary", authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
        baseProfileUid = parent, values = values
    )
}
