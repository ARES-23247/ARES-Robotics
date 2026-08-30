package com.ares.analytics.viewmodel

import com.ares.analytics.service.*
import com.ares.analytics.service.dashboard.DashboardWidgetCatalog
import com.ares.analytics.service.dashboard.DashboardWidgetType
import com.ares.analytics.service.dashboard.defaultProperties
import com.ares.analytics.service.log.*
import com.ares.analytics.shared.*
import com.ares.analytics.shared.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class DashboardState(
    val currentRoleProfile: String = "Standard",
    val currentLayout: DashboardLayoutConfig? = null,
    val isPickerOpen: Boolean = false,
    val isLayoutEditing: Boolean = false,
    val primarySessionId: String? = null,
    val replayEvidenceTarget: ReplayEvidenceTarget? = null,
    val sessionMode: SessionMode = SessionMode.LIVE_STREAMING,
    val compareSessionId: String? = null,
    val alerts: List<AlertRecord> = emptyList(),
    val isConnected: Boolean = false,
    val isImporting: Boolean = false,
    val importSuccess: Boolean = false,
    val errorMessage: String? = null,
    /**
     * Non-blocking warning set when a local import succeeded but the background cloud
     * sync (upload/delta) failed (e.g. offline). `null` when sync was clean or not attempted.
     */
    val syncWarning: String? = null,
    val availableProfiles: List<String> = emptyList(),
    val savedLiveProfile: String? = null
)

data class ReplayEvidenceTarget(
    val sessionId: String,
    val timestampMs: Long,
    val requestId: Long,
)

sealed class DashboardIntent {

    data class ChangeProfile(val profile: String) : DashboardIntent()

    data class SetPickerOpen(val isOpen: Boolean) : DashboardIntent()

    data class SetLayoutEditing(val isEditing: Boolean) : DashboardIntent()

    data class SelectPrimarySession(val sessionId: String?, val evidenceTimestampMs: Long? = null) : DashboardIntent()

    data class ConsumeReplayEvidenceTarget(val requestId: Long) : DashboardIntent()

    data class SetSessionMode(val mode: SessionMode) : DashboardIntent()

    data class SelectCompareSession(val sessionId: String?) : DashboardIntent()

    data class UpdateLayout(val newWidgets: List<WidgetConfig>) : DashboardIntent()

    data class AddWidget(val type: DashboardWidgetType) : DashboardIntent()

    data class RemoveWidget(val widgetId: String) : DashboardIntent()

    object ResetProfile : DashboardIntent()

    data class ImportLogFiles(val files: List<File>, val teamId: String, val seasonId: String, val robotId: String) : DashboardIntent()

    object ClearImportSuccess : DashboardIntent()

    data class SaveLayoutAs(val profileName: String) : DashboardIntent()

    data class DeleteLayout(val profileName: String) : DashboardIntent()
}
/** Coordinates dashboard profiles, live/replay session selection, layouts, and log imports. */
class DashboardViewModel(
    private val databaseService: DatabaseService,
    private val nt4ClientService: Nt4ClientService,
    private val alertEngineService: AlertEngineService,
    private val syncEngineService: SyncEngineService,
    private val hootDecoderService: HootDecoderService,
    private val logParserService: LogParserService,
    private val layoutPreferenceService: LayoutPreferenceService,
    private val widgetCatalog: DashboardWidgetCatalog,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()
    private val layoutTransactions = DashboardLayoutTransactionQueue()
    private var replayEvidenceRequestId = 0L

    init {
        // Collect alerts
        scope.launch {
            alertEngineService.alerts.collectLatest { list ->
                if (_state.value.sessionMode != SessionMode.HISTORICAL_REPLAY) {
                    _state.update { it.copy(alerts = list) }
                }
            }
        }
        // Collect connection state
        scope.launch {
            nt4ClientService.isConnected.collectLatest { connected ->
                _state.update { it.copy(isConnected = connected) }
            }
        }
        // Load initial layout through the same transaction boundary used by edits.
        scope.launch {
            layoutTransactions.transact {
                loadLayoutForProfile(_state.value.currentRoleProfile)
            }
        }
        refreshAvailableProfiles()
    }

    fun onIntent(intent: DashboardIntent) {
        scope.launch {
            when (intent) {
                is DashboardIntent.ChangeProfile -> {
                    layoutTransactions.transact {
                        _state.update { it.copy(currentRoleProfile = intent.profile, isLayoutEditing = false) }
                        loadLayoutForProfile(intent.profile)
                    }
                }
                is DashboardIntent.SetPickerOpen -> {
                    _state.update { it.copy(isPickerOpen = intent.isOpen) }
                }
                is DashboardIntent.SetLayoutEditing -> {
                    _state.update { it.copy(isLayoutEditing = intent.isEditing, isPickerOpen = if (intent.isEditing) it.isPickerOpen else false) }
                }
                is DashboardIntent.SelectPrimarySession -> {
                    val newMode = if (intent.sessionId == null) SessionMode.LIVE_STREAMING else SessionMode.HISTORICAL_REPLAY
                    val evidenceTarget = intent.sessionId?.let { sessionId ->
                        intent.evidenceTimestampMs?.let { timestamp ->
                            ReplayEvidenceTarget(
                                sessionId = sessionId,
                                timestampMs = timestamp,
                                requestId = ++replayEvidenceRequestId,
                            )
                        }
                    }

                    if (intent.sessionId == null) {
                        _state.update { it.copy(alerts = alertEngineService.alerts.value) }
                    }

                    layoutTransactions.transact {
                        when {
                            newMode == SessionMode.HISTORICAL_REPLAY && _state.value.sessionMode == SessionMode.LIVE_STREAMING -> {
                                // Going into replay, save the current live layout profile
                                val currentLiveProfile = _state.value.currentRoleProfile
                                _state.update { it.copy(
                                    primarySessionId = intent.sessionId,
                                    replayEvidenceTarget = evidenceTarget,
                                    sessionMode = newMode,
                                    savedLiveProfile = currentLiveProfile,
                                    currentRoleProfile = "Replay"
                                ) }
                                loadLayoutForProfile("Replay")
                            }
                            newMode == SessionMode.LIVE_STREAMING && _state.value.sessionMode == SessionMode.HISTORICAL_REPLAY -> {
                                // Returning to live, restore live layout profile
                                val restoreProfile = _state.value.savedLiveProfile ?: "Standard"
                                _state.update { it.copy(
                                    primarySessionId = intent.sessionId,
                                    replayEvidenceTarget = evidenceTarget,
                                    sessionMode = newMode,
                                    savedLiveProfile = null,
                                    currentRoleProfile = restoreProfile
                                ) }
                                loadLayoutForProfile(restoreProfile)
                            }
                            else -> {
                                // Standard update
                                _state.update {
                                    it.copy(
                                        primarySessionId = intent.sessionId,
                                        replayEvidenceTarget = evidenceTarget,
                                        sessionMode = newMode,
                                    )
                                }
                            }
                        }
                    }
                    intent.sessionId?.let { selectedSessionId ->
                        scope.launch {
                            val historicalAlerts = databaseService.getAlerts(selectedSessionId)
                            _state.update { current ->
                                if (current.primarySessionId == selectedSessionId) {
                                    current.copy(alerts = historicalAlerts)
                                } else {
                                    current
                                }
                            }
                        }
                    }
                }
                is DashboardIntent.ConsumeReplayEvidenceTarget -> {
                    _state.update { current ->
                        if (current.replayEvidenceTarget?.requestId == intent.requestId) {
                            current.copy(replayEvidenceTarget = null)
                        } else current
                    }
                }
                is DashboardIntent.SetSessionMode -> {
                    _state.update { it.copy(sessionMode = intent.mode) }
                }
                is DashboardIntent.SelectCompareSession -> {
                    _state.update { it.copy(compareSessionId = intent.sessionId) }
                }
                is DashboardIntent.UpdateLayout -> {
                    layoutTransactions.transact {
                        val profile = _state.value.currentRoleProfile
                        persistLayout(profile, DashboardLayoutConfig(intent.newWidgets))
                    }
                }
                is DashboardIntent.AddWidget -> {
                    layoutTransactions.transact {
                        val spec = widgetCatalog.find(intent.type)
                        if (spec == null) {
                            _state.update { it.copy(errorMessage = "Unknown dashboard widget type: ${intent.type}") }
                        } else {
                            val profile = _state.value.currentRoleProfile
                            val currentList = _state.value.currentLayout?.widgets.orEmpty()
                            val maxRow = currentList.maxOfOrNull { it.row + it.rowSpan } ?: 0
                            val newWidget = WidgetConfig(
                                id = "${intent.type.serializedName}_${UUID.randomUUID()}",
                                type = intent.type.serializedName,
                                row = maxRow,
                                col = 0,
                                rowSpan = spec.defaultRowSpan,
                                colSpan = spec.defaultColSpan,
                                properties = spec.defaultProperties(),
                            )
                            persistLayout(
                                profile = profile,
                                layout = DashboardLayoutConfig(currentList + newWidget),
                                stateTransform = { it.copy(isPickerOpen = false) },
                            )
                        }
                    }
                }
                is DashboardIntent.RemoveWidget -> {
                    layoutTransactions.transact {
                        val profile = _state.value.currentRoleProfile
                        val currentList = _state.value.currentLayout?.widgets.orEmpty()
                        persistLayout(profile, DashboardLayoutConfig(currentList.filter { it.id != intent.widgetId }))
                    }
                }
                is DashboardIntent.ResetProfile -> {
                    layoutTransactions.transact {
                        val profile = _state.value.currentRoleProfile
                        persistLayout(profile, layoutPreferenceService.getDefaultLayout(profile))
                    }
                }
                is DashboardIntent.ImportLogFiles -> {
                    _state.update { it.copy(isImporting = true, importSuccess = false, errorMessage = null, syncWarning = null) }
                    withContext(Dispatchers.IO) {
                        try {
                            val sessionId = if (intent.files.size == 1 && intent.files.first().name.lowercase().endsWith(".hoot")) {
                                hootDecoderService.importHootLog(
                                    hootFile = intent.files.first(),
                                    teamId = intent.teamId,
                                    seasonId = intent.seasonId,
                                    robotId = intent.robotId,
                                ).session.sessionId
                            } else {
                                logParserService.parseLogFiles(
                                    files = intent.files,
                                    teamId = intent.teamId,
                                    seasonId = intent.seasonId,
                                    robotId = intent.robotId
                                ).sessionId
                            }

                            // Trigger background cloud sync after successful import. Surface a
                            // non-blocking warning if it fails (e.g. offline) instead of
                            // silently claiming success — previously importSuccess=true implied
                            // a backup that never happened.
                            var warning: String? = null
                            try {
                                syncEngineService.uploadSession(sessionId)
                                syncEngineService.performDeltaSync(intent.teamId, intent.seasonId)
                            } catch (syncEx: kotlinx.coroutines.CancellationException) {
                                throw syncEx
                            } catch (syncEx: Exception) {
                                warning = "Imported locally; cloud sync failed: ${syncEx.message ?: syncEx.javaClass.simpleName}"
                            }

                            _state.update { it.copy(isImporting = false, importSuccess = true, syncWarning = warning) }
                        } catch (e: Exception) {
                            _state.update { it.copy(isImporting = false, errorMessage = e.message ?: "Failed to import log file(s)") }
                        }
                    }
                }
                is DashboardIntent.ClearImportSuccess -> {
                    _state.update { it.copy(importSuccess = false) }
                }
                is DashboardIntent.SaveLayoutAs -> {
                    layoutTransactions.transact {
                        val currentLayout = _state.value.currentLayout
                        if (currentLayout != null) {
                            try {
                                layoutPreferenceService.saveLayout(intent.profileName, currentLayout)
                                _state.update {
                                    it.copy(currentRoleProfile = intent.profileName, errorMessage = null)
                                }
                                refreshAvailableProfiles()
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                _state.update {
                                    it.copy(errorMessage = error.message ?: "Failed to save dashboard layout")
                                }
                            }
                        }
                    }
                }
                is DashboardIntent.DeleteLayout -> {
                    layoutTransactions.transact {
                        try {
                            val currentProfile = _state.value.currentRoleProfile
                            layoutPreferenceService.deleteLayout(intent.profileName)
                            if (currentProfile == intent.profileName) {
                                _state.update { it.copy(currentRoleProfile = "Standard", isLayoutEditing = false) }
                                loadLayoutForProfile("Standard")
                            }
                            refreshAvailableProfiles()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            _state.update { it.copy(errorMessage = error.message ?: "Failed to delete dashboard layout") }
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadLayoutForProfile(profileName: String) {
        try {
            val layout = withContext(Dispatchers.IO) {
                layoutPreferenceService.loadLayout(profileName)
            }
            _state.update { state ->
                if (state.currentRoleProfile == profileName) {
                    state.copy(currentLayout = layout, errorMessage = null)
                } else {
                    state
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(errorMessage = error.message ?: "Failed to load dashboard layout") }
        }
    }

    private suspend fun persistLayout(
        profile: String,
        layout: DashboardLayoutConfig,
        stateTransform: (DashboardState) -> DashboardState = { it },
    ): Boolean {
        return try {
            layoutPreferenceService.saveLayout(profile, layout)
            _state.update { state ->
                if (state.currentRoleProfile == profile) {
                    stateTransform(state.copy(currentLayout = layout, errorMessage = null))
                } else {
                    state
                }
            }
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(errorMessage = error.message ?: "Failed to save dashboard layout") }
            false
        }
    }

    private fun refreshAvailableProfiles() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                layoutPreferenceService.getAvailableLayouts()
            }
            _state.update { it.copy(availableProfiles = list) }
        }
    }
}
