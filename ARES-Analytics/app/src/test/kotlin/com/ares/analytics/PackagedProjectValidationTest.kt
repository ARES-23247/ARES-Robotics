package com.ares.analytics

import com.ares.analytics.service.project.AresProjectDocuments
import com.areslib.project.schema.AresControllerTarget
import com.areslib.project.schema.AresSimulatorTarget
import com.areslib.project.schema.ProjectActionKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackagedProjectValidationTest {
    @Test
    fun `complete fixture exercises every packaged project codec`() {
        val fixture = checkNotNull(javaClass.classLoader.getResource("packaged-runtime-project"))
        val projectPath = File(fixture.toURI()).path
        val result = validatePackagedProject(projectPath)
        val project = AresProjectDocuments().load(projectPath).query

        assertTrue(result.isValid, result.errors.joinToString())
        assertEquals(1, result.routineCount)
        assertEquals(1, result.subsystemCount)
        assertTrue(project.isValid, project.issues.joinToString())
        assertEquals("packaged-runtime-smoke", project.projectId?.value)
        assertEquals(AresControllerTarget.FTC_CONTROL_HUB, project.target?.controller)
        assertEquals(AresSimulatorTarget.FTC, project.target?.simulator)
        assertTrue(ProjectActionKey("smoke.run") in project.actionKeys)
    }

    @Test
    fun `packaged runtime probe exercises jgit pack transfer`() {
        validatePackagedGitRuntime()
    }

    @Test
    fun `validation command is isolated from ordinary desktop startup`() {
        assertEquals(null, runPackagedProjectValidationCommand(emptyArray()))
        assertEquals(64, runPackagedProjectValidationCommand(arrayOf(PACKAGED_PROJECT_VALIDATION_COMMAND)))
    }
}
