package com.ares.analytics.viewmodel

import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.tuning.*
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.areslib.controls.ControllerInputPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.ares.analytics.shared.TelemetryFrame

data class BackupInfo(val filename: String, val formattedDate: String, val filePath: String, val count: Int)

data class TuningState(
    /** Live values are observational and never mutate the robot-owned profile. */
    val variables: Map<String, Double> = emptyMap(),
    /** Full typed observations keyed by declaration key. */
    val liveTypedValues: Map<String, TuningValue> = emptyMap(),
    /** Runtime support keyed by stable declaration UID. Missing means an older/unknown robot. */
    val consumerSupportByUid: Map<String, Boolean> = emptyMap(),
    val projectPath: String = "",
    val projectRevision: ProjectSessionRevision? = null,
    val catalog: TuningComponentCatalog = emptyList(),
    val profiles: List<RobotTuningProfile> = emptyList(),
    val selectedProfileId: String = "competition",
    val proposals: Map<String, TuningValue> = emptyMap(),
    val proposalProvenance: Map<String, TuningValueProvenance> = emptyMap(),
    val review: TuningProposalReview? = null,
    val reviewerName: String = "",
    val reviewSummary: String = "",
    val availableBackups: List<BackupInfo> = emptyList(),
    val isLoading: Boolean = false,
    val saveStatus: String = "",
    val errorMessage: String? = null
) {
    val selectedProfile: RobotTuningProfile?
        get() = profiles.firstOrNull { it.profileId == selectedProfileId }

    val rows: List<ResolvedTuningValue>
        get() = selectedProfile?.let { resolveTuningProfile(it, profiles, catalog, variables, proposals, liveTypedValues, proposalProvenance) }.orEmpty()
}

sealed class TuningIntent {
    data class LoadConstants(val projectPath: String) : TuningIntent()
    data class SelectProfile(val profileId: String) : TuningIntent()
    /** Stages a proposal only. It never writes a file or publishes NT4. */
    data class UpdateAppConstant(val key: String, val newValue: Double) : TuningIntent()
    data class UpdateTypedConstant(val key: String, val newValue: TuningValue) : TuningIntent()
    data class InvalidateTypedConstant(val key: String, val message: String) : TuningIntent()
    data class SetProposalProvenance(
        val key: String,
        val source: String,
        val note: String,
        val evidencePath: String? = null,
        val evidenceSha256: String? = null
    ) : TuningIntent()
    data class SetReviewerName(val value: String) : TuningIntent()
    data class SetReviewSummary(val value: String) : TuningIntent()
    data class PushToRobot(val key: String) : TuningIntent()
    data class PullFromRobot(val key: String) : TuningIntent()
    object PushAllToRobot : TuningIntent()
    object PullAllFromRobot : TuningIntent()
    object ReviewPromotion : TuningIntent()
    data class ConfirmPromotion(val confirmationToken: String) : TuningIntent()
    data class RemoveProposal(val key: String) : TuningIntent()
    object DiscardProposal : TuningIntent()
    object CreateBackup : TuningIntent()
    data class LoadBackup(val filename: String) : TuningIntent()
    object RefreshBackups : TuningIntent()
    object ClearSaveStatus : TuningIntent()
}

class TuningViewModel(
    val nt4ClientService: Nt4ClientService,
    private val scope: CoroutineScope,
    private val repository: TuningProfileRepository = TuningProfileRepository(),
    proposalInbox: TuningProposalInbox? = null,
    private val checkpointRecorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
    private val projectSession: ProjectSession? = null,
    private val targetPlatform: ControllerInputPlatform? = null,
) {
    private var requestNonce = 0L
    /** Serializes multi-parameter live tests so every Requested value receives a unique nonce. */
    private val requestMutex = Mutex()
    private val _state = MutableStateFlow(TuningState())
    val state: StateFlow<TuningState> = _state.asStateFlow()

    init {
        scope.launch {
            nt4ClientService.isConnected.collect { connected ->
                if (!connected) requestNonce = 0L
            }
        }
        proposalInbox?.let { inbox ->
            scope.launch {
                inbox.proposals.collect { proposal ->
                    proposal.values.forEach { (key, value) ->
                        stage(key, value, proposal.source, proposal.summary, proposal.evidencePath, proposal.evidenceSha256)
                    }
                    reviewPromotion()
                }
            }
        }
        scope.launch {
            while (isActive) {
                val declarations = _state.value.catalog
                val typed = declarations.mapNotNull { declaration ->
                    val frame = nt4ClientService.latestValues[TuningTransport.current(declaration)] ?: return@mapNotNull null
                    frame.toTuningValue(declaration)?.let { declaration.key to it }
                }.toMap()
                val numeric = typed.mapNotNull { (key, value) -> value.numericValue()?.let { key to it } }.toMap()
                val consumerSupport = declarations.mapNotNull { declaration ->
                    val frame = nt4ClientService.latestValues[TuningTransport.consumerSupported(declaration)]
                        ?: return@mapNotNull null
                    declaration.uid to (frame.stringValue?.toBooleanStrictOrNull() ?: (frame.value != 0.0))
                }.toMap()
                if (_state.value.variables != numeric ||
                    _state.value.liveTypedValues != typed ||
                    _state.value.consumerSupportByUid != consumerSupport
                ) {
                    _state.update {
                        it.copy(
                            variables = numeric,
                            liveTypedValues = typed,
                            consumerSupportByUid = consumerSupport,
                        )
                    }
                }
                delay(200)
            }
        }
    }

    fun onIntent(intent: TuningIntent) {
        when (intent) {
            is TuningIntent.LoadConstants -> load(intent.projectPath)
            is TuningIntent.SelectProfile -> selectProfile(intent.profileId)
            is TuningIntent.UpdateAppConstant -> stage(intent.key, intent.newValue, "Student edit", "Entered in the ARES tuning proposal board.")
            is TuningIntent.UpdateTypedConstant -> stageTyped(intent.key, intent.newValue, "Student edit", "Entered in the ARES tuning proposal board.")
            is TuningIntent.InvalidateTypedConstant -> _state.update {
                it.copy(
                    proposals = it.proposals - intent.key,
                    proposalProvenance = it.proposalProvenance - intent.key,
                    review = null,
                    saveStatus = "",
                    errorMessage = intent.message,
                )
            }
            is TuningIntent.SetProposalProvenance -> _state.update {
                it.copy(proposalProvenance = it.proposalProvenance + (intent.key to TuningValueProvenance(intent.source, intent.note, intent.evidencePath, intent.evidenceSha256)), review = null)
            }
            is TuningIntent.SetReviewerName -> _state.update { it.copy(reviewerName = intent.value, review = null) }
            is TuningIntent.SetReviewSummary -> _state.update { it.copy(reviewSummary = intent.value, review = null) }
            is TuningIntent.PushToRobot -> pushOne(intent.key)
            is TuningIntent.PullFromRobot -> pullOne(intent.key)
            TuningIntent.PushAllToRobot -> pushAllExperimental()
            TuningIntent.PullAllFromRobot -> pullAll()
            TuningIntent.ReviewPromotion -> reviewPromotion()
            is TuningIntent.ConfirmPromotion -> confirmPromotion(intent.confirmationToken)
            is TuningIntent.RemoveProposal -> _state.update {
                it.copy(
                    proposals = it.proposals - intent.key,
                    proposalProvenance = it.proposalProvenance - intent.key,
                    review = null,
                    saveStatus = "Removed the local experiment proposal. Canonical files were not changed.",
                    errorMessage = null,
                )
            }
            TuningIntent.DiscardProposal -> _state.update { it.copy(proposals = emptyMap(), proposalProvenance = emptyMap(), review = null, saveStatus = "Discarded proposed values. The profile and robot were not changed.") }
            TuningIntent.CreateBackup -> _state.update { it.copy(saveStatus = "Canonical profiles are backed up automatically before promotion.") }
            is TuningIntent.LoadBackup -> _state.update { it.copy(errorMessage = "Use profile history to review old canonical content; backups never replace the active profile directly.") }
            TuningIntent.RefreshBackups -> Unit
            TuningIntent.ClearSaveStatus -> _state.update { it.copy(saveStatus = "", errorMessage = null) }
        }
    }

    private fun load(projectPath: String) = scope.launch {
        if (projectPath.isBlank()) {
            _state.value = TuningState()
            return@launch
        }
        _state.update { it.copy(isLoading = true, projectPath = projectPath, errorMessage = null) }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val snapshot = targetPlatform?.let { projectSession?.snapshot(projectPath, it, forceReload = true) }
                repository.load(projectPath).getOrThrow() to snapshot?.revision
            }
        }
        val result = loaded.map { it.first }
        val projectRevision = loaded.getOrNull()?.second
        result.fold(onSuccess = { docs ->
            val selected = docs.profiles.firstOrNull { it.profileId == _state.value.selectedProfileId } ?: docs.profiles.firstOrNull()
            _state.update { it.copy(
                catalog = docs.catalog,
                projectRevision = projectRevision,
                profiles = docs.profiles,
                selectedProfileId = selected?.profileId.orEmpty(),
                proposals = emptyMap(), proposalProvenance = emptyMap(), review = null,
                isLoading = false, saveStatus = if (selected == null) "Loaded ${docs.catalog.size} declarations; no canonical profile exists yet." else "Loaded ${docs.catalog.size} declared values from ${selected.displayName}.", errorMessage = null
            ) }
        }, onFailure = { failure -> _state.update { it.copy(isLoading = false, errorMessage = failure.message ?: "Could not load tuning profiles.") } })
    }

    private fun selectProfile(profileId: String) {
        val state = _state.value
        val profile = state.profiles.firstOrNull { it.profileId == profileId } ?: return
        _state.update { it.copy(selectedProfileId = profileId, proposals = emptyMap(), proposalProvenance = emptyMap(), review = null, saveStatus = "Switched to ${profile.displayName}. Unsaved proposals were cleared.") }
    }

    private fun stage(
        key: String,
        value: Double,
        source: String,
        note: String,
        evidencePath: String? = null,
        evidenceSha256: String? = null
    ) {
        val declaration = _state.value.catalog.firstOrNull { it.key == key }
        val typed = when (declaration?.type) {
            TuningParameterType.INT -> value.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.let { TuningValue(intValue = it.toInt()) }
            else -> TuningValue(doubleValue = value)
        }
        if (typed == null) {
            _state.update { it.copy(errorMessage = "Enter a whole number in the supported Int range for $key.") }
            return
        }
        stageTyped(key, typed, source, note, evidencePath, evidenceSha256)
    }

    private fun stageTyped(
        key: String,
        value: TuningValue,
        source: String,
        note: String,
        evidencePath: String? = null,
        evidenceSha256: String? = null
    ) {
        val declaration = _state.value.catalog.firstOrNull { it.key == key }
        if (declaration == null) {
            _state.update { it.copy(errorMessage = "$key is not declared by a robot component.") }
            return
        }
        if (declaration.applyPolicy == TuningApplyPolicy.READ_ONLY_VENDOR) {
            _state.update { it.copy(errorMessage = "${declaration.displayName} is vendor-owned. Re-import its source instead of editing it.") }
            return
        }
        _state.update { it.copy(
            proposals = it.proposals + (key to value),
            proposalProvenance = it.proposalProvenance + (key to TuningValueProvenance(source, note, evidencePath, evidenceSha256)),
            review = null,
            saveStatus = "Staged ${declaration.displayName}. Nothing has been written or pushed.",
            errorMessage = null
        ) }
    }

    private fun pullOne(key: String) {
        val live = _state.value.liveTypedValues[key]
        if (live == null) _state.update { it.copy(errorMessage = "No live value is available for $key.") }
        else stageTyped(key, live, "Live robot observation", "Copied into the proposal by the student; connection alone never changes profiles.")
    }

    private fun pullAll() {
        val state = _state.value
        state.liveTypedValues.forEach { (key, value) ->
            val declaration = state.catalog.firstOrNull { it.key == key }
            if (declaration != null && declaration.applyPolicy != TuningApplyPolicy.READ_ONLY_VENDOR) stageTyped(key, value, "Live robot observation", "Explicitly copied into a proposal by the student.")
        }
    }

    private fun pushOne(key: String) = scope.launch {
        val state = _state.value
        val declaration = state.catalog.firstOrNull { it.key == key }
        val value = state.proposals[key]
        when {
            declaration == null -> _state.update { it.copy(errorMessage = "$key is undeclared and cannot be pushed.") }
            state.consumerSupportByUid[declaration.uid] == false -> _state.update {
                it.copy(errorMessage = "${declaration.displayName} has no compiled runtime consumer in the connected robot. Regenerate or update the robot project before live testing.")
            }
            declaration.applyPolicy != TuningApplyPolicy.LIVE_SAFE -> _state.update { it.copy(errorMessage = "${declaration.displayName} is ${declaration.applyPolicy.name.lowercase().replace('_', ' ')} and cannot be live-pushed.") }
            value == null -> _state.update { it.copy(errorMessage = "Stage and review a proposed value before live testing.") }
            buildTuningReview(state.selectedProfile ?: return@launch, state.profiles, state.catalog, mapOf(key to value), state.proposalProvenance).second.isNotEmpty() -> _state.update { it.copy(errorMessage = "${declaration.displayName} is invalid for live testing.") }
            else -> requestMutex.withLock {
                runCatching {
                    val observedNonce = nt4ClientService.latestValues[TuningTransport.requestNonce(declaration)]?.value
                    val nextNonce = nextTuningRequestNonce(requestNonce, observedNonce)
                    when (declaration.type) {
                        TuningParameterType.DOUBLE -> nt4ClientService.publishDouble(TuningTransport.requested(declaration), requireNotNull(value.doubleValue))
                        TuningParameterType.INT -> nt4ClientService.publishDouble(TuningTransport.requested(declaration), requireNotNull(value.intValue).toDouble())
                        TuningParameterType.BOOLEAN -> nt4ClientService.publishBoolean(TuningTransport.requested(declaration), requireNotNull(value.booleanValue))
                        TuningParameterType.TEXT, TuningParameterType.ENUM -> nt4ClientService.publishString(TuningTransport.requested(declaration), requireNotNull(value.textValue))
                    }
                    requestNonce = nextNonce
                    nt4ClientService.publishDouble(TuningTransport.requestNonce(declaration), requestNonce.toDouble())
                    _state.update { it.copy(saveStatus = "Waiting for ${declaration.displayName} acknowledgement…", errorMessage = null) }
                    val result = awaitTuningResult(declaration, requestNonce)
                    require(result == "APPLIED") {
                        "Robot rejected ${declaration.displayName}: ${result.lowercase().replace('_', ' ')}. The profile was not changed."
                    }
                }
                    .onSuccess { _state.update { it.copy(saveStatus = "Robot acknowledged ${declaration.displayName} as applied experimentally. The profile was not changed.", errorMessage = null) } }
                    .onFailure { failure -> _state.update {
                        it.copy(
                            saveStatus = "No robot acknowledgement was received; the experimental result is unknown.",
                            errorMessage = failure.message ?: "Live push failed.",
                        )
                    } }
            }
        }
    }

    private suspend fun awaitTuningResult(declaration: TuningParameterDeclaration, nonce: Long): String {
        repeat(30) {
            val processed = nt4ClientService.latestValues[TuningTransport.processedNonce(declaration)]?.value
            if (processed == nonce.toDouble()) {
                return nt4ClientService.latestValues[TuningTransport.lastResult(declaration)]?.stringValue
                    ?: error("Robot acknowledged the request without a result.")
            }
            delay(100)
        }
        error("Robot did not acknowledge the live tuning request within 3 seconds. Treat the result as unknown.")
    }

    private fun pushAllExperimental() {
        val eligible = _state.value.proposals.keys.filter { key ->
            _state.value.catalog.firstOrNull { it.key == key }?.let { declaration ->
                declaration.applyPolicy == TuningApplyPolicy.LIVE_SAFE &&
                    _state.value.consumerSupportByUid[declaration.uid] != false
            } == true
        }
        if (eligible.isEmpty()) _state.update { it.copy(errorMessage = "No reviewed experimental-live proposals are available.") }
        else eligible.forEach(::pushOne)
    }

    private fun reviewPromotion() {
        val state = _state.value
        val profile = state.selectedProfile ?: return
        val (changes, valueErrors) = buildTuningReview(profile, state.profiles, state.catalog, state.proposals, state.proposalProvenance)
        val errors = valueErrors + buildList {
            if (state.reviewerName.isBlank()) add("Enter the reviewer name.")
            if (state.reviewSummary.isBlank()) add("Enter a review summary.")
            if (changes.any { it.policy == TuningApplyPolicy.CALIBRATION_ONLY && (it.provenance.evidencePath.isNullOrBlank() || it.provenance.evidenceSha256.isNullOrBlank()) }) add("Calibration-only changes require a project evidence path and SHA-256 before promotion.")
        } + repository.evidenceErrors(state.projectPath, changes)
        val hash = com.areslib.tuning.TuningProfileDocumentCodec.contentHash(profile, state.catalog)
        val review = TuningProposalReview(profile.profileId, hash, changes, errors, repository.reviewToken(profile, state.catalog, changes, state.reviewerName, state.reviewSummary), state.reviewerName, state.reviewSummary)
        _state.update { it.copy(review = review, errorMessage = errors.firstOrNull(), saveStatus = if (review.canPromote) "Review ${changes.size} structured profile changes, then confirm promotion." else "Promotion is blocked by validation.") }
    }

    private fun confirmPromotion(token: String) = scope.launch {
        val state = _state.value
        val profile = state.selectedProfile ?: return@launch
        val review = state.review
        val currentHash = com.areslib.tuning.TuningProfileDocumentCodec.contentHash(profile, state.catalog)
        if (review == null || !review.canPromote || token != review.confirmationToken || review.baseContentHash != currentHash) {
            _state.update { it.copy(errorMessage = "The confirmation is missing or stale. Review a fresh structured diff.") }
            return@launch
        }
        runCatching {
            withContext(Dispatchers.IO) {
                val session = projectSession
                val revision = state.projectRevision
                if (session != null && revision != null) {
                    when (
                        val result = session.promoteTuningProfile(
                            revision,
                            profile,
                            review.baseContentHash,
                            state.catalog,
                            review.changes,
                            review.reviewedBy,
                            review.reviewSummary,
                        )
                    ) {
                        is ProjectSessionMutationResult.Applied -> result.value to result.snapshot.revision
                        is ProjectSessionMutationResult.Stale -> error("The project changed after this tuning profile loaded. Reload before promotion.")
                        is ProjectSessionMutationResult.Conflict -> error(result.message)
                        is ProjectSessionMutationResult.Failed -> error(result.message)
                    }
                } else {
                    repository.promote(state.projectPath, profile, review.baseContentHash, state.catalog, review.changes, review.reviewedBy, review.reviewSummary) to null
                }
            }
        }
            .fold(onSuccess = { (promoted, revision) ->
                val profiles = state.profiles.map { if (it.profileId == promoted.profileId) promoted else it }
                _state.update { it.copy(profiles = profiles, projectRevision = revision ?: it.projectRevision, proposals = emptyMap(), proposalProvenance = emptyMap(), review = null, reviewerName = "", reviewSummary = "", saveStatus = "Promoted canonical profile atomically. Robot values were not pushed.", errorMessage = null) }
                scope.launch {
                    runCatching {
                        recordTuningPromotionCheckpoint(
                            recorder = checkpointRecorder,
                            projectPath = state.projectPath,
                            profileDisplayName = promoted.displayName,
                            reviewSummary = review.reviewSummary,
                        )
                    }.onFailure { failure ->
                        _state.update {
                            it.copy(
                                saveStatus = "Tuning profile promoted, but automatic Project History checkpoint failed: ${failure.message}",
                            )
                        }
                    }
                }
            }, onFailure = { failure -> _state.update { it.copy(errorMessage = failure.message ?: "Profile promotion failed.") } })
    }
}

internal suspend fun recordTuningPromotionCheckpoint(
    recorder: ProjectCheckpointRecorder,
    projectPath: String,
    profileDisplayName: String,
    reviewSummary: String,
) = recorder.checkpoint(
    projectPath = projectPath,
    label = "Promoted $profileDisplayName tuning profile: $reviewSummary",
    pathScopes = setOf(".ares/tuning", ".ares/history/tuning"),
)

private const val MAX_SAFE_REQUEST_NONCE = 9_007_199_254_740_991L

internal fun nextTuningRequestNonce(local: Long, observed: Double?): Long {
    val observedLong = observed?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 0.0..MAX_SAFE_REQUEST_NONCE.toDouble() }?.toLong() ?: -1L
    val base = maxOf(local, observedLong)
    require(base < MAX_SAFE_REQUEST_NONCE) {
        "The robot tuning request nonce is exhausted. Restart both robot and dashboard before another live test; no value was requested."
    }
    return base + 1L
}

private fun TelemetryFrame.toTuningValue(declaration: TuningParameterDeclaration): TuningValue? = when (declaration.type) {
    TuningParameterType.DOUBLE -> value.takeIf(Double::isFinite)?.let { TuningValue(doubleValue = it) }
    TuningParameterType.INT -> value.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }
        ?.let { TuningValue(intValue = it.toInt()) }
    TuningParameterType.BOOLEAN -> stringValue?.toBooleanStrictOrNull()?.let { TuningValue(booleanValue = it) }
        ?: value.takeIf(Double::isFinite)?.let { TuningValue(booleanValue = it != 0.0) }
    TuningParameterType.TEXT, TuningParameterType.ENUM -> stringValue?.let { TuningValue(textValue = it) }
}
