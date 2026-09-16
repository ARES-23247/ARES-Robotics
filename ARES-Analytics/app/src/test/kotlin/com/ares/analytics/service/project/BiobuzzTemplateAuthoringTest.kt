package com.ares.analytics.service.project

import com.ares.analytics.BuildConfig
import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderViewModel
import com.areslib.project.AresProjectMetadataCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipInputStream

class BiobuzzTemplateAuthoringTest {
    @Test
    fun `bundled BioBuzz drivetrain opens in Studio with its own project and tuning identity`() = runBlocking {
        val temporary = Files.createTempDirectory("biobuzz-authoring-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val archiveName = "ARES-BIOBUZZ-Example-${BuildConfig.BIOBUZZ_EXAMPLE_VERSION}"
            ZipInputStream(requireNotNull(javaClass.getResourceAsStream("/project-templates/$archiveName.zip"))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val output = temporary.resolve(entry.name).canonicalFile
                    require(output.toPath().startsWith(temporary.canonicalFile.toPath()))
                    if (!entry.isDirectory) {
                        output.parentFile.mkdirs()
                        output.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            val project = temporary.resolve(archiveName)
            val metadata = AresProjectMetadataCodec.decode(project.resolve(".ares/project.json").readText())
            val model = DrivebaseBuilderViewModel(project.path, metadata.projectId, League.FTC, scope)
            val state = withTimeout(30_000) { model.state.first { !it.loading } }
            assertNull(state.error)
            assertEquals(metadata.projectId, state.draft.projectId)
            assertTrue(state.tuningProfileRepairIssues.toString(), state.tuningProfileRepairIssues.isEmpty())
            assertNotNull(state.saved)
        } finally {
            scope.cancel()
            temporary.deleteRecursively()
        }
    }
}
