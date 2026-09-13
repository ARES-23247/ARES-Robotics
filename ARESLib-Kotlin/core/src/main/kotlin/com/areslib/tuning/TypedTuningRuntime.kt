package com.areslib.tuning

import com.google.gson.GsonBuilder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections

enum class TuningUpdateResult {
    APPLIED, UNKNOWN_PARAMETER, INVALID_VALUE, SESSION_NOT_ARMED, ROBOT_MUST_BE_DISABLED,
    RESTART_REQUIRED, REBUILD_REQUIRED, CALIBRATION_SESSION_REQUIRED, READ_ONLY_VENDOR,
    CONSUMER_REJECTED, APPLY_CALLBACK_FAILED,
}

data class TuningApplyContext(
    val sessionArmed: Boolean,
    val robotDisabled: Boolean,
    val calibrationParameterUids: Set<String> = emptySet(),
    /**
     * Platform-specific disabled equivalent: every owned actuator is neutral and the normal
     * control path is actively inhibited. FTC uses an acknowledged, fresh STOP lease because an
     * active OpMode has no WPILib-style Disabled lifecycle.
     */
    val outputsNeutralAndInhibited: Boolean = false,
)

/** Immutable UI/transport metadata. Runtime access uses pre-indexed arrays and does not serialize state. */
data class TuningMetadataSnapshot(
    val projectId: String,
    /** Selected drivebase, or null for a project containing only subsystem/global tuning. */
    val drivebaseUid: String?,
    val canonicalProfileUid: String,
    val declarations: List<TuningParameterDeclaration>,
    val profileUids: List<String>,
)

/**
 * Policy-aware typed tuning store. It never writes the canonical checked-in profile.
 * Values are indexed once; periodic typed reads allocate nothing.
 * Construction validates the initial values and owns snapshots of declaration/metadata lists.
 */
class TypedTuningRuntime(
    declarations: List<TuningParameterDeclaration>,
    canonicalValues: Map<String, TuningValue>,
    metadata: TuningMetadataSnapshot,
) {
    private val declarations = declarations.sortedBy { it.uid }.map(::snapshotDeclaration).toTypedArray()
    val metadata: TuningMetadataSnapshot = metadata.copy(
        declarations = Collections.unmodifiableList(metadata.declarations.map(::snapshotDeclaration)),
        profileUids = Collections.unmodifiableList(metadata.profileUids.toList()),
    )
    private val indices = this.declarations.mapIndexed { index, declaration -> declaration.uid to index }.toMap()
    private val values = Array(this.declarations.size) { index ->
        canonicalValues[this.declarations[index].uid] ?: this.declarations[index].defaultValue
    }
    private val canonicalSnapshot = values.copyOf()
    private val locallyChanged = BooleanArray(this.declarations.size)

    init {
        val declarationIssues = validateTuningParameterDeclarations(this.declarations.toList())
        require(declarationIssues.isEmpty()) { "Invalid tuning declarations: $declarationIssues" }
        require(this.metadata.declarations.size == this.declarations.size &&
            this.metadata.declarations.associateBy { it.uid } == this.declarations.associateBy { it.uid }) {
            "Tuning metadata must describe exactly the runtime declarations"
        }
        require(canonicalValues.keys.all(indices::containsKey)) { "Canonical values contain an unknown tuning parameter" }
        val initialProfile = TuningProfileDocument(
            uid = this.metadata.canonicalProfileUid, profileId = "runtime-initial", displayName = "Runtime initial values",
            description = "Validated initial tuning values", projectId = this.metadata.projectId,
            drivebaseUid = this.metadata.drivebaseUid, authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
            values = this.declarations.indices.map { TuningAssignment(this.declarations[it].uid, values[it]) },
        )
        val valueIssues = validateTuningProfileDocument(initialProfile, this.declarations.toList())
        require(valueIssues.isEmpty()) { "Invalid initial tuning values: $valueIssues" }
    }

    fun value(parameterUid: String): TuningValue? = indices[parameterUid]?.let(values::get)
    fun canonicalValue(parameterUid: String): TuningValue? = indices[parameterUid]?.let(canonicalSnapshot::get)
    fun double(parameterUid: String): Double = requireNotNull(value(parameterUid)?.doubleValue) { "'$parameterUid' is not a double parameter" }
    fun int(parameterUid: String): Int = requireNotNull(value(parameterUid)?.intValue) { "'$parameterUid' is not an integer parameter" }
    fun boolean(parameterUid: String): Boolean = requireNotNull(value(parameterUid)?.booleanValue) { "'$parameterUid' is not a boolean parameter" }
    fun text(parameterUid: String): String = requireNotNull(value(parameterUid)?.textValue) { "'$parameterUid' is not a text/enum parameter" }

    fun apply(parameterUid: String, candidate: TuningValue, context: TuningApplyContext): TuningUpdateResult {
        val index = indices[parameterUid] ?: return TuningUpdateResult.UNKNOWN_PARAMETER
        val declaration = declarations[index]
        if (validateTuningProfileDocument(
                TuningProfileDocument(
                    uid = "runtime.validation", profileId = "runtime-validation", displayName = "Runtime validation",
                    description = "Typed runtime candidate", projectId = metadata.projectId,
                    drivebaseUid = metadata.drivebaseUid,
                    authority = TuningProfileAuthority.LOCAL_EXPERIMENTAL,
                    values = listOf(TuningAssignment(parameterUid, candidate)),
                ),
                listOf(declaration),
            ).isNotEmpty()
        ) return TuningUpdateResult.INVALID_VALUE
        val policyResult = when (declaration.applyPolicy) {
            TuningApplyPolicy.LIVE_SAFE -> if (context.sessionArmed) null else TuningUpdateResult.SESSION_NOT_ARMED
            TuningApplyPolicy.DISABLED_ONLY -> when {
                !context.sessionArmed -> TuningUpdateResult.SESSION_NOT_ARMED
                !context.robotDisabled && !context.outputsNeutralAndInhibited ->
                    TuningUpdateResult.ROBOT_MUST_BE_DISABLED
                else -> null
            }
            TuningApplyPolicy.RESTART_REQUIRED -> TuningUpdateResult.RESTART_REQUIRED
            TuningApplyPolicy.REBUILD_REQUIRED -> TuningUpdateResult.REBUILD_REQUIRED
            TuningApplyPolicy.CALIBRATION_ONLY -> if (
                context.sessionArmed && parameterUid in context.calibrationParameterUids
            ) null else TuningUpdateResult.CALIBRATION_SESSION_REQUIRED
            TuningApplyPolicy.READ_ONLY_VENDOR -> TuningUpdateResult.READ_ONLY_VENDOR
        }
        if (policyResult != null) return policyResult
        values[index] = candidate
        locallyChanged[index] = candidate != canonicalSnapshot[index]
        return TuningUpdateResult.APPLIED
    }

    /** Restores the last robot-confirmed value if the consumer callback failed to commit it. */
    internal fun restoreAfterFailedApply(parameterUid: String, previous: TuningValue) {
        val index = requireNotNull(indices[parameterUid]) { "Unknown tuning parameter '$parameterUid'" }
        values[index] = previous
        locallyChanged[index] = previous != canonicalSnapshot[index]
    }

    /** Creates an experimental overlay only; callers choose an explicit robot-local path. */
    fun localOverlay(uid: String, profileId: String, displayName: String): TuningProfileDocument =
        TuningProfileDocument(
            uid = uid,
            profileId = profileId,
            displayName = displayName,
            description = "Robot-local experimental tuning overlay; not authoritative.",
            projectId = metadata.projectId,
            drivebaseUid = metadata.drivebaseUid,
            authority = TuningProfileAuthority.LOCAL_EXPERIMENTAL,
            baseProfileUid = metadata.canonicalProfileUid,
            values = declarations.indices.filter { locallyChanged[it] }
                .map { TuningAssignment(declarations[it].uid, values[it]) },
        )
}

private fun snapshotDeclaration(declaration: TuningParameterDeclaration): TuningParameterDeclaration =
    declaration.copy(enumOptions = Collections.unmodifiableList(declaration.enumOptions.toList()))

/** Persists only LOCAL_EXPERIMENTAL overlays, atomically, outside canonical `.ares/tuning`. */
object LocalTuningOverlayStore {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun writeAtomically(projectRoot: Path, output: Path, profile: TuningProfileDocument) {
        require(profile.authority == TuningProfileAuthority.LOCAL_EXPERIMENTAL) { "Only local experimental overlays may be persisted at runtime" }
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val allowedRoot = normalizedRoot.resolve(".ares/local/tuning")
        val normalizedOutput = output.toAbsolutePath().normalize()
        require(normalizedOutput.startsWith(allowedRoot) && normalizedOutput.toString().endsWith(".arestuning")) {
            "Runtime overlays must stay under .ares/local/tuning and use .arestuning"
        }
        Files.createDirectories(normalizedOutput.parent)
        val temporary = Files.createTempFile(normalizedOutput.parent, ".${normalizedOutput.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, gson.toJson(profile.copy(values = profile.values.sortedBy { it.parameterUid })))
            try {
                Files.move(temporary, normalizedOutput, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, normalizedOutput, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
