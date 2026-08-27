package com.ares.analytics.ui.help

import com.ares.analytics.ui.components.NavigationTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearningCatalogTest {
    @Test
    fun `catalog offers a hardware-free first success and real destinations`() {
        val first = LearningCatalog.lessons.first()
        assertEquals(LearningAction.START_SIMULATOR, first.action)
        assertFalse(first.requiresRobot)
        assertEquals(NavigationTarget.DASHBOARD, first.destination)
        assertTrue(LearningCatalog.lessons.all { it.steps.isNotEmpty() && it.successLooksLike.isNotBlank() })
        assertTrue(LearningCatalog.lessons.all { it.checkpoints.isNotEmpty() })
        assertTrue(first.checkpoints.isNotEmpty())
    }

    @Test
    fun `search understands student tasks and concepts`() {
        assertTrue(LearningCatalog.search("simulation").any { it.id == "start-simulator" })
        assertTrue(LearningCatalog.search("redux").any { it.id == "safe-subsystem" })
        assertTrue(LearningCatalog.search("sysid", LearningLevel.STARTER).isEmpty())
    }

    @Test
    fun `contextual help opens the lesson for the active workflow`() {
        assertEquals("compare-run-evidence", LearningCatalog.lessonFor(NavigationTarget.IMPORT_CENTER)?.id)
        assertEquals("compare-run-evidence", LearningCatalog.lessonFor(NavigationTarget.RUN_HISTORY)?.id)
        assertEquals("first-routine", LearningCatalog.lessonFor(NavigationTarget.PATH_PLANNER)?.id)
        assertEquals("drivebase-blueprint", LearningCatalog.lessonFor(NavigationTarget.DRIVEBASE_BUILDER)?.id)
        assertEquals("robot-studio-tour", LearningCatalog.lessonFor(NavigationTarget.ROBOT_STUDIO)?.id)
        assertEquals("robot-studio-tour", LearningCatalog.lessonFor(NavigationTarget.PROJECT_IDENTITY)?.id)
        assertEquals("protect-project-history", LearningCatalog.lessonFor(NavigationTarget.PROJECT_BACKUP)?.id)
        assertEquals("developer-reference", LearningCatalog.lessonFor(NavigationTarget.KDOC_VIEWER)?.id)
        assertEquals(null, LearningCatalog.lessonFor(NavigationTarget.ADMIN))
    }

    @Test
    fun `every workflow screen except profile and admin has contextual help`() {
        val intentionallyUnmapped = setOf(NavigationTarget.PROFILE, NavigationTarget.ADMIN)
        NavigationTarget.entries
            .filter { it !in intentionallyUnmapped }
            .forEach { target ->
                assertTrue(
                    LearningCatalog.lessonFor(target) != null,
                    "Missing contextual lesson for ${target.name}",
                )
            }
        assertEquals("compare-run-evidence", LearningCatalog.lessonFor(NavigationTarget.CLOUD)?.id)
        assertEquals("edit-field-documents", LearningCatalog.lessonFor(NavigationTarget.FIELD_EDITOR)?.id)
        assertEquals("read-driver-coaching", LearningCatalog.lessonFor(NavigationTarget.MATCH_STRATEGY)?.id)
        assertEquals("compare-run-evidence", LearningCatalog.lessonFor(NavigationTarget.GUIDED_RUN_ANALYSIS)?.id)
        assertEquals("query-stored-telemetry", LearningCatalog.lessonFor(NavigationTarget.DATABASE_VIEWER)?.id)
        assertEquals("review-hardware-addresses", LearningCatalog.lessonFor(NavigationTarget.HARDWARE_SETUP)?.id)
    }

    @Test
    fun `paths and prerequisites reference stable catalog lessons`() {
        val lessonIds = LearningCatalog.lessons.map { it.id }
        assertEquals(lessonIds.size, lessonIds.toSet().size)
        assertEquals(LearningCatalog.paths.size, LearningCatalog.paths.map { it.id }.toSet().size)
        assertEquals(
            LearningCatalog.lessons.flatMap { it.checkpoints }.size,
            LearningCatalog.lessons.flatMap { it.checkpoints }.map { it.id }.toSet().size,
        )
        assertTrue(LearningCatalog.paths.all { path ->
            path.lessonIds.isNotEmpty() && path.lessonIds.all { it in lessonIds }
        })
        assertTrue(LearningCatalog.lessons.all { lesson -> lesson.prerequisiteLessonIds.all { it in lessonIds } })
    }

    @Test
    fun `drivetrains track starts without a robot and verifies simulation evidence`() {
        val track = LearningCatalog.path("drivetrains-odometry") ?: error("Missing drivetrains track")
        val firstLesson = LearningCatalog.lesson(track.lessonIds.first()) ?: error("Missing first lesson")
        assertFalse(firstLesson.requiresRobot)
        assertEquals(LearningAction.START_SIMULATOR, firstLesson.action)
        assertTrue(firstLesson.checkpoints.any { it.evidence == LearningCheckpointEvidence.SELF_REPORTED })
        assertTrue(firstLesson.checkpoints.any { it.evidence != LearningCheckpointEvidence.SELF_REPORTED })
    }

    @Test
    fun `five progressive engineering tracks are defined`() {
        assertEquals(5, LearningCatalog.paths.size)
        val pathIds = LearningCatalog.paths.map { it.id }.toSet()
        assertTrue("drivetrains-odometry" in pathIds)
        assertTrue("subsystems-architecture" in pathIds)
        assertTrue("superstructure-stateflow" in pathIds)
        assertTrue("control-tuning" in pathIds)
        assertTrue("autonomous-telemetry" in pathIds)
    }

    @Test
    fun `every interactive lab has guidance and a lesson`() {
        LearningLab.entries.forEach { lab ->
            val guide = LearningCatalog.labGuide(lab)
            assertTrue(guide.tryThis.isNotEmpty())
            assertTrue(guide.reflectionQuestions.isNotEmpty())
            assertTrue(LearningCatalog.lessons.any { it.lab == lab && it.action == LearningAction.OPEN_LAB })
        }
    }

    @Test
    fun `homing lab teaches freshness stall evidence and neutral recovery`() {
        val lesson = LearningCatalog.lesson("homing-safety-lab") ?: error("Missing homing safety lab")

        assertEquals(LearningLab.HOMING_SAFETY, lesson.lab)
        assertFalse(lesson.requiresRobot)
        assertTrue("stall" in lesson.keywords)
        assertTrue("recovery" in lesson.keywords)
        assertTrue("safe-subsystem" in lesson.prerequisiteLessonIds)
    }

    @Test
    fun `subsystem mission uses real builder evidence`() {
        val lesson = LearningCatalog.lesson("safe-subsystem") ?: error("Missing subsystem mission")

        assertEquals("Build a Safe Mechanism Subsystem", lesson.title)
        assertEquals(NavigationTarget.SUBSYSTEM_GEN, lesson.destination)
        assertFalse(lesson.requiresRobot)
        assertTrue("start-simulator" in lesson.prerequisiteLessonIds)
        assertTrue(lesson.checkpoints.any { it.evidence != LearningCheckpointEvidence.SELF_REPORTED })
    }

    @Test
    fun `controls mission follows the complete saved and generated evidence chain`() {
        val lesson = LearningCatalog.lesson("map-one-control") ?: error("Missing controls mission")

        assertFalse(lesson.requiresRobot)
        assertEquals(
            setOf(
                ControlsMissionCheckpointIds.ACTION_CATALOG,
                ControlsMissionCheckpointIds.SUBSYSTEM_CAPABILITY,
                ControlsMissionCheckpointIds.PLATFORM_MAPPING,
                ControlsMissionCheckpointIds.BINDING_APPLIED,
                ControlsMissionCheckpointIds.BINDING_POLICY,
                ControlsMissionCheckpointIds.SCHEME_SAVED,
                ControlsMissionCheckpointIds.BINDINGS_GENERATED,
                ControlsMissionCheckpointIds.RUNTIME_FLOW,
            ),
            lesson.checkpoints.map { it.id }.toSet(),
        )
        assertTrue(lesson.safetyNote?.contains("restrained") == true)
    }

    @Test
    fun `pit lesson treats telemetry as evidence rather than hardware certification`() {
        val lesson = LearningCatalog.lesson("pit-readiness") ?: error("Missing pit lesson")

        assertTrue(lesson.steps.any { it.contains("STREAM LIVE") })
        assertTrue(lesson.steps.any { it.contains("read-only") })
        assertTrue(lesson.safetyNote?.contains("cannot certify") == true)
        assertFalse(lesson.successLooksLike.contains("report green", ignoreCase = true))
    }

    @Test
    fun `tuning mission is offline project backed and separates review from hardware validation`() {
        val lesson = LearningCatalog.lesson("tuning-evidence") ?: error("Missing tuning mission")

        assertEquals("Run One Safe, Evidence-Guided Tuning Experiment", lesson.title)
        assertEquals(NavigationTarget.TUNING, lesson.destination)
        assertFalse(lesson.requiresRobot)
        assertTrue("control-challenges-lab" in lesson.prerequisiteLessonIds)
        assertTrue(lesson.checkpoints.any { it.evidence != LearningCheckpointEvidence.SELF_REPORTED })
    }

    @Test
    fun `superstructure mission coordinates postures across mechanisms`() {
        val lesson = LearningCatalog.lesson("coordinate-mechanisms") ?: error("Missing superstructure mission")

        assertEquals("Coordinate Multi-Subsystem Robot Postures", lesson.title)
        assertEquals(NavigationTarget.SUPERSTRUCTURE_STUDIO, lesson.destination)
        assertFalse(lesson.requiresRobot)
        assertTrue("safe-subsystem" in lesson.prerequisiteLessonIds)
        assertTrue(lesson.checkpoints.any { it.evidence != LearningCheckpointEvidence.SELF_REPORTED })
    }

    @Test
    fun `stateflow canvas mastery teaches 2D visual layout and Bezier transitions`() {
        val lesson = LearningCatalog.lesson("stateflow-canvas-mastery") ?: error("Missing stateflow canvas lesson")

        assertEquals("2D Stateflow Canvas & Directed Bezier Transitions", lesson.title)
        assertEquals(NavigationTarget.SUPERSTRUCTURE_STUDIO, lesson.destination)
        assertFalse(lesson.requiresRobot)
        assertTrue("coordinate-mechanisms" in lesson.prerequisiteLessonIds)
        assertTrue(lesson.checkpoints.any { it.id == "stateflow-canvas-mastery.nodes-positioned" })
        assertTrue(lesson.checkpoints.any { it.id == "stateflow-canvas-mastery.bezier-transitions" })
        assertTrue(lesson.checkpoints.any { it.id == "stateflow-canvas-mastery.conflict-locks" })
    }

    @Test
    fun `autonomous mission authors spline paths and verifies bounds`() {
        val lesson = LearningCatalog.lesson("first-routine") ?: error("Missing autonomous mission")

        assertEquals(NavigationTarget.PATH_PLANNER, lesson.destination)
        assertFalse(lesson.requiresRobot)
        assertTrue("motion-profile-lab" in lesson.prerequisiteLessonIds)
        assertTrue(lesson.checkpoints.any { it.evidence != LearningCheckpointEvidence.SELF_REPORTED })
    }

    @Test
    fun `run review uses telemetry evidence`() {
        val analysisLesson = LearningCatalog.lesson("compare-run-evidence") ?: error("Missing analysis mission")

        assertEquals(NavigationTarget.GUIDED_RUN_ANALYSIS, analysisLesson.destination)
        assertTrue(analysisLesson.checkpoints.any { it.evidence != LearningCheckpointEvidence.SELF_REPORTED })
    }

    @Test
    fun `state flow lab covers controller redux and telemetry units`() {
        val lesson = LearningCatalog.lesson("state-flow-lab") ?: error("Missing state flow lab")

        assertEquals(LearningLab.STATE_FLOW, lesson.lab)
        assertFalse(lesson.requiresRobot)
        assertTrue("redux" in lesson.keywords)
        assertTrue("immutable" in lesson.keywords)
    }

    @Test
    fun `autonomous lab teaches validation before routine execution`() {
        val lesson = LearningCatalog.lesson("autonomous-safety-lab") ?: error("Missing autonomous safety lab")
        val track = LearningCatalog.path("autonomous-telemetry") ?: error("Missing autonomous track")

        assertEquals(LearningLab.AUTONOMOUS_SAFETY, lesson.lab)
        assertFalse(lesson.requiresRobot)
        assertTrue("bounds" in lesson.keywords)
        assertTrue("timeout" in lesson.keywords)
        assertTrue("interlock" in lesson.keywords)
        assertTrue(track.lessonIds.indexOf("first-routine") < track.lessonIds.indexOf("autonomous-safety-lab"))
    }
}
