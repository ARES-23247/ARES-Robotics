package com.ares.analytics.service.tuning

import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.tuning.*
import com.ares.analytics.util.Sha256
import com.ares.analytics.service.resolveExistingPath
import com.ares.analytics.service.writeFileAtomically
import com.ares.analytics.service.project.persistence.AtomicProjectFileWriter
import com.ares.analytics.service.project.persistence.ProjectDocumentWriteLocks
import com.ares.analytics.service.project.persistence.ProjectMutationTransaction
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files

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
    internal var beforeCanonicalReplace: () -> Unit = {}
    fun evidenceErrors(projectPath: String, changes: List<TuningProfileChange>): List<String> =
        runCatching { validateEvidence(projectPath, changes) }.exceptionOrNull()?.message?.let(::listOf).orEmpty()

    fun load(projectPath: String): Result<TuningWorkspaceDocuments> =
        loadInternal(projectPath, allowUnknownAssignments = false)

    /**
     * Allows one reviewed drivebase edit to remove or replace declarations and their assignments
     * atomically. Project/profile identity remains strict; only assignment references made obsolete
     * by the current edit are tolerated until the transaction writes the new documents.
     */
    internal fun loadForDrivebaseEdit(projectPath: String): Result<TuningWorkspaceDocuments> =
        loadInternal(projectPath, allowUnknownAssignments = true)

    private fun loadInternal(projectPath: String, allowUnknownAssignments: Boolean): Result<TuningWorkspaceDocuments> = runCatching {
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
                if (allowUnknownAssignments) decodeForDrivebaseEdit(file.readText(), declarations)
                else TuningProfileDocumentCodec.decode(file.readText(), declarations)
            }
            ?.sortedBy { it.displayName }.orEmpty()
        require(profiles.map { it.uid }.distinct().size == profiles.size) { "Tuning profile UIDs must be unique across the project." }
        require(profiles.map { it.profileId }.distinct().size == profiles.size) { "Tuning profile IDs must be unique across the project." }
        require(profiles.all { it.authority == TuningProfileAuthority.CANONICAL_CHECKED_IN }) {
            "Only checked-in canonical profiles belong in .ares/tuning; local experiments belong in .ares/local/tuning."
        }
        require(profiles.map { it.projectId }.distinct().size <= 1) {
            "Every tuning profile must target the same robot project."
        }
        val resolvableProfiles = if (allowUnknownAssignments) {
            val declaredUids = declarations.mapTo(hashSetOf()) { it.uid }
            profiles.map { profile ->
                profile.copy(values = profile.values.filter { it.parameterUid in declaredUids })
            }
        } else profiles
        resolveTuningProfiles(resolvableProfiles, declarations)
        TuningWorkspaceDocuments(declarations, profiles)
    }

    private fun decodeForDrivebaseEdit(
        text: String,
        declarations: Collection<TuningParameterDeclaration>,
    ): TuningProfileDocument {
        val profile = runCatching {
            gson.fromJson(text, TuningProfileDocument::class.java)
        }.getOrElse { throw IllegalArgumentException("Invalid tuning profile: ${it.message}", it) }
        val blockingIssues = validateTuningProfileDocument(profile, declarations).filterNot { issue ->
            issue.path.matches(unknownAssignmentPath) && issue.message.startsWith("Unknown parameter '")
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
        val root = File(projectPath).toPath().toRealPath().toFile()
        require(root.isDirectory) { "Project directory is missing." }
        val lockTarget = ownedPath(root, ".ares/.project-mutation-transaction")
        return ProjectDocumentWriteLocks.withLock(lockTarget) {
            ownedPath(root, ".ares/recovery/transactions")
            ProjectMutationTransaction.recover(root)
            promoteLocked(root, current, expectedContentHash, declarations, changes, reviewedBy, reviewSummary)
        }
    }

    private fun promoteLocked(
        projectRoot: File,
        current: TuningProfileDocument,
        expectedContentHash: String,
        declarations: List<TuningParameterDeclaration>,
        changes: List<TuningProfileChange>,
        reviewedBy: String,
        reviewSummary: String,
    ): TuningProfileDocument {
        val projectPath = projectRoot.path
        require(changes.isNotEmpty()) { "Review at least one change before promotion." }
        require(reviewedBy.isNotBlank() && reviewSummary.isNotBlank()) { "Reviewer and review summary are required." }
        require(TuningProfileDocumentCodec.contentHash(current, declarations) == expectedContentHash) {
            "The reviewed profile does not match its revision. Reload and review a fresh diff."
        }
        require(changes.map { it.parameterUid }.distinct().size == changes.size) { "Review each parameter only once." }
        val catalog = declarations.associateBy { it.uid }
        changes.forEach { change ->
            val declaration = requireNotNull(catalog[change.parameterUid]) { "Unknown tuning parameter ${change.parameterUid}." }
            require(change.key == declaration.key && change.policy == declaration.applyPolicy && change.owner == declaration.owner()) {
                "${declaration.displayName}: declaration changed after review. Reload the proposal."
            }
            require(declaration.applyPolicy != TuningApplyPolicy.READ_ONLY_VENDOR) { "${declaration.displayName} is vendor-owned and read-only." }
        }
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
        val historyRelative = ".ares/history/tuning/${current.uid}"
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
        val proposalRelative = "$historyRelative/proposals/$proposalHash.arestuning"
        val proposalFile = ownedPath(projectRoot, proposalRelative)
        if (proposalFile.exists()) require(proposalFile.readText() == proposalText) { "Immutable proposal snapshot hash collision." }
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
        val backup = ownedPath(projectRoot, "$historyRelative/${diskHash.take(16)}.arestuning")
        val history = ownedPath(projectRoot, "$historyRelative/${afterHash.take(16)}.review.txt")
        if (backup.exists()) require(TuningProfileDocumentCodec.contentHash(
            TuningProfileDocumentCodec.decode(backup.readText(), declarations), declarations) == diskHash) {
            "Immutable canonical backup hash collision."
        }
        // Snapshot only the four touched files, not an ever-growing history directory. The outer
        // project lock covers the revision check as well as this recoverable multi-file commit.
        return ProjectMutationTransaction.run(projectRoot, "promote-tuning",
            listOf(file, proposalFile, backup, history).map { it.relativeTo(projectRoot).invariantSeparatorsPath }) {
            if (!proposalFile.exists()) AtomicProjectFileWriter.write(proposalFile, proposalText, replaceExisting = false)
            if (!backup.exists()) AtomicProjectFileWriter.write(backup, file.readBytes(), replaceExisting = false)
            writeHistory(history, ReviewedTuningHistory(current.uid, diskHash, afterHash, reviewedBy, reviewSummary, changes))
            writeFileAtomically(file, beforeReplace = { _, _ -> beforeCanonicalReplace() }) { it.writeText(encoded) }
            promoted
        }
    }

    fun reviewToken(profile: TuningProfileDocument, declarations: List<TuningParameterDeclaration>, changes: List<TuningProfileChange>, reviewedBy: String, reviewSummary: String): String {
        val hash = TuningProfileDocumentCodec.contentHash(profile, declarations)
        // Typed JSON binds exact numeric values and field boundaries; display formatting is lossy
        // and delimiter concatenation lets text move between independently reviewed fields.
        val canonical = gson.toJson(listOf(hash, declarations.sortedBy { it.uid }, changes, reviewedBy, reviewSummary))
        return Sha256.hex(canonical)
    }

    private fun profileFile(
        projectPath: String,
        profile: TuningProfileDocument,
        declarations: List<TuningParameterDeclaration>
    ): File {
        val root = File(projectPath)
        val directory = ownedPath(root, ".ares/tuning")
        val matches = directory.listFiles { file -> file.extension == "arestuning" }
            ?.filter { file ->
                ownedPath(root, file.relativeTo(root).invariantSeparatorsPath)
                runCatching { TuningProfileDocumentCodec.decode(file.readText(), declarations) }
                    .getOrNull()?.let { it.uid == profile.uid } == true
            }.orEmpty()
        require(matches.size <= 1) { "Multiple canonical files claim profile UID ${profile.uid}. Resolve the duplicate before promotion." }
        return matches.singleOrNull() ?: ownedPath(root, ".ares/tuning/${profile.uid}.arestuning")
    }

    private fun validateEvidence(projectPath: String, changes: List<TuningProfileChange>) {
        val projectRoot = File(projectPath).toPath().toRealPath()
        changes.filter { change ->
            change.policy == TuningApplyPolicy.CALIBRATION_ONLY ||
                change.provenance.source.contains("live", ignoreCase = true) ||
                change.provenance.source.contains("autotuner", ignoreCase = true) ||
                change.provenance.evidencePath != null || change.provenance.evidenceSha256 != null
        }.forEach { change ->
            val relative = change.provenance.evidencePath
            val expectedHash = change.provenance.evidenceSha256
            require(!relative.isNullOrBlank() && !expectedHash.isNullOrBlank()) {
                "${change.displayName}: live/calibration promotion requires a project evidence file and SHA-256."
            }
            require(expectedHash.matches(Regex("[a-fA-F0-9]{64}"))) { "${change.displayName}: evidence SHA-256 is malformed." }
            require(!relative.startsWith('/') && '\\' !in relative && ':' !in relative &&
                relative.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "${change.displayName}: evidence must use a project-relative path."
            }
            val evidence = resolveExistingPath(projectRoot.resolve(relative))
            require(evidence.startsWith(projectRoot)) { "${change.displayName}: evidence must stay inside the project." }
            require(Files.isRegularFile(evidence)) { "${change.displayName}: evidence file is missing: $relative" }
            val actual = Sha256.fileHex(evidence.toFile())
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
        writeFileAtomically(target) { it.writeText(content) }
    }

    private fun ownedPath(root: File, relative: String): File {
        val path = root.toPath().resolve(relative).normalize()
        require(path.startsWith(root.toPath()) && resolveExistingPath(path) == path) {
            "Canonical tuning and its history must not be redirected through filesystem aliases."
        }
        return path.toFile()
    }

    private companion object {
        val gson = GsonBuilder().serializeNulls().create()
        val unknownAssignmentPath = Regex("values\\[\\d+].parameterUid")
    }

}
