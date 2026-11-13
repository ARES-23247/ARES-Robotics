package com.ares.analytics.viewmodel

import com.ares.analytics.service.EnvironmentService
import com.ares.analytics.service.EventApiService
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.shared.models.ForensicsResponse
import com.ares.analytics.domain.navigation.NavigationHistoryStack
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.AppWorkspaces
import com.ares.analytics.shared.models.ControllerBinding
import com.ares.analytics.service.KeybindingParserService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class MainState(
    val config: WorkspaceConfig? = null,
    val workspaces: List<WorkspaceConfig> = emptyList(),
    val activeNav: NavigationTarget = NavigationTarget.DASHBOARD,
    val navHistory: NavigationHistoryStack = NavigationHistoryStack(),
    val matches: List<MatchInfo> = emptyList(),
    val runsIndexReloadTrigger: Int = 0,
    val diagnosticsResponse: ForensicsResponse? = null,
    val isTerminalOpen: Boolean = false,
    val parsedBindings: List<ControllerBinding> = emptyList(),
    val showUpdateBanner: Boolean = true
)

sealed class MainIntent {

    object LoadConfig : MainIntent()

    data class SetActiveNav(val nav: NavigationTarget, val subLabel: String? = null) : MainIntent()

    data class UpdateNavSubLabel(val subLabel: String?) : MainIntent()

    object NavigateBack : MainIntent()

    object NavigateForward : MainIntent()

    data class SetMatches(val matches: List<MatchInfo>) : MainIntent()

    object TriggerRunsIndexReload : MainIntent()

    data class SetDiagnosticsResponse(val response: ForensicsResponse?) : MainIntent()

    data class SetTerminalOpen(val isOpen: Boolean) : MainIntent()

    object RefreshKeybindings : MainIntent()

    data class SetShowUpdateBanner(val show: Boolean) : MainIntent()

    data class SaveConfig(val config: WorkspaceConfig) : MainIntent()

    data class SelectWorkspace(val id: String) : MainIntent()

    data class DeleteWorkspace(val id: String) : MainIntent()

    object AddNewWorkspace : MainIntent()

    object CancelAddNewWorkspace : MainIntent()

    object RefreshEventSchedule : MainIntent()

    data class UpdateCachedMatches(val matches: List<MatchInfo>) : MainIntent()
}

/** Top-level navigation and workspace state shared by the desktop application's screens. */
class MainViewModel(
    private val environmentService: EnvironmentService,
    private val eventApiService: EventApiService,
    private val keybindingParserService: KeybindingParserService,
    private val scope: CoroutineScope,
    private val beforeWorkspaceChange: suspend () -> Unit = {},
    private val eventScheduleStore: com.ares.analytics.service.EventScheduleStore = com.ares.analytics.service.EventScheduleStore(),
) {
    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()
    private val scheduleGeneration = AtomicLong(0L)

    init {
        onIntent(MainIntent.LoadConfig)
    }

    fun onIntent(intent: MainIntent) {
        scope.launch {
            when (intent) {
                is MainIntent.LoadConfig -> {
                    val app = environmentService.loadWorkspaces()
                    val active = app.workspaces.find { it.id == app.activeWorkspaceId } ?: app.workspaces.firstOrNull()
                    _state.update { it.copy(config = active, workspaces = app.workspaces) }
                    if (active != null) {
                        loadMatchSchedule(active)
                    }
                }
                is MainIntent.SetActiveNav -> {
                    _state.update { current ->
                        val nextHistory = current.navHistory.navigateTo(intent.nav, intent.subLabel)
                        current.copy(activeNav = nextHistory.current.target, navHistory = nextHistory)
                    }
                }
                is MainIntent.UpdateNavSubLabel -> {
                    _state.update { current ->
                        current.copy(navHistory = current.navHistory.updateCurrentSubLabel(intent.subLabel))
                    }
                }
                is MainIntent.NavigateBack -> {
                    _state.update { current ->
                        val nextHistory = current.navHistory.goBack()
                        current.copy(activeNav = nextHistory.current.target, navHistory = nextHistory)
                    }
                }
                is MainIntent.NavigateForward -> {
                    _state.update { current ->
                        val nextHistory = current.navHistory.goForward()
                        current.copy(activeNav = nextHistory.current.target, navHistory = nextHistory)
                    }
                }
                is MainIntent.SetMatches -> {
                    _state.update { it.copy(matches = intent.matches) }
                }
                is MainIntent.TriggerRunsIndexReload -> {
                    _state.update { it.copy(runsIndexReloadTrigger = it.runsIndexReloadTrigger + 1) }
                }
                is MainIntent.SetDiagnosticsResponse -> {
                    _state.update { it.copy(diagnosticsResponse = intent.response) }
                }
                is MainIntent.SetTerminalOpen -> {
                    _state.update { it.copy(isTerminalOpen = intent.isOpen) }
                }
                is MainIntent.RefreshKeybindings -> {
                    val path = _state.value.config?.projectPath
                    if (path != null && path.isNotEmpty()) {
                        val bindings = keybindingParserService.parseBindings(path)
                        _state.update { it.copy(parsedBindings = bindings) }
                    }
                }
                is MainIntent.SetShowUpdateBanner -> {
                    _state.update { it.copy(showUpdateBanner = intent.show) }
                }
                is MainIntent.SaveConfig -> {
                    val configWithId = if (intent.config.id.isEmpty()) {
                        intent.config.copy(id = "${intent.config.league}-${intent.config.teamId}-${intent.config.robotId}-${intent.config.seasonId}")
                    } else {
                        intent.config
                    }
                    val app = environmentService.loadWorkspaces()
                    val newList = app.workspaces.filter { it.id != configWithId.id } + configWithId
                    val newApp = com.ares.analytics.shared.models.AppWorkspaces(activeWorkspaceId = configWithId.id, workspaces = newList)
                    if (_state.value.config?.id != configWithId.id || _state.value.config?.projectPath != configWithId.projectPath) {
                        beforeWorkspaceChange()
                    }
                    environmentService.saveWorkspaces(newApp)

                    _state.update { it.copy(config = configWithId, workspaces = newList) }
                    loadMatchSchedule(configWithId)
                }
                is MainIntent.SelectWorkspace -> {
                    val app = environmentService.loadWorkspaces()
                    val target = app.workspaces.find { it.id == intent.id }
                    if (target != null) {
                        val newApp = app.copy(activeWorkspaceId = target.id)
                        val changed = _state.value.config?.id != target.id
                        if (changed) beforeWorkspaceChange()
                        environmentService.saveWorkspaces(newApp)
                        _state.update { current ->
                            current.copy(
                                config = target,
                                workspaces = app.workspaces,
                                navHistory = if (changed) current.navHistory.reset(current.activeNav) else current.navHistory,
                            )
                        }
                        loadMatchSchedule(target)
                    }
                }
                is MainIntent.DeleteWorkspace -> {
                    val app = environmentService.loadWorkspaces()
                    val newList = app.workspaces.filter { it.id != intent.id }
                    val newActiveId = if (app.activeWorkspaceId == intent.id) {
                        newList.firstOrNull()?.id
                    } else {
                        app.activeWorkspaceId
                    }
                    val newApp = com.ares.analytics.shared.models.AppWorkspaces(activeWorkspaceId = newActiveId, workspaces = newList)
                    val deletingActive = _state.value.config?.id == intent.id
                    if (deletingActive) beforeWorkspaceChange()
                    environmentService.saveWorkspaces(newApp)
                    val newActiveConfig = newList.find { it.id == newActiveId }
                    _state.update { current ->
                        current.copy(
                            config = newActiveConfig,
                            workspaces = newList,
                            navHistory = if (deletingActive) current.navHistory.reset(current.activeNav) else current.navHistory,
                        )
                    }
                    if (newActiveConfig != null) {
                        loadMatchSchedule(newActiveConfig)
                    } else {
                        _state.update { it.copy(matches = emptyList()) }
                    }
                }
                is MainIntent.AddNewWorkspace -> {
                    scheduleGeneration.incrementAndGet()
                    if (_state.value.config != null) beforeWorkspaceChange()
                    _state.update { it.copy(config = null, matches = emptyList()) }
                }
                is MainIntent.CancelAddNewWorkspace -> {
                    val app = environmentService.loadWorkspaces()
                    val active = app.workspaces.find { it.id == app.activeWorkspaceId } ?: app.workspaces.firstOrNull()
                    if (active != null) {
                        _state.update { it.copy(config = active) }
                        loadMatchSchedule(active)
                    }
                }
                is MainIntent.RefreshEventSchedule -> {
                    _state.value.config?.let { loadMatchSchedule(it) }
                }
                is MainIntent.UpdateCachedMatches -> {
                    val active = _state.value.config
                    if (active != null) {
                        val saved = runCatching { eventScheduleStore.saveMatches(active, intent.matches) }
                            .getOrDefault(intent.matches)
                        _state.update { it.copy(matches = saved) }
                    } else {
                        _state.update { it.copy(matches = intent.matches) }
                    }
                }
            }
        }
    }

    private suspend fun loadMatchSchedule(currentConf: WorkspaceConfig) {
        val generation = scheduleGeneration.incrementAndGet()
        val cached = runCatching { eventScheduleStore.loadCachedMatches(currentConf) }.getOrDefault(emptyList())
        if (scheduleGeneration.get() == generation && _state.value.config?.id == currentConf.id) {
            _state.update { it.copy(matches = cached) }
        }
        val eventCode = currentConf.eventCode
        if (!eventCode.isNullOrEmpty()) {
            val fetched = try {
                if (currentConf.league == League.FTC) {
                    val apiKey = currentConf.toaApiKey ?: ""
                    val year = Regex("(20\\d{2})").find(currentConf.seasonId)?.value?.toIntOrNull()
                    eventApiService.fetchFtcEventSchedule(eventCode, apiKey, seasonYear = year)
                } else {
                    val apiKey = currentConf.tbaApiKey ?: ""
                    eventApiService.fetchFrcEventSchedule(eventCode, apiKey)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (fetched.isNotEmpty() && scheduleGeneration.get() == generation && _state.value.config?.id == currentConf.id) {
                val persisted = runCatching { eventScheduleStore.saveMatches(currentConf, fetched, source = "api") }
                    .getOrDefault(fetched)
                _state.update { it.copy(matches = persisted) }
            }
        }
    }
}
