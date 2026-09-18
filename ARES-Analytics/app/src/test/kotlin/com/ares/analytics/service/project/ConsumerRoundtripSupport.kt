package com.ares.analytics.service.project

import com.ares.analytics.service.ManagedToolchainPaths
import com.ares.analytics.service.ProjectBuildService
import com.ares.analytics.service.ProjectProcessGate
import com.ares.analytics.service.ProjectProcessCommandFactory
import com.ares.analytics.service.terminateProcessTree
import com.ares.analytics.shared.models.League
import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.util.Sha256
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.tuning.TuningProfileDocumentCodec
import com.areslib.tuning.TuningValue
import com.ares.analytics.service.tuning.TuningProfileChange
import com.ares.analytics.service.tuning.TuningValueOwner
import com.ares.analytics.service.tuning.TuningValueProvenance
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Uses the production gate to observe scheduling without racing a short-lived RUNNING state. */
internal class ConsumerRoundtripBuild(
    private val evidenceName: String,
    repository: String? = System.getProperty("ares.consumer.repository"),
    version: String? = System.getProperty("ares.consumer.version"),
) {
    private val gate = ProjectProcessGate()
    private val commands = ProjectProcessCommandFactory(repository, version, ManagedToolchainPaths.gradleJavaInstallations())
    val service = ProjectBuildService(repository, version, operationGate = gate)
    private var operation = 0

    suspend fun generate(project: File) = execute(project) { service.generateAresProject(project.path, League.FTC) }
    suspend fun verify(project: File) = execute(project) { service.runBuild(project.path, League.FTC) }

    /** Verify existing files without runBuild's intentional regenerate-before-verify behavior. */
    suspend fun verifyExistingFiles(project: File): Int = withContext(Dispatchers.IO) {
        val log = File(project, "build/roundtrip-verify-existing.log").apply { parentFile.mkdirs() }
        val windows = System.getProperty("os.name").contains("win", ignoreCase = true)
        // The normal verifier materializes disposable plumbing first. Exclude that setup only in
        // this focused oracle so --check must inspect the existing outputs without repairing them.
        val command = commands.authoring(League.FTC, ":TeamCode:verifyAresProject", windows) +
            listOf("-x", ":TeamCode:prepareAresSubsystemPlumbing")
        val process = commands.configureEnvironment(ProcessBuilder(command).directory(project)
            .redirectErrorStream(true).redirectOutput(log)).start()
        try {
            withTimeout(180_000L) { while (process.isAlive) delay(25) }
            process.exitValue()
        } finally {
            if (process.isAlive) terminateProcessTree(process)
            System.getProperty("ares.consumer.evidenceDir")?.let { root ->
                val destination = File(root, "$evidenceName/verify-existing-${++operation}.log").apply { parentFile.mkdirs() }
                log.copyTo(destination, overwrite = true)
            }
        }
    }

    private suspend fun execute(project: File, start: () -> Unit) {
        check(!service.processState.value.buildRunning)
        try {
            withTimeout(240_000L) {
                gate.runExclusive {
                    start()
                    // The submitted job sets ownership/running before waiting on this gate. It cannot
                    // complete until we release it, even for immediate preflight failure or repeat success.
                    while (!service.processState.value.buildRunning) delay(10)
                }
                service.awaitBuildIdleForTest()
            }
        } finally {
            retainEvidence(project)
        }
    }

    private fun retainEvidence(project: File) {
        val root = System.getProperty("ares.consumer.evidenceDir")?.let(::File) ?: return
        val evidence = File(root, evidenceName).apply { mkdirs() }
        File(evidence, "operation-${++operation}.txt").writeText(buildString {
            appendLine(service.aresGenerationState.value)
            appendLine(service.processState.value.buildExecution)
            service.buildOutput.replayCache.forEach { appendLine(it) }
        })
        for (path in listOf("TeamCode/build/test-results", "simulator/build/test-results")) {
            val source = File(project, path)
            if (source.isDirectory) source.copyRecursively(File(evidence, path), overwrite = true)
        }
    }
}

internal fun configureConsumerSdk(project: File) {
    val sdk = requireNotNull(ManagedToolchainPaths.resolveAndroidSdk()) {
        "consumerRoundtripTest requires an Android SDK; set ANDROID_HOME or ANDROID_SDK_ROOT."
    }
    require(sdk.isDirectory) { "Android SDK directory does not exist: $sdk" }
    File(project, "local.properties").writeText("sdk.dir=${sdk.path.replace('\\', '/')}\n")
}

internal fun saveConsumerMetadata(project: File, document: AresProjectMetadataDocument) {
    val repository = ProjectMetadataRepository()
    val previous = repository.load(project.path).getOrThrow()
    val reviewedHash = Sha256.hex(AresProjectMetadataCodec.encode(previous))
    repository.saveReviewed(project.path, reviewedHash, document)
}

internal fun consumerCanonicalSnapshot(project: File): Map<String, String> {
    val ares = File(project, ".ares")
    return ares.walkTopDown().filter { it.isFile && !it.relativeTo(ares).invariantSeparatorsPath.startsWith("local/") }
        .associate { it.relativeTo(ares).invariantSeparatorsPath to Sha256.fileHex(it) }
}

internal fun saveConsumerHeadingGain(session: ProjectSession, project: File, gain: Double) {
    val snapshot = session.snapshot(project.path, ControllerInputPlatform.FTC, forceReload = true)
    val declarations = snapshot.documents.query.tuningParameters
    val profile = snapshot.documents.query.tuningProfiles.single { it.profileId == "simulation" }
    val parameter = declarations.single { it.uid == "ftc.drive.heading.kp" }
    val change = TuningProfileChange(
        parameterUid = parameter.uid, key = parameter.key, displayName = parameter.displayName,
        before = profile.values.single { it.parameterUid == parameter.uid }.value,
        after = TuningValue(doubleValue = gain), unit = parameter.unit.orEmpty(),
        owner = TuningValueOwner.ROBOT_PROFILE, policy = parameter.applyPolicy,
        provenance = TuningValueProvenance("integration fixture", "Independent expected heading gain"),
    )
    val result = session.promoteTuningProfile(
        snapshot.revision, profile, TuningProfileDocumentCodec.contentHash(profile, declarations),
        declarations, listOf(change), "consumer roundtrip test", "Verify saved configuration through export and reopen",
    )
    check(result is ProjectSessionMutationResult.Applied) { "Tuning save failed: $result" }
}
