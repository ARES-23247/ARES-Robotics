package com.ares.analytics.ui.screens

import com.ares.analytics.ui.theme.AresOnAccent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ares.analytics.service.AuthState
import com.ares.analytics.service.DrivePickerState
import com.ares.analytics.service.OAuthService
import com.ares.analytics.ui.screens.onboarding.AuthStep
import com.ares.analytics.ui.screens.onboarding.JavaVerificationStep
import com.ares.analytics.ui.screens.onboarding.SyncStep
import com.ares.analytics.ui.screens.onboarding.WelcomeStep
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresCyanGlow
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.viewmodel.OnboardingIntent
import com.ares.analytics.viewmodel.ProjectSetupMode
import com.ares.analytics.viewmodel.OnboardingStep
import com.ares.analytics.viewmodel.OnboardingViewModel
import javax.swing.JFileChooser

/** Novice-first, four-stage workspace setup. */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    oauthService: OAuthService,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val authState by oauthService.authState.collectAsState()
    val drivePickerState by oauthService.drivePickerState.collectAsState()
    val token = (authState as? AuthState.Authenticated)?.idToken
    val contentScrollState = rememberScrollState()

    LaunchedEffect(state.teamId, token) {
        if (token != null && state.teamId.isNotBlank()) {
            viewModel.handleIntent(OnboardingIntent.FetchCloudRobots(token))
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(AresBackground),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(AresCyanGlow, Color.Transparent), radius = 800f),
            ),
        )

        Surface(
            modifier = Modifier
                .width(680.dp)
                .heightIn(max = 840.dp)
                .border(1.dp, AresBorder, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = AresSurface,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(28.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                WelcomeStep(state.currentStep)
                HorizontalDivider(color = AresBorder)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 14.dp)
                            .verticalScroll(contentScrollState),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        if (state.currentStep == OnboardingStep.OPTIONAL) {
                            AuthStep(
                        authState = authState,
                        managedGoogleSignInAvailable = oauthService.managedGoogleClientAvailable,
                        driveDestination = state.driveDestination,
                        isDriveDestinationBusy = state.isDriveDestinationBusy || drivePickerState is DrivePickerState.Picking,
                        driveDestinationError = (drivePickerState as? DrivePickerState.Error)?.message
                            ?: state.driveDestinationError,
                        expanded = state.cloudSetupExpanded,
                        onExpandedChange = {
                            viewModel.handleIntent(OnboardingIntent.SetCloudSetupExpanded(it))
                        },
                        onSignInClick = {
                            oauthService.startGoogleLogin()
                        },
                        onConfigureDestination = { type, name, folder, drive ->
                            viewModel.handleIntent(
                                OnboardingIntent.ConfigureDriveDestination(type, name, folder, drive),
                            )
                        },
                        onPickExistingDestination = { type, name ->
                            oauthService.startGoogleDriveFolderPicker { folderId ->
                                viewModel.handleIntent(
                                    OnboardingIntent.ConfigureDriveDestination(
                                        type = type,
                                        displayName = name,
                                        existingFolderReference = folderId,
                                    ),
                                )
                            }
                        },
                            )
                        }

                        SyncStep(
                    step = state.currentStep,
                    projectSetupMode = state.projectSetupMode,
                    projectPath = state.projectPath,
                    projectParentPath = state.projectParentPath,
                    projectFolderName = state.projectFolderName,
                    projectDetectionMessage = state.projectDetectionMessage,
                    projectTemplateName = state.projectTemplateName,
                    projectTemplateVersion = state.projectTemplateVersion,
                    projectCreationMessage = state.projectCreationMessage,
                    authoringModel = state.authoringModel,
                    onAuthoringModelChange = {
                        viewModel.handleIntent(OnboardingIntent.SetAuthoringModel(it))
                    },
                    onProjectSetupModeChange = {
                        viewModel.handleIntent(OnboardingIntent.SetProjectSetupMode(it))
                    },
                    onProjectPathChange = {
                        viewModel.handleIntent(OnboardingIntent.UpdateProjectPath(it))
                    },
                    onBrowseProject = {
                        val chooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = "Choose your robot project folder"
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            // Updating a real directory automatically runs project detection.
                            viewModel.handleIntent(OnboardingIntent.UpdateProjectPath(chooser.selectedFile.absolutePath))
                        }
                    },
                    onProjectParentPathChange = {
                        viewModel.handleIntent(OnboardingIntent.UpdateProjectParentPath(it))
                    },
                    onProjectFolderNameChange = {
                        viewModel.handleIntent(OnboardingIntent.UpdateProjectFolderName(it))
                    },
                    onBrowseProjectParent = {
                        val chooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = "Choose where to create the robot project"
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            viewModel.handleIntent(
                                OnboardingIntent.UpdateProjectParentPath(chooser.selectedFile.absolutePath),
                            )
                        }
                    },
                    teamId = state.teamId,
                    onTeamIdChange = { viewModel.handleIntent(OnboardingIntent.UpdateTeamId(it)) },
                    cloudRobots = state.cloudRobots,
                    selectedOptionText = state.selectedOptionText,
                    onSelectedOptionTextChange = {
                        viewModel.handleIntent(OnboardingIntent.UpdateSelectedOptionText(it))
                    },
                    robotId = state.robotId,
                    onRobotIdChange = { viewModel.handleIntent(OnboardingIntent.UpdateRobotId(it)) },
                    seasonId = state.seasonId,
                    onSeasonIdChange = { viewModel.handleIntent(OnboardingIntent.UpdateSeasonId(it)) },
                    robotName = state.robotName,
                    onRobotNameChange = { viewModel.handleIntent(OnboardingIntent.UpdateRobotName(it)) },
                    league = state.league,
                    onLeagueChange = { viewModel.handleIntent(OnboardingIntent.UpdateLeague(it)) },
                    nt4Host = state.nt4Host,
                    onNt4HostChange = { viewModel.handleIntent(OnboardingIntent.UpdateNt4Host(it)) },
                    simulatorCommand = state.simulatorCommand,
                    onSimulatorCommandChange = {
                        viewModel.handleIntent(OnboardingIntent.UpdateSimulatorCommand(it))
                    },
                    advancedExpanded = state.advancedSetupExpanded,
                    onAdvancedExpandedChange = {
                        viewModel.handleIntent(OnboardingIntent.SetAdvancedSetupExpanded(it))
                    },
                    fieldErrors = state.fieldErrors,
                    cloudConfigured = authState is AuthState.Authenticated,
                        )

                        if (state.currentStep == OnboardingStep.REVIEW) {
                            JavaVerificationStep(
                        isValid = state.javaEnvValid,
                        isVerifying = state.isVerifyingJava,
                        message = state.javaEnvMsg,
                        installState = state.toolchainInstallState,
                        onVerifyClick = { viewModel.handleIntent(OnboardingIntent.VerifyJava) },
                        onInstallClick = { viewModel.handleIntent(OnboardingIntent.InstallManagedJdk) },
                            )
                        }

                        state.errorMessage?.let { error ->
                            Text(error, color = AresError, style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.isSaving && !state.projectCreationMessage.isNullOrBlank()) {
                            Text(
                                state.projectCreationMessage!!,
                                color = AresCyan,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(contentScrollState),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                    )
                }

                HorizontalDivider(color = AresBorder)
                NavigationButtons(
                    step = state.currentStep,
                    isSaving = state.isSaving,
                    finishLabel = if (state.projectSetupMode == ProjectSetupMode.EXPLORE_DEMO) {
                        "Create demo copy"
                    } else {
                        "Create standalone project"
                    },
                    onCancel = onCancel,
                    onBack = { viewModel.handleIntent(OnboardingIntent.PreviousStep) },
                    onNext = { viewModel.handleIntent(OnboardingIntent.NextStep) },
                    onFinish = { viewModel.handleIntent(OnboardingIntent.SubmitConfig) },
                )
            }
        }
    }
}

@Composable
private fun NavigationButtons(
    step: OnboardingStep,
    isSaving: Boolean,
    finishLabel: String,
    onCancel: (() -> Unit)?,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            step != OnboardingStep.PROJECT -> OutlinedButton(
                onClick = onBack,
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
                border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
            ) { Text("Back", color = AresTextPrimary) }
            onCancel != null -> OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
            ) { Text("Cancel", color = AresTextPrimary) }
        }

        Button(
            onClick = if (step == OnboardingStep.REVIEW) onFinish else onNext,
            enabled = !isSaving,
            modifier = Modifier.weight(2f),
            colors = ButtonDefaults.buttonColors(
                    containerColor = AresCyan,
                    contentColor = AresOnAccent, disabledContainerColor = AresBorder),
        ) {
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AresOnAccent, strokeWidth = 2.dp)
            } else {
                val label = when (step) {
                    OnboardingStep.PROJECT -> "Continue"
                    OnboardingStep.ROBOT -> "Continue"
                    OnboardingStep.OPTIONAL -> "Review setup"
                    OnboardingStep.REVIEW -> finishLabel
                }
                Text(label, color = AresOnAccent, fontWeight = FontWeight.Bold)
            }
        }
    }
}
