package com.ares.analytics.viewmodel

import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.tuning.*
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.areslib.controls.ControllerInputPlatform
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningValue
import com.areslib.telemetry.schema.TuningAcknowledgementCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.ares.analytics.shared.models.TelemetryFrame

class TuningViewModel(
    val nt4ClientService: Nt4ClientService,
    private val scope: CoroutineScope,
    private val repository: TuningProfileRepository = TuningProfileRepository(),
    proposalInbox: TuningProposalInbox? = null,
    private val checkpointRecorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
    private val projectSession: ProjectSession? = null,
    private val targetPlatform: ControllerInputPlatform? = null,
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val loadGeneration = AtomicLong()
    private val reviewGeneration = AtomicLong()
    private val promotionInFlight = AtomicBoolean()
    private var requestNonce = 0L
    /** Serializes multi-parameter live tests so every Requested value receives a unique nonce. */
    private val requestMutex = Mutex()
    private val _state = MutableStateFlow(TuningState())
    val state: StateFlow<TuningState> = _state.asStateFlow()

    init {
        proposalInbox?.let { inbox ->
            scope.launch {
                combine(inbox.pendingCount, _state.map { it.loadRevision to (!it.isLoading && it.selectedProfile != null) }.distinctUntilChanged()) {
                    count, readiness -> count > 0 && readiness.second
                }.collect { ready ->
                    if (ready) {
                        val revision = _state.value.loadRevision
                        val errors = mutableListOf<String>()
                        while (_state.value.loadRevision == revision && !_state.value.isLoading && _state.value.selectedProfile != null && inbox.deliverNext { proposal ->
                            var consumed = false
                            var rejection: String? = null
                            _state.update {
                                consumed = it.loadRevision == revision && !it.isLoading && it.selectedProfile != null
                                if (consumed) stageExternalTuningProposal(it, proposal).also { next -> rejection = next.errorMessage }
                                else it
                            }
                            if (consumed) rejection?.let(errors::add)
                            consumed
                        }) { /* Acknowledge only after synchronous atomic staging or explicit rejection. */ }
                        if (errors.isEmpty() && _state.value.loadRevision == revision) reviewPromotion()
                        else if (errors.isNotEmpty()) _state.update {
                            if (it.loadRevision == revision) it.copy(errorMessage = errors.distinct().joinToString("\n")) else it
                        }
                    }
                }
            }
        }
        scope.launch {
            var catalog: List<TuningParameterDeclaration>? = null
            var topics = emptyList<ObservedTuningTopics>()
            while (isActive) {
                val snapshot = _state.value
                if (catalog !== snapshot.catalog) {
                    catalog = snapshot.catalog
                    topics = snapshot.catalog.map(::ObservedTuningTopics)
                }
                val typed = mutableMapOf<String, TuningValue>()
                val numeric = mutableMapOf<String, Double>()
                val consumerSupport = mutableMapOf<String, Boolean>()
                for (topic in topics) {
                    val declaration = topic.declaration
                    val value = nt4ClientService.latestValues[topic.current]?.toTuningValue(declaration)
                    if (value != null) {
                        typed[declaration.key] = value
                        value.numericValue()?.let { numeric[declaration.key] = it }
                    }
                    nt4ClientService.latestValues[topic.consumerSupported]?.let {
                        // An explicitly malformed support flag cannot authorize live testing.
                        consumerSupport[declaration.uid] = it.tuningBoolean() == true
                    }
                }
                _state.update {
                    if (it.loadRevision != snapshot.loadRevision || it.catalog !== snapshot.catalog ||
                        (it.variables == numeric && it.liveTypedValues == typed && it.consumerSupportByUid == consumerSupport)) it
                    else it.copy(variables = numeric, liveTypedValues = typed, consumerSupportByUid = consumerSupport)
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

    private fun load(projectPath: String) {
        // Invalidate readiness at the call site, before a scope can reorder launches.
        val generation = loadGeneration.incrementAndGet()
        _state.update {
            if (generation != loadGeneration.get()) it
            else if (projectPath.isBlank()) TuningState(loadRevision = generation)
            else TuningState(isLoading = true, projectPath = projectPath, selectedProfileId = it.selectedProfileId, loadRevision = generation)
        }
        if (projectPath.isBlank()) return
        scope.launch {
            if (generation != loadGeneration.get()) return@launch
            val loaded = withContext(loadDispatcher) {
                runCatching {
                    val snapshot = targetPlatform?.let { projectSession?.snapshot(projectPath, it, forceReload = true) }
                    repository.load(projectPath).getOrThrow() to snapshot?.revision
                }
            }
            if (generation != loadGeneration.get()) return@launch
            val result = loaded.map { it.first }
            val projectRevision = loaded.getOrNull()?.second
            result.fold(onSuccess = { docs ->
                val selected = docs.profiles.firstOrNull { it.profileId == _state.value.selectedProfileId } ?: docs.profiles.firstOrNull()
                _state.update { if (generation != loadGeneration.get()) it else it.copy(
                    catalog = docs.catalog,
                    projectRevision = projectRevision,
                    profiles = docs.profiles,
                    selectedProfileId = selected?.profileId.orEmpty(),
                    proposals = emptyMap(), proposalProvenance = emptyMap(), review = null,
                    isLoading = false, saveStatus = if (selected == null) "Loaded ${docs.catalog.size} declarations; no canonical profile exists yet." else "Loaded ${docs.catalog.size} declared values from ${selected.displayName}.", errorMessage = null
                ) }
            }, onFailure = { failure -> _state.update {
                if (generation != loadGeneration.get()) it else it.copy(isLoading = false,
                    errorMessage = failure.message ?: "Could not load tuning profiles.")
            } })
        }
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

    private fun TuningState.sameLiveInputsAs(snapshot: TuningState, key: String): Boolean =
        sameWorkspaceAs(snapshot) && selectedProfileId == snapshot.selectedProfileId &&
            profiles == snapshot.profiles && catalog == snapshot.catalog &&
            proposals[key] == snapshot.proposals[key] && proposalProvenance[key] == snapshot.proposalProvenance[key]

    private fun updateLiveStatus(snapshot: TuningState, key: String, status: String, error: String? = null) {
        _state.update { if (it.sameLiveInputsAs(snapshot, key)) it.copy(saveStatus = status, errorMessage = error) else it }
    }

    private fun pushOne(key: String, snapshot: TuningState = _state.value) {
        val connection = nt4ClientService.tuningConnectionId
        scope.launch { sendLiveRequest(key, snapshot, connection) }
    }

    private suspend fun sendLiveRequest(key: String, snapshot: TuningState, connection: Long?) {
        requestMutex.withLock {
            val current = _state.value
            if (!current.sameLiveInputsAs(snapshot, key)) return@withLock
            val declaration = snapshot.catalog.firstOrNull { it.key == key }
            val value = snapshot.proposals[key]
            val validation = when {
                declaration == null -> "$key is undeclared and cannot be pushed."
                connection == null || nt4ClientService.tuningConnectionId != connection -> "The robot connection changed or is not ready. Review the live target and request a new test."
                current.consumerSupportByUid[declaration.uid] == false -> "${declaration.displayName} has no valid runtime consumer support in the connected robot. Regenerate or update the robot project before live testing."
                declaration.applyPolicy != TuningApplyPolicy.LIVE_SAFE -> "${declaration.displayName} is ${declaration.applyPolicy.name.lowercase().replace('_', ' ')} and cannot be live-pushed."
                value == null -> "Stage and review a proposed value before live testing."
                snapshot.selectedProfile == null -> "Load a canonical profile before live testing."
                buildTuningReview(requireNotNull(snapshot.selectedProfile), snapshot.profiles, snapshot.catalog,
                    mapOf(key to value), snapshot.proposalProvenance).second.isNotEmpty() -> "${declaration.displayName} is invalid for live testing."
                else -> null
            }
            if (validation != null) {
                updateLiveStatus(snapshot, key, "Live tuning request was not sent.", validation)
                return@withLock
            }
            val target = requireNotNull(declaration)
            val nonce = try {
                val acknowledged = TuningAcknowledgementCodec.decode(
                    nt4ClientService.latestValues[TuningTransport.acknowledgement(target)]?.stringValue)?.nonce ?: -1L
                nextTuningRequestNonce(maxOf(requestNonce, acknowledged),
                    nt4ClientService.latestValues[TuningTransport.requestNonce(target)]?.value,
                    nt4ClientService.latestValues[TuningTransport.processedNonce(target)]?.value)
            } catch (failure: IllegalArgumentException) {
                updateLiveStatus(snapshot, key, "Live tuning request was not sent.", failure.message)
                return@withLock
            }
            try {
                // Reserve under the request mutex; disconnects must not reset/reuse a nonce.
                requestNonce = nonce
                if (!nt4ClientService.publishTuningRequest(target, requireNotNull(value), nonce, requireNotNull(connection))) {
                    updateLiveStatus(snapshot, key, "Live tuning request was not sent.", "The connection or outgoing queue was not ready. Request a new test when the robot is ready.")
                    return@withLock
                }
                updateLiveStatus(snapshot, key, "Waiting for ${target.displayName} acknowledgement…")
                val result = awaitTuningResult(target, nonce, connection)
                if (result == "APPLIED") {
                    updateLiveStatus(snapshot, key, "Robot acknowledged ${target.displayName} as applied experimentally. The profile was not changed.")
                } else {
                    updateLiveStatus(snapshot, key, "Robot acknowledged ${target.displayName} but rejected the experimental change. The profile was not changed.",
                        "Robot rejected ${target.displayName}: ${result.lowercase().replace('_', ' ')}. The profile was not changed.")
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                updateLiveStatus(snapshot, key, "No current robot acknowledgement was received; the experimental result is unknown.",
                    failure.message ?: "Live push failed.")
            }
        }
    }

    private suspend fun awaitTuningResult(declaration: TuningParameterDeclaration, nonce: Long, connection: Long): String {
        val acknowledgementTopic = TuningTransport.acknowledgement(declaration)
        repeat(30) {
            check(nt4ClientService.tuningConnectionId == connection) { "The robot connection changed before acknowledging this request. Treat the result as unknown." }
            val acknowledgement = TuningAcknowledgementCodec.decode(nt4ClientService.latestValues[acknowledgementTopic]?.stringValue)
            if (acknowledgement?.nonce == nonce) return acknowledgement.result
            delay(100)
        }
        error("Robot did not provide a matching atomic tuning acknowledgement within 3 seconds. Treat the result as unknown; older robot code must be updated before results can be verified.")
    }

    private fun pushAllExperimental() {
        val snapshot = _state.value
        val declarations = snapshot.catalog.associateBy { it.key }
        val eligible = snapshot.proposals.keys.filter { key ->
            declarations[key]?.let { it.applyPolicy == TuningApplyPolicy.LIVE_SAFE && snapshot.consumerSupportByUid[it.uid] != false } == true
        }
        if (eligible.isEmpty()) _state.update { it.copy(errorMessage = "No reviewed experimental-live proposals are available.") }
        else {
            val connection = nt4ClientService.tuningConnectionId
            scope.launch { for (key in eligible) sendLiveRequest(key, snapshot, connection) }
        }
    }

    private fun TuningState.sameWorkspaceAs(snapshot: TuningState): Boolean =
        !isLoading && !snapshot.isLoading && projectPath == snapshot.projectPath && loadRevision == snapshot.loadRevision

    /** Observed telemetry and status messages do not change the reviewed draft. */
    private fun TuningState.sameReviewInputsAs(snapshot: TuningState): Boolean =
        sameWorkspaceAs(snapshot) && projectRevision == snapshot.projectRevision &&
            selectedProfileId == snapshot.selectedProfileId && profiles == snapshot.profiles && catalog == snapshot.catalog &&
            proposals == snapshot.proposals && proposalProvenance == snapshot.proposalProvenance &&
            reviewerName == snapshot.reviewerName && reviewSummary == snapshot.reviewSummary

    private fun reviewPromotion() {
        val snapshot = _state.value
        val profile = snapshot.selectedProfile ?: return
        val generation = reviewGeneration.incrementAndGet()
        _state.update { if (it.sameReviewInputsAs(snapshot)) it.copy(review = null, saveStatus = "") else it }
        scope.launch {
            runCatching {
                withContext(workDispatcher) {
                    if (generation != reviewGeneration.get() || !_state.value.sameReviewInputsAs(snapshot)) return@withContext null
                    val (changes, valueErrors) = buildTuningReview(profile, snapshot.profiles, snapshot.catalog, snapshot.proposals, snapshot.proposalProvenance)
                    val errors = valueErrors + buildList {
                        if (snapshot.reviewerName.isBlank()) add("Enter the reviewer name.")
                        if (snapshot.reviewSummary.isBlank()) add("Enter a review summary.")
                        if (changes.any { it.policy == TuningApplyPolicy.CALIBRATION_ONLY && (it.provenance.evidencePath.isNullOrBlank() || it.provenance.evidenceSha256.isNullOrBlank()) }) add("Calibration-only changes require a project evidence path and SHA-256 before promotion.")
                    } + repository.evidenceErrors(snapshot.projectPath, changes)
                    val hash = com.areslib.tuning.TuningProfileDocumentCodec.contentHash(profile, snapshot.catalog)
                    TuningProposalReview(profile.profileId, hash, changes, errors,
                        repository.reviewToken(profile, snapshot.catalog, changes, snapshot.reviewerName, snapshot.reviewSummary),
                        snapshot.reviewerName, snapshot.reviewSummary)
                }
            }.fold(onSuccess = { review ->
                if (review != null) _state.update {
                    if (generation != reviewGeneration.get() || !it.sameReviewInputsAs(snapshot)) it
                    else it.copy(review = review, errorMessage = review.errors.firstOrNull(),
                        saveStatus = if (review.canPromote) "Review ${review.changes.size} structured profile changes, then confirm promotion." else "Promotion is blocked by validation.")
                }
            }, onFailure = { failure ->
                if (failure is CancellationException) throw failure
                _state.update {
                    if (generation != reviewGeneration.get() || !it.sameReviewInputsAs(snapshot)) it
                    else it.copy(review = null, errorMessage = failure.message ?: "Profile review failed.")
                }
            })
        }
    }

    private fun confirmPromotion(token: String) {
        // Bind the intent before launch: queued work must never reinterpret a token in another project.
        val snapshot = _state.value
        val profile = snapshot.selectedProfile ?: return
        val review = snapshot.review
        if (review == null || !review.canPromote || token != review.confirmationToken) {
            _state.update {
                if (it.sameReviewInputsAs(snapshot)) it.copy(errorMessage = "The confirmation is missing or stale. Review a fresh structured diff.") else it
            }
            return
        }
        if (!promotionInFlight.compareAndSet(false, true)) return
        val job = scope.launch {
            runCatching {
                withContext(workDispatcher) {
                    val current = _state.value
                    if (!current.sameReviewInputsAs(snapshot) || current.review != review) return@withContext null
                    require(review.baseContentHash == com.areslib.tuning.TuningProfileDocumentCodec.contentHash(profile, snapshot.catalog)) {
                        "The confirmation is missing or stale. Review a fresh structured diff."
                    }
                    val session = projectSession
                    val revision = snapshot.projectRevision
                    if (session != null && revision != null) {
                        when (val result = session.promoteTuningProfile(revision, profile, review.baseContentHash,
                            snapshot.catalog, review.changes, review.reviewedBy, review.reviewSummary)) {
                            is ProjectSessionMutationResult.Applied -> result.value to result.snapshot.revision
                            is ProjectSessionMutationResult.Stale -> error("The project changed after this tuning profile loaded. Reload before promotion.")
                            is ProjectSessionMutationResult.Conflict -> error(result.message)
                            is ProjectSessionMutationResult.Failed -> error(result.message)
                        }
                    } else {
                        repository.promote(snapshot.projectPath, profile, review.baseContentHash, snapshot.catalog,
                            review.changes, review.reviewedBy, review.reviewSummary) to null
                    }
                }
            }.fold(onSuccess = { result ->
                if (result == null) return@fold
                val (promoted, revision) = result
                val successStatus = "Promoted canonical profile atomically. Robot values were not pushed."
                val completion = _state.updateAndGet {
                    if (!it.sameWorkspaceAs(snapshot)) it
                    else {
                        val unchangedDraft = it.sameReviewInputsAs(snapshot)
                        it.copy(
                            profiles = it.profiles.map { doc -> if (doc.uid == promoted.uid) promoted else doc },
                            projectRevision = revision ?: it.projectRevision,
                            proposals = if (unchangedDraft) emptyMap() else it.proposals,
                            proposalProvenance = if (unchangedDraft) emptyMap() else it.proposalProvenance,
                            review = null,
                            reviewerName = if (unchangedDraft) "" else it.reviewerName,
                            reviewSummary = if (unchangedDraft) "" else it.reviewSummary,
                            saveStatus = if (unchangedDraft) successStatus else it.saveStatus,
                            errorMessage = if (unchangedDraft) null else it.errorMessage,
                        )
                    }
                }
                // The committed project still owns its checkpoint even if the user has moved on.
                scope.launch {
                    runCatching {
                        recordTuningPromotionCheckpoint(checkpointRecorder, snapshot.projectPath, promoted.displayName, review.reviewSummary)
                    }.onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        _state.update {
                            if (completion.sameWorkspaceAs(snapshot) && completion.saveStatus == successStatus &&
                                it.sameReviewInputsAs(completion) && it.saveStatus == completion.saveStatus) {
                                it.copy(saveStatus = "Tuning profile promoted, but automatic Project History checkpoint failed: ${failure.message}")
                            } else it
                        }
                    }
                }
            }, onFailure = { failure ->
                if (failure is CancellationException) throw failure
                _state.update {
                    if (it.sameReviewInputsAs(snapshot) && it.review == review) it.copy(errorMessage = failure.message ?: "Profile promotion failed.") else it
                }
            })
        }
        // Also releases when the scope was already cancelled and the launch body never starts.
        job.invokeOnCompletion { promotionInFlight.set(false) }
    }

}
