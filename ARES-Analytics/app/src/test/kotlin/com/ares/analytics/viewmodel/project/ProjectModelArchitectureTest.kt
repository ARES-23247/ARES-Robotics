package com.ares.analytics.viewmodel.project

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ProjectModelArchitectureTest {
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
            "projectGenerator = services.processManagerService" !in mainScreen,
            "Authoring ViewModels must receive SessionProjectGenerator, never ProcessManagerService.",
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
        val routeHostStart = mainScreen.indexOf("when (activeNav)")
        assertTrue(routeHostStart >= 0, "MainScreen route host was not found")

        val applicationShell = mainScreen.substring(0, routeHostStart)
        assertTrue(
            "gamepad1State.collectAsState()" !in applicationShell &&
                "gamepad2State.collectAsState()" !in applicationShell,
            "High-rate gamepad state must be collected only inside visible controller routes.",
        )
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
