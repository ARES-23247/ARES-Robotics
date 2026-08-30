package com.ares.analytics.viewmodel.project

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ProjectModelArchitectureTest {
    @Test
    fun `drive sync robot design and ai diagnostics have distinct service owners`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }
        val services = File(sourceRoot, "service")
        val sync = File(services, "SyncEngineService.kt").readText()
        val provider = File(services, "GenerativeAiService.kt").readText()
        val design = File(services, "RobotDesignAssistantService.kt").readText()
        val diagnostics = File(services, "AiDiagnosticsService.kt").readText()

        listOf(
            "requestSubsystemDesignProposal",
            "requestDrivebaseDesignProposal",
            "requestControlsDesignProposal",
            "requestForensics",
            "requestChatCoach",
            "requestSqlAnalysis",
            "generativelanguage.googleapis.com",
            "aiplatform.googleapis.com",
        ).forEach { forbidden ->
            assertTrue(forbidden !in sync, "SyncEngineService must remain Drive synchronization only: $forbidden")
        }
        assertTrue("requestStructuredJson" in provider && "requestText" in provider)
        assertTrue("requestSubsystemDesignProposal" in design && "requestControlsDesignProposal" in design)
        assertTrue("requestForensics" in diagnostics && "requestSqlAnalysis" in diagnostics)
    }

    @Test
    fun `project history authentication automatic backup and recovery have distinct owners`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }
        val boundary = File(sourceRoot, "service/versioncontrol")
        val history = File(boundary, "ProjectVersionControlService.kt").readText()
        val authentication = File(boundary, "GitHubAuthenticationService.kt").readText()
        val autoSync = File(boundary, "ProjectBackupAutoSyncService.kt").readText()
        val remoteBackup = File(boundary, "ProjectRemoteBackupService.kt").readText()
        val recovery = File(boundary, "ProjectRecoveryService.kt").readText()

        listOf(
            "beginDeviceAuthorization",
            "ProjectGitHubCredentialRepository",
            "ProjectBackupAutoSyncStatus",
            "Channel<String>",
            "AUTO_SYNC_RETRY",
            "previewGitHubRestore",
            "recoverToSafetyPoint",
            "MergeCommand",
            "ResetCommand",
            "connectApprovedRepository",
            "pushBackup",
            "remotePusher",
        ).forEach { forbidden ->
            assertTrue(
                forbidden !in history,
                "ProjectVersionControlService must not reclaim authentication, automatic-backup, or recovery ownership: $forbidden",
            )
        }
        assertTrue("beginDeviceAuthorization" in authentication)
        assertTrue("ProjectGitHubCredentialRepository" in authentication)
        assertTrue("Channel<String>" in autoSync)
        assertTrue("ProjectBackupAutoSyncStatus" in autoSync)
        assertTrue("connectApprovedRepository" in remoteBackup)
        assertTrue("pushBackup" in remoteBackup)
        assertTrue("remotePusher" in remoteBackup)
        assertTrue("previewGitHubRestore" in recovery)
        assertTrue("recoverToSafetyPoint" in recovery)
        assertTrue("MergeCommand" in recovery)
        assertTrue("ResetCommand" in recovery)
    }

    @Test
    fun `studio features consume the assembled project instead of decoding the action catalog`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }

        val projectBoundary = File(sourceRoot, "viewmodel/project").canonicalFile
        val templateBoundary = File(sourceRoot, "service/project").canonicalFile
        val violations = sourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filterNot { file -> file.canonicalFile.toPath().startsWith(projectBoundary.toPath()) }
            .filterNot { file -> file.canonicalFile.toPath().startsWith(templateBoundary.toPath()) }
            .mapNotNull { file ->
                val source = file.readText()
                val forbidden = listOf(
                    "CapabilityCatalogCodec.decode(",
                    "CapabilityCatalogProjectRepository()",
                ).filter(source::contains)
                forbidden.takeIf(List<String>::isNotEmpty)?.let { file.relativeTo(sourceRoot).path to it }
            }
            .toList()

        assertTrue(
            violations.isEmpty(),
            "Studio features must load action capabilities through AresProjectDocuments: " +
                violations.joinToString { (file, patterns) -> "$file uses ${patterns.joinToString()}" },
        )
    }

    @Test
    fun `main screen routes project execution through the session coordinator`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }
        val mainScreen = File(sourceRoot, "ui/screens/MainScreen.kt").readText()

        val forbidden = listOf(
            ".runBuild(",
            ".runSimulation(",
            ".deployToRobot(",
            ".generateAresProject(",
        ).filter(mainScreen::contains)
        assertTrue(
            forbidden.isEmpty(),
            "MainScreen must authorize project execution through ProjectExecutionCoordinator: ${forbidden.joinToString()}",
        )
        assertTrue(
            "projectGenerator = services.projectBuildService" !in mainScreen,
            "Authoring ViewModels must receive SessionProjectGenerator, never ProjectBuildService.",
        )
    }

    @Test
    fun `build and deployment services own distinct current contracts`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }
        val buildService = File(sourceRoot, "service/ProjectBuildService.kt").readText()
        val deploymentService = File(sourceRoot, "service/RobotDeploymentService.kt").readText()

        assertTrue(
            "deployToRobot" !in buildService && "resolveAdbExecutable" !in buildService,
            "Project generation/build must not retain deployment forwarding or device ownership.",
        )
        assertTrue(
            "fun deploy(" in deploymentService && "commandFactory.adbInstall" in deploymentService,
            "RobotDeploymentService must directly own the current deployment contract.",
        )
    }

    @Test
    fun `main shell does not collect high rate gamepad state outside visible routes`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }
        val mainScreen = File(sourceRoot, "ui/screens/MainScreen.kt").readText()
        val routeHost = File(sourceRoot, "ui/screens/RobotAuthoringRouteHost.kt").readText()
        assertTrue(
            "gamepad1State.collectAsState()" !in mainScreen &&
                "gamepad2State.collectAsState()" !in mainScreen,
            "High-rate gamepad state must be collected only inside visible controller routes.",
        )
        assertTrue(
            "gamepad1State.collectAsState()" in routeHost &&
                "gamepad2State.collectAsState()" in routeHost,
            "RobotAuthoringRouteHost must own controller-state collection for visible routes.",
        )
    }

    @Test
    fun `workspace selector owns dropdown state without application services`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }
        val mainScreen = File(sourceRoot, "ui/screens/MainScreen.kt").readText()
        val selector = File(sourceRoot, "ui/components/WorkspaceSelector.kt").readText()

        assertTrue("WorkspaceSelector(" in mainScreen)
        assertTrue("DropdownMenu(" !in mainScreen, "Workspace menu state belongs to WorkspaceSelector.")
        assertTrue("ServiceRegistry" !in selector, "The workspace selector receives data and actions, not application services.")
    }

    @Test
    fun `workspace route host owns navigation without the application registry`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }
        val mainScreen = File(sourceRoot, "ui/screens/MainScreen.kt").readText()
        val routeHost = File(sourceRoot, "ui/screens/WorkspaceRouteHost.kt").readText()

        assertTrue("WorkspaceRouteHost(" in mainScreen)
        assertTrue("when (activeNav)" !in mainScreen, "Navigation rendering belongs to WorkspaceRouteHost.")
        assertTrue("WorkspaceRouteFeatureScope" in routeHost)
        assertTrue("WorkspaceRouteState" in routeHost)
        assertTrue("WorkspaceRouteActions" in routeHost)
        assertTrue("ServiceRegistry" !in routeHost, "Route rendering must not pull arbitrary application services.")
    }

    @Test
    fun `robot authoring route host uses a typed feature scope instead of the global registry`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }
        val routeHost = File(sourceRoot, "ui/screens/RobotAuthoringRouteHost.kt").readText()

        assertTrue(
            "RobotAuthoringFeatureScope" in routeHost,
            "Robot authoring routes must declare their capability boundary explicitly.",
        )
        assertTrue(
            "ServiceRegistry" !in routeHost,
            "Robot authoring routes must not pull arbitrary application services.",
        )
        assertTrue(
            "gamepad1State.collectAsState()" in routeHost && "gamepad2State.collectAsState()" in routeHost,
            "Controller state must be collected in the visible authoring route host.",
        )
        assertTrue(
            "scope.controls.state.collectAsState()" in routeHost,
            "Controls state must be collected only while a controls-aware authoring route is visible.",
        )
    }

    @Test
    fun `academy evidence subscriptions stay outside the main shell`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }
        val mainScreen = File(sourceRoot, "ui/screens/MainScreen.kt").readText()
        val runtimeHost = File(sourceRoot, "ui/screens/AcademyRuntimeHost.kt").readText()

        val forbiddenRootSubscriptions = listOf(
            "controlsEditorViewModel.state.collectAsState()",
            "subsystemGeneratorViewModel.state.collectAsState()",
            "tuningViewModel.state.collectAsState()",
            "superstructureStudioViewModel.state.collectAsState()",
            "pathPlannerViewModel.state.collectAsState()",
            "guidedRunAnalysisViewModel.state.collectAsState()",
        ).filter(mainScreen::contains)
        assertTrue(
            forbiddenRootSubscriptions.isEmpty(),
            "Hidden authoring evidence must not invalidate MainScreen: ${forbiddenRootSubscriptions.joinToString()}",
        )
        assertTrue(
            "dashboardViewModel.state.collectAsState()" !in mainScreen,
            "MainScreen must observe DashboardShellState instead of full dashboard/replay/alert state.",
        )
        assertTrue(
            "robotStudioViewModel.state.collectAsState()" !in mainScreen,
            "MainScreen must observe RobotStudioShellState instead of the full authoring-stage model.",
        )
        assertTrue(
            "AcademyRuntimeFeatureScope" in runtimeHost && "ServiceRegistry" !in runtimeHost,
            "Academy evidence must be collected behind a typed, feature-owned boundary.",
        )
    }

    @Test
    fun `run data route host uses a typed feature scope instead of the global registry`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }
        val routeHost = File(sourceRoot, "ui/screens/RunDataRouteHost.kt").readText()

        assertTrue(
            "RunDataFeatureScope" in routeHost,
            "Run-data routes must declare their database, sync, import, and analysis boundary explicitly.",
        )
        assertTrue(
            "ServiceRegistry" !in routeHost,
            "Run-data routes must not pull arbitrary application services.",
        )
    }

    @Test
    fun `workspace services route host uses a typed feature scope instead of the global registry`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }
        val routeHost = File(sourceRoot, "ui/screens/WorkspaceServicesRouteHost.kt").readText()

        assertTrue(
            "WorkspaceServicesFeatureScope" in routeHost,
            "Workspace-service routes must declare their preference, backup, and integration boundary explicitly.",
        )
        assertTrue(
            "ServiceRegistry" !in routeHost,
            "Workspace-service routes must not pull arbitrary application services.",
        )
    }

    @Test
    fun `dashboard consumes typed services instead of the global registry adapter`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }
        val dashboard = File(sourceRoot, "ui/screens/DashboardScreen.kt").readText()
        val widgetServices = File(sourceRoot, "ui/components/dashboard/DashboardWidgetServices.kt").readText()

        assertTrue("DashboardFeatureServices" in dashboard, "Dashboard must declare its explicit service boundary.")
        assertTrue("ServiceRegistry" !in dashboard, "Dashboard must not pull arbitrary application services.")
        assertTrue(
            "ServiceRegistryDashboardWidgetServices" !in widgetServices,
            "Dashboard widget services must be concrete capability groups, not a registry wrapper.",
        )
    }

    @Test
    fun `production authoring view models share the long lived project session`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }
        val mainScreen = File(sourceRoot, "ui/screens/MainScreen.kt").readText()

        val constructors = listOf(
            "PathPlannerViewModel(",
            "ControlsEditorViewModel(",
            "SubsystemGeneratorViewModel(",
            "SuperstructureStudioViewModel(",
            "ProjectIdentityViewModel(",
            "DrivebaseBuilderViewModel(",
            "FieldEditorViewModel(",
            "TuningViewModel(",
        )
        constructors.forEach { constructor ->
            val start = mainScreen.indexOf(constructor)
            assertTrue(start >= 0, "MainScreen no longer constructs $constructor; update this boundary test intentionally.")
            val end = mainScreen.indexOf("\n        )", start).takeIf { it >= 0 } ?: (start + 800).coerceAtMost(mainScreen.length)
            val construction = mainScreen.substring(start, end)
            assertTrue(
                "projectSession = services.projectSession" in construction,
                "$constructor must receive the application ProjectSession instead of reconstructing project meaning.",
            )
        }
    }

    @Test
    fun `canonical persistence has no production owner in the viewmodel namespace`() {
        val sourceRoot = sequenceOf(
            File("app/src/main/kotlin/com/ares/analytics"),
            File("src/main/kotlin/com/ares/analytics"),
        ).firstOrNull(File::isDirectory)
        checkNotNull(sourceRoot) { "Could not locate Analytics application sources" }

        val violations = File(sourceRoot, "viewmodel").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val source = file.readText()
                "AtomicProjectFileWriter" in source ||
                    "ProjectDocumentWriteLocks" in source ||
                    Regex("(object|class)\\s+\\w*(ProjectDocumentStore|ProjectRepository)").containsMatchIn(source)
            }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toList()

        assertTrue(
            violations.isEmpty(),
            "Canonical persistence belongs under service/project or a feature service: ${violations.joinToString()}",
        )
    }
}
