package com.ares.analytics.viewmodel

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingModelTest {
    @Test
    fun `JDK 17 output is accepted with concise readiness message`() {
        val result = evaluateJavaBuildTools(
            commandSucceeded = true,
            rawMessage = "Java executable valid. Output:\nopenjdk version \"17.0.12\" 2024-07-16",
        )

        assertTrue(result.isValid)
        assertEquals(17, result.majorVersion)
        assertEquals("JDK 17 is ready for robot builds and simulation.", result.message)
    }

    @Test
    fun `JDK 21 is accepted for robot builds and simulation`() {
        val result = evaluateJavaBuildTools(
            commandSucceeded = true,
            rawMessage = "java version \"21.0.2\" 2024-01-16 LTS",
        )

        assertTrue(result.isValid)
        assertEquals(21, result.majorVersion)
        assertEquals("JDK 21 is ready for robot builds and simulation.", result.message)
    }

    @Test
    fun `unsupported Java is an actionable non-app-blocking build tools warning`() {
        val result = evaluateJavaBuildTools(
            commandSucceeded = true,
            rawMessage = "java version \"11.0.24\" 2024-07-16 LTS",
        )

        assertFalse(result.isValid)
        assertEquals(11, result.majorVersion)
        assertTrue(result.message.startsWith("ARES Robotics Studio is ready"))
        assertTrue(result.message.contains("JDK 17 or 21"))
    }

    @Test
    fun `legacy Java version syntax is parsed correctly`() {
        assertEquals(8, parseJavaMajorVersion("java version \"1.8.0_402\""))
        assertNull(parseJavaMajorVersion("Java executable valid but returned no version text"))
    }

    @Test
    fun `command execution failure returns invalid status and indicates Java could not be started`() {
        val result = evaluateJavaBuildTools(
            commandSucceeded = false,
            rawMessage = "java: command not found",
        )

        assertFalse(result.isValid)
        assertNull(result.majorVersion)
        assertTrue(result.message.startsWith("ARES Robotics Studio is ready"))
        assertTrue(result.message.contains("supported JDK"))
    }

    @Test
    fun `parseJavaMajorVersion safely returns null for empty blank or malformed version strings`() {
        assertNull(parseJavaMajorVersion(""))
        assertNull(parseJavaMajorVersion("   \t\n"))
        assertNull(parseJavaMajorVersion("invalid version text"))
        assertNull(parseJavaMajorVersion("java version \"invalid\""))
        assertNull(parseJavaMajorVersion("openjdk version \"\""))
        assertNull(parseJavaMajorVersion("version = malformed"))
    }

    @Test
    fun `first run recommends creating a robot and reports only its project location error`() {
        assertEquals(ProjectSetupMode.CREATE_NEW, OnboardingState().projectSetupMode)
        val errors = validateOnboardingFields(OnboardingState(), OnboardingStep.PROJECT)

        assertEquals("Choose where ARES should create the robot project.", errors.projectPath)
        assertNull(errors.teamId)
        assertNull(errors.seasonId)
        assertNull(errors.robotId)
    }

    @Test
    fun `robot step has field-specific errors and accepts a real project folder`() {
        val directory = Files.createTempDirectory("ares-onboarding-test").toFile()
        try {
            val errors = validateOnboardingFields(
                OnboardingState(
                    projectSetupMode = ProjectSetupMode.OPEN_EXISTING,
                    projectPath = directory.absolutePath,
                    teamId = "23A47",
                    seasonId = "",
                    robotId = "",
                ),
                OnboardingStep.ROBOT,
            )

            assertNull(errors.projectPath)
            assertEquals("Use numbers only for the team number.", errors.teamId)
            assertEquals("Enter the season, for example 2026.", errors.seasonId)
            assertEquals("Enter a short robot ID.", errors.robotId)
        } finally {
            directory.delete()
        }
    }

    @Test
    fun `optional cloud and advanced fields never block local readiness`() {
        val directory = Files.createTempDirectory("ares-onboarding-ready-test").toFile()
        try {
            val state = OnboardingState(
                projectSetupMode = ProjectSetupMode.OPEN_EXISTING,
                projectPath = directory.absolutePath,
                teamId = "23247",
                seasonId = "2026",
                robotId = "AresIII",
                nt4Host = "",
                simulatorCommand = "",
            )

            assertFalse(validateOnboardingFields(state, OnboardingStep.REVIEW).hasRequiredFieldErrors)
        } finally {
            directory.delete()
        }
    }

    @Test
    fun `missing robot build tools do not block local workspace completion`() {
        val directory = Files.createTempDirectory("ares-onboarding-no-jdk-test").toFile()
        try {
            val state = OnboardingState(
                projectSetupMode = ProjectSetupMode.OPEN_EXISTING,
                projectPath = directory.absolutePath,
                teamId = "23247",
                seasonId = "2026",
                robotId = "AresIII",
                javaEnvValid = false,
                javaEnvMsg = "Robot build tools are unavailable.",
            )

            assertFalse(validateOnboardingCompletion(state).hasRequiredFieldErrors)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `new project mode accepts an unused direct child without requiring it to exist yet`() {
        val parent = Files.createTempDirectory("ares-onboarding-create-test").toFile()
        try {
            val state = OnboardingState(
                projectSetupMode = ProjectSetupMode.CREATE_NEW,
                projectParentPath = parent.path,
                projectFolderName = "student-robot",
                projectPath = File(parent, "student-robot").path,
            )

            assertNull(validateOnboardingFields(state, OnboardingStep.PROJECT).projectPath)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `new project mode blocks traversal and existing destinations`() {
        val parent = Files.createTempDirectory("ares-onboarding-create-block-test").toFile()
        try {
            val traversal = OnboardingState(
                projectSetupMode = ProjectSetupMode.CREATE_NEW,
                projectParentPath = parent.path,
                projectFolderName = "../escape",
            )
            assertTrue(validateOnboardingFields(traversal, OnboardingStep.PROJECT).projectPath!!.contains("Folder names"))

            File(parent, "existing").mkdirs()
            val existing = traversal.copy(projectFolderName = "existing")
            assertTrue(validateOnboardingFields(existing, OnboardingStep.PROJECT).projectPath!!.contains("already exists"))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `demo mode creates one simulation-first editable copy with novice defaults`() {
        val parent = Files.createTempDirectory("ares-onboarding-demo-test").toFile()
        try {
            val state = OnboardingState(
                projectSetupMode = ProjectSetupMode.EXPLORE_DEMO,
                projectParentPath = parent.path,
                projectFolderName = DEMO_PROJECT_FOLDER,
                projectPath = File(parent, DEMO_PROJECT_FOLDER).path,
                teamId = DEMO_TEAM_ID,
                seasonId = DEMO_SEASON_ID,
                robotId = DEMO_ROBOT_ID,
                robotName = DEMO_ROBOT_NAME,
            )

            assertTrue(state.projectSetupMode.createsProject)
            assertFalse(validateOnboardingCompletion(state).hasRequiredFieldErrors)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `recognized project detection advances from the project page to robot details`() {
        assertEquals(OnboardingStep.ROBOT, advanceAfterDetection(OnboardingStep.PROJECT, recognizedProject = true))
    }

    @Test
    fun `unrecognized directories never advance the wizard`() {
        assertEquals(OnboardingStep.PROJECT, advanceAfterDetection(OnboardingStep.PROJECT, recognizedProject = false))
    }

    @Test
    fun `detection never moves a student who already advanced`() {
        assertEquals(OnboardingStep.OPTIONAL, advanceAfterDetection(OnboardingStep.OPTIONAL, recognizedProject = true))
    }
}
