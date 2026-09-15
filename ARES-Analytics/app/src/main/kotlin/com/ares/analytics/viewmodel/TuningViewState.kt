package com.ares.analytics.viewmodel

import com.ares.analytics.service.tuning.*
import com.ares.analytics.service.project.ProjectSessionRevision
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningValue

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
    val catalog: List<TuningParameterDeclaration> = emptyList(),
    val profiles: List<TuningProfileDocument> = emptyList(),
    val selectedProfileId: String = "competition",
    val proposals: Map<String, TuningValue> = emptyMap(),
    val proposalProvenance: Map<String, TuningValueProvenance> = emptyMap(),
    val review: TuningProposalReview? = null,
    val reviewerName: String = "",
    val reviewSummary: String = "",
    val availableBackups: List<BackupInfo> = emptyList(),
    val isLoading: Boolean = false,
    val saveStatus: String = "",
    val errorMessage: String? = null,
    /** Keeps reload completion observable even when intermediate loading emissions conflate. */
    val loadRevision: Long = 0L
) {
    val selectedProfile: TuningProfileDocument? by lazy {
        profiles.firstOrNull { it.profileId == selectedProfileId }
    }

    /** A state snapshot is immutable; repeated UI reads reuse its validated, sorted rows. */
    val rows: List<ResolvedTuningValue> by lazy {
        selectedProfile?.let { resolveTuningProfile(it, profiles, catalog, variables, proposals, liveTypedValues, proposalProvenance) }.orEmpty()
    }
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
