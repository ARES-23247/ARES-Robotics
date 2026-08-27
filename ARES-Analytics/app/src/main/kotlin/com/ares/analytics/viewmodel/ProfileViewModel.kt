package com.ares.analytics.viewmodel

import com.ares.analytics.service.AuthState
import com.ares.analytics.service.DrivePickerState
import com.ares.analytics.service.OAuthService
import com.ares.analytics.service.DriveDestinationStatus
import com.ares.analytics.service.GoogleDriveService
import com.ares.analytics.service.SyncEngineService
import com.ares.analytics.shared.RobotProfile
import com.ares.analytics.shared.WorkspaceConfig
import com.ares.analytics.shared.DriveDestinationType
import com.ares.analytics.shared.DEFAULT_GEMINI_MODEL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProfileState(
    val authState: AuthState = AuthState.Unauthenticated,
    val config: WorkspaceConfig? = null,
    val robotProfiles: List<RobotProfile> = emptyList(),
    val syncStatus: String = "",
    val googleClientId: String = "",
    val googleOAuthBrokerUrl: String = "",
    val googleOAuthUseCustomClient: Boolean = false,
    val managedGoogleSignInAvailable: Boolean = false,
    val driveDestinationStatus: DriveDestinationStatus? = null,
    val isDriveDestinationBusy: Boolean = false,
    val pendingConfigUpdate: WorkspaceConfig? = null,
    val eventCode: String = "",
    val toaApiKey: String = "",
    val tbaApiKey: String = "",
    val aiMode: String = "STUDIO",
    val geminiApiKey: String = "",
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val vertexServiceAccountPath: String = "",
    val vertexProjectId: String = "",
    val vertexLocation: String = "us-central1",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

sealed class ProfileIntent {

    data class LoadConfig(val config: WorkspaceConfig) : ProfileIntent()

    data class GoogleSignIn(val config: WorkspaceConfig) : ProfileIntent()

    data class ConfigureDriveDestination(
        val config: WorkspaceConfig,
        val type: DriveDestinationType,
        val displayName: String,
        val existingFolderReference: String? = null,
        val sharedDriveId: String? = null,
    ) : ProfileIntent()

    data class RefreshDriveDestination(val config: WorkspaceConfig) : ProfileIntent()

    data class PickExistingDriveDestination(
        val config: WorkspaceConfig,
        val type: DriveDestinationType,
        val displayName: String,
    ) : ProfileIntent()

    object ConfigUpdateApplied : ProfileIntent()

    object SignOut : ProfileIntent()

    data class PerformDeltaSync(val firebaseToken: String) : ProfileIntent()

    object ClearSyncStatus : ProfileIntent()
}

/** Manages account linking, event settings, and explicit cloud synchronization actions. */
class ProfileViewModel(
    private val oauthService: OAuthService,
    private val googleDriveService: GoogleDriveService,
    private val syncEngineService: SyncEngineService,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(
        ProfileState(managedGoogleSignInAvailable = oauthService.managedGoogleClientAvailable)
    )
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    init {
        scope.launch {
            oauthService.authState.collectLatest { state ->
                _state.update { it.copy(authState = state) }
                if (state is AuthState.Authenticated) {
                    if (_state.value.config?.driveDestination != null) {
                        onIntent(ProfileIntent.PerformDeltaSync(state.idToken))
                        try {
                            val remoteProfiles = syncEngineService.getRemoteRobotProfiles()
                            _state.update { it.copy(robotProfiles = remoteProfiles) }
                        } catch (e: Exception) {
                            _state.update {
                                it.copy(errorMessage = e.message ?: "The Drive destination is unavailable.")
                            }
                        }
                    } else {
                        _state.update {
                            it.copy(syncStatus = "Signed in. Choose a Drive destination for this workspace to enable cloud sync.")
                        }
                    }
                }
            }
        }
        scope.launch {
            oauthService.drivePickerState.collectLatest { picker ->
                when (picker) {
                    DrivePickerState.Picking -> _state.update {
                        it.copy(isDriveDestinationBusy = true, errorMessage = null)
                    }
                    is DrivePickerState.Error -> _state.update {
                        it.copy(isDriveDestinationBusy = false, errorMessage = picker.message)
                    }
                    DrivePickerState.Idle,
                    is DrivePickerState.Selected -> Unit
                }
            }
        }
    }

    fun onIntent(intent: ProfileIntent) {
        scope.launch {
            when (intent) {
                is ProfileIntent.LoadConfig -> {
                    val cfg = intent.config
                    val remoteProfiles = if (
                        cfg.driveDestination != null && oauthService.authState.value is AuthState.Authenticated
                    ) {
                        try {
                            syncEngineService.getRemoteRobotProfiles()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else emptyList()

                    _state.update {
                        it.copy(
                            config = cfg,
                            robotProfiles = remoteProfiles,
                            googleClientId = cfg.googleClientId ?: "",
                            googleOAuthBrokerUrl = cfg.googleOAuthBrokerUrl ?: "",
                            googleOAuthUseCustomClient = cfg.googleOAuthUseCustomClient,
                            eventCode = cfg.eventCode ?: "",
                            toaApiKey = cfg.toaApiKey ?: "",
                            tbaApiKey = cfg.tbaApiKey ?: "",
                            aiMode = cfg.aiMode ?: "STUDIO",
                            geminiApiKey = cfg.geminiApiKey ?: "",
                            geminiModel = cfg.geminiModel
                                ?.takeUnless { it == "gemini-1.5-flash" }
                                ?: DEFAULT_GEMINI_MODEL,
                            vertexServiceAccountPath = cfg.vertexServiceAccountPath ?: "",
                            vertexProjectId = cfg.vertexProjectId ?: "",
                            vertexLocation = cfg.vertexLocation ?: "us-central1"
                        )
                    }
                    if (cfg.driveDestination != null && oauthService.authState.value is AuthState.Authenticated) {
                        onIntent(ProfileIntent.RefreshDriveDestination(cfg))
                    }
                }
                is ProfileIntent.GoogleSignIn -> {
                    oauthService.startGoogleLogin(intent.config)
                }
                is ProfileIntent.PickExistingDriveDestination -> {
                    _state.update { it.copy(isDriveDestinationBusy = true, errorMessage = null) }
                    oauthService.startGoogleDriveFolderPicker(intent.config) { folderId ->
                        configureDriveDestination(
                            ProfileIntent.ConfigureDriveDestination(
                                config = intent.config,
                                type = intent.type,
                                displayName = intent.displayName,
                                existingFolderReference = folderId,
                            ),
                        )
                    }
                }
                is ProfileIntent.ConfigureDriveDestination -> {
                    configureDriveDestination(intent)
                }
                is ProfileIntent.RefreshDriveDestination -> {
                    _state.update { it.copy(isDriveDestinationBusy = true, errorMessage = null) }
                    try {
                        val status = googleDriveService.inspectDestination(intent.config.driveDestination)
                        _state.update { it.copy(driveDestinationStatus = status, isDriveDestinationBusy = false) }
                    } catch (e: Exception) {
                        _state.update {
                            it.copy(
                                driveDestinationStatus = null,
                                isDriveDestinationBusy = false,
                                errorMessage = e.message ?: "The Drive destination is inaccessible.",
                            )
                        }
                    }
                }
                ProfileIntent.ConfigUpdateApplied -> _state.update { it.copy(pendingConfigUpdate = null) }
                is ProfileIntent.SignOut -> {
                    oauthService.logout()
                }
                is ProfileIntent.PerformDeltaSync -> {
                    val cfg = _state.value.config ?: return@launch
                    _state.update { it.copy(syncStatus = "Running delta sync...") }
                    try {
                        withContext(Dispatchers.IO) {
                            syncEngineService.performDeltaSync(cfg.teamId, cfg.seasonId, intent.firebaseToken)
                        }
                        _state.update { it.copy(syncStatus = "Sync successful!") }
                    } catch (e: Exception) {
                        _state.update { it.copy(syncStatus = "Sync failed: ${e.message}") }
                    }
                }
                is ProfileIntent.ClearSyncStatus -> {
                    _state.update { it.copy(syncStatus = "") }
                }
            }
        }
    }

    private suspend fun configureDriveDestination(intent: ProfileIntent.ConfigureDriveDestination) {
        _state.update { it.copy(isDriveDestinationBusy = true, errorMessage = null) }
        try {
            val destination = googleDriveService.configureDestination(
                type = intent.type,
                displayName = intent.displayName,
                existingFolderReference = intent.existingFolderReference,
                sharedDriveId = intent.sharedDriveId,
            )
            val updatedConfig = intent.config.copy(driveDestination = destination)
            val status = googleDriveService.inspectDestination(destination)
            _state.update {
                it.copy(
                    config = updatedConfig,
                    driveDestinationStatus = status,
                    isDriveDestinationBusy = false,
                    pendingConfigUpdate = updatedConfig,
                    syncStatus = "Drive destination ready. Existing local data stays on this computer until you choose Sync.",
                )
            }
        } catch (failure: Exception) {
            _state.update {
                it.copy(
                    isDriveDestinationBusy = false,
                    errorMessage = failure.message ?: "The Drive destination could not be configured.",
                )
            }
        }
    }
}
