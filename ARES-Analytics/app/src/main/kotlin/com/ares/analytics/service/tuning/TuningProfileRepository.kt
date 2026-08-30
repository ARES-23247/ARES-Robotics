package com.ares.analytics.service.tuning

import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.tuning.*
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class TuningWorkspaceDocuments(
    val catalog: List<TuningParameterDeclaration>,
    val profiles: List<TuningProfileDocument>,
)

data class ReviewedTuningHistory(
    val profileUid: String,
    val beforeHash: String,
    val afterHash: String,
    val reviewedBy: String,
    val reviewSummary: String,
    val changes: List<TuningProfileChange>
)

class TuningProfileRepository {
    private val gson = GsonBuilder().create()
    fun evidenceErrors(projectPath: String, changes: List<TuningProfileChange>): List<String> =
        runCatching { validateEvidence(projectPath, changes) }.exceptionOrNull()?.message?.let(::listOf).orEmpty()

    fun load(projectPath: String): Result<TuningWorkspaceDocuments> = loadInternal(projectPath, requireSingleProject = true)

    /**
     * Reads otherwise-valid checked-in tuning documents while allowing a legacy project UID
     * mismatch to be repaired by a structured, history-backed authoring transaction.
     * Normal consumers must use [load], which continues to fail closed.
     */
    internal fun loadForIdentityRepair(projectPath: String): Result<TuningWorkspaceDocuments> =
        loadInternal(projectPath, requireSingleProject = false)

    private fun loadInternal(projectPath: String, requireSingleProject: Boolean): Result<TuningWorkspaceDocuments> = runCatching {
        val root = File(projectPath, ".ares")
        val drivetrainDeclarations = File(root, "drivetrains").listFiles { file -> file.extension == "aresdrivetrain" }
            ?.flatMap { DrivetrainDocumentCodec.decode(it.readText()).parameters }.orEmpty()
        val subsystemDeclarations = File(root, "subsystems").listFiles { file -> file.extension == "aressubsystem" }
            ?.flatMap { SubsystemDocumentCodec.decode(it.readText()).tuningParameters }.orEmpty()
        val globalDeclarations = File(root, "tuning-components").listFiles { file -> file.extension == "arestuningcomponent" }
            ?.flatMap { TuningComponentDocumentCodec.decode(it.readText()).parameters }.orEmpty()
        val declarations = drivetrainDeclarations + subsystemDeclarations + globalDeclarations
        require(declarations.map { it.uid }.distinct().size == declarations.size) { "Tuning parameter UIDs must be unique across the project." }
        require(declarations.map { it.key }.distinct().size == declarations.size) { "Tuning parameter keys must be unique across the project." }
        val profiles = File(root, "tuning").listFiles { file -> file.extension == "arestuning" }
            ?.map { file ->
                if (requireSingleProject) TuningProfileDocumentCodec.decode(file.readText(), declarations)
                else decodeForIdentityRepair(file.readText(), declarations)
            }
            ?.sortedBy { it.displayName }.orEmpty()
        require(profiles.map { it.uid }.distinct().size == profiles.size) { "Tuning profile UIDs must be unique across the project." }
        require(profiles.map { it.profileId }.distinct().size == profiles.size) { "Tuning profile IDs must be unique across the project." }
        require(profiles.all { it.authority == TuningProfileAuthority.CANONICAL_CHECKED_IN }) {
            "Only checked-in canonical profiles belong in .ares/tuning; local experiments belong in .ares/local/tuning."
        }
        if (requireSingleProject) {
            require(profiles.map { it.projectUid }.distinct().size <= 1) { "Every tuning profile must target the same robot project." }
        }
        val resolvableProfiles = if (requireSingleProject) profiles else profiles.map { profile ->
            profile.copy(values = profile.values.filter { assignment -> declarations.any { it.uid == assignment.parameterUid } })
        }
        resolveTuningProfiles(resolvableProfiles, declarations)
        TuningWorkspaceDocuments(declarations, profiles)
    }

    private fun decodeForIdentityRepair(
        text: String,
        declarations: Collection<TuningParameterDeclaration>,
    ): TuningProfileDocument {
        val profile = runCatching { gson.fromJson(text, TuningProfileDocument::class.java) }
            .getOrElse { throw IllegalArgumentException("Invalid tuning profile: ${it.message}", it) }
        val blockingIssues = validateTuningProfileDocument(profile, declarations).filterNot { issue ->
            issue.path.matches(Regex("values\\[\\d+].parameterUid")) && issue.message.startsWith("Unknown parameter '")
        }
        require(blockingIssues.isEmpty()) { blockingIssues.joinToString("; ") { "${it.path}: ${it.message}" } }
        return profile
    }

    fun promote(
        projectPath: String,
        current: TuningProfileDocument,
        expectedContentHash: String,
        declarations: List<TuningParameterDeclaration>,
        changes: List<TuningProfileChange>,
        reviewedBy: String,
        reviewSummary: String
    ): TuningProfileDocument {
        require(changes.isNotEmpty()) { "Review at least one change before promotion." }
        require(reviewedBy.isNotBlank() && reviewSummary.isNotBlank()) { "Reviewer and review summary are required." }
        validateEvidence(projectPath, changes)
        val file = profileFile(projectPath, current, declarations)
        require(file.isFile) { "Canonical profile is missing. Create it through project setup before promotion." }
        val disk = TuningProfileDocumentCodec.decode(file.readText(), declarations)
        val diskHash = TuningProfileDocumentCodec.contentHash(disk, declarations)
        require(diskHash == expectedContentHash) { "The profile changed on disk. Reload and review a fresh diff." }
        val byUid = disk.values.associateBy { it.parameterUid }.toMutableMap()
        changes.forEach { change ->
            byUid[change.parameterUid] = TuningAssignment(change.parameterUid, change.after)
        }
        val historyDir = File(projectPath, ".ares/history/tuning/${current.uid}")
        historyDir.mkdirs()
        val proposal = disk.copy(
            uid = "${disk.uid}.proposal",
            profileId = "${disk.profileId}-proposal",
            displayName = "${disk.displayName} reviewed proposal",
            description = reviewSummary,
            authority = TuningProfileAuthority.LOCAL_EXPERIMENTAL,
            baseProfileUid = disk.uid,
            values = changes.map { TuningAssignment(it.parameterUid, it.after) }.sortedBy { it.parameterUid },
            promotion = null
        )
        val proposalText = TuningProfileDocumentCodec.encode(proposal, declarations)
        val proposalHash = TuningProfileDocumentCodec.contentHash(proposal, declarations)
        val proposalRelative = ".ares/history/tuning/${current.uid}/proposals/$proposalHash.arestuning"
        val proposalFile = File(projectPath, proposalRelative)
        if (proposalFile.exists()) require(proposalFile.readText() == proposalText) { "Immutable proposal snapshot hash collision." }
        else atomicWrite(proposalFile, proposalText)
        val evidencePairs = changes.mapNotNull { change ->
            val path = change.provenance.evidencePath
            val hash = change.provenance.evidenceSha256
            if (path != null && hash != null) path to hash.lowercase() else null
        }.distinct()
        val promoted = disk.copy(
            values = byUid.values.sortedBy { it.parameterUid },
            promotion = TuningPromotionData(
                sourceLocalProfileUid = proposal.uid,
                sourceContentSha256 = proposalHash,
                evidencePaths = listOf(proposalRelative) + evidencePairs.map { it.first },
                evidenceSha256 = listOf(proposalHash) + evidencePairs.map { it.second },
                reviewedBy = reviewedBy,
                reviewSummary = reviewSummary
            )
        )
        val encoded = TuningProfileDocumentCodec.encode(promoted, declarations)
        val afterHash = TuningProfileDocumentCodec.contentHash(promoted, declarations)
        Files.copy(file.toPath(), File(historyDir, "${diskHash.take(16)}.arestuning").toPath(), StandardCopyOption.REPLACE_EXISTING)
        writeHistory(File(historyDir, "${afterHash.take(16)}.review.txt"), ReviewedTuningHistory(current.uid, diskHash, afterHash, reviewedBy, reviewSummary, changes))
        atomicWrite(file, encoded)
        return promoted
    }

    fun reviewToken(profile: TuningProfileDocument, declarations: List<TuningParameterDeclaration>, changes: List<TuningProfileChange>, reviewedBy: String, reviewSummary: String): String {
        val hash = TuningProfileDocumentCodec.contentHash(profile, declarations)
        val canonical = "$hash|$reviewedBy|$reviewSummary|" + changes.joinToString("|") {
            "${it.parameterUid}:${it.before?.displayValue()}->${it.after.displayValue()}:${it.provenance.source}:${it.provenance.note}:${it.provenance.evidencePath}:${it.provenance.evidenceSha256}"
        }
        return sha256(canonical).take(16)
    }

    private fun profileFile(
        projectPath: String,
        profile: TuningProfileDocument,
        declarations: List<TuningParameterDeclaration>
    ): File {
        val directory = File(projectPath, ".ares/tuning")
        val matches = directory.listFiles { file -> file.extension == "arestuning" }
            ?.filter { file ->
                runCatching { TuningProfileDocumentCodec.decode(file.readText(), declarations) }
                    .getOrNull()?.let { it.uid == profile.uid } == true
            }.orEmpty()
        require(matches.size <= 1) { "Multiple canonical files claim profile UID ${profile.uid}. Resolve the duplicate before promotion." }
        return matches.singleOrNull() ?: File(directory, "${profile.uid}.arestuning")
    }

    private fun validateEvidence(projectPath: String, changes: List<TuningProfileChange>) {
        val projectRoot = File(projectPath).canonicalFile.toPath()
        changes.filter { change ->
            change.policy == TuningApplyPolicy.CALIBRATION_ONLY ||
                change.provenance.source.contains("live", ignoreCase = true) ||
                change.provenance.source.contains("autotuner", ignoreCase = true)
        }.forEach { change ->
            val relative = change.provenance.evidencePath
            val expectedHash = change.provenance.evidenceSha256
            require(!relative.isNullOrBlank() && !expectedHash.isNullOrBlank()) {
                "${change.displayName}: live/calibration promotion requires a project evidence file and SHA-256."
            }
            require(expectedHash.matches(Regex("[a-fA-F0-9]{64}"))) { "${change.displayName}: evidence SHA-256 is malformed." }
            val evidence = projectRoot.resolve(relative).normalize()
            require(evidence.startsWith(projectRoot)) { "${change.displayName}: evidence must stay inside the project." }
            require(Files.isRegularFile(evidence)) { "${change.displayName}: evidence file is missing: $relative" }
            val actual = sha256(Files.readAllBytes(evidence))
            require(actual.equals(expectedHash, ignoreCase = true)) { "${change.displayName}: evidence changed after this proposal was created." }
        }
    }

    private fun writeHistory(file: File, history: ReviewedTuningHistory) {
        val text = buildString {
            appendLine("profileUid=${history.profileUid}")
            appendLine("beforeSha256=${history.beforeHash}")
            appendLine("afterSha256=${history.afterHash}")
            appendLine("reviewedBy=${history.reviewedBy}")
            appendLine("summary=${history.reviewSummary.replace('\n', ' ')}")
            history.changes.forEach { appendLine("change=${it.parameterUid}|${it.before}|${it.after}|${it.provenance.source}|${it.provenance.note.replace('\n', ' ')}|${it.provenance.evidencePath.orEmpty()}|${it.provenance.evidenceSha256.orEmpty()}") }
        }
        atomicWrite(file, text)
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(content)
        runCatching { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun sha256(value: String) = sha256(value.toByteArray())
    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
}
