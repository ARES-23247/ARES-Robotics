package com.ares.analytics.viewmodel.project

import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.areslib.project.*
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectIdentityValidationAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun document(league: AresLeague = AresLeague.FTC, dimension: Double = 0.45123456789) = AresProjectMetadataDocument(
        projectId = "test-project", identity = AresProjectIdentityDocument("99999", "2026", "robot", "Robot"),
        league = league,
        coordinateConvention = if (league == AresLeague.FRC) AresCoordinateConvention.BLUE_CORNER_ORIGIN_CCW else AresCoordinateConvention.CENTER_ORIGIN_CCW,
        robotLengthMeters = dimension, robotWidthMeters = dimension, fieldLengthMeters = dimension, fieldWidthMeters = dimension,
        authoringModel = AresProjectAuthoringModel.HYBRID,
        runtimeOptions = when (league) {
            AresLeague.FTC -> AresRuntimeOptionsDocument(ftc = AresFtcRuntimeOptionsDocument(AresFtcHubCommandTransport.ARES_PHOTON, true))
            AresLeague.FRC -> AresRuntimeOptionsDocument()
            AresLeague.XRP -> AresRuntimeOptionsDocument(xrp = AresXrpRuntimeOptionsDocument(AresXrpControllerModel.SPARKFUN_XRP_BETA_RP2040))
        },
    )
    private fun workspace(league: League = League.FTC, path: String = "fixture") = WorkspaceConfig(
        id = "workspace", teamId = "99999", seasonId = "2026", robotId = "robot", projectPath = path, league = league,
        robotLengthMeters = 0.45123456789, robotWidthMeters = 0.43210987654,
    )
    private fun roundTrip(current: AresProjectMetadataDocument): AresProjectMetadataDocument {
        val league = League.valueOf(current.league.name)
        val result = validateProjectIdentityDraft(league, projectIdentityDraft(workspace(league), current))
        return assertNotNull(result.document, "${result.fieldErrors} ${result.generalErrors}")
    }

    @Test fun `all supported geometry round trips preserve every Double exactly`() {
        for (league in AresLeague.entries) {
            for (value in listOf(Double.MIN_VALUE, java.lang.Double.MIN_NORMAL, 0.0000004, Math.nextUp(0.45), 0.45123456789, 1.23456789012345e100, Double.MAX_VALUE)) {
                val current = document(league, value)
                assertTrue(validateAresProjectMetadata(current).isEmpty())
                val next = roundTrip(current)
                assertEquals(current, next, "$league, value=$value")
                assertEquals(AresProjectMetadataCodec.contentHash(current), AresProjectMetadataCodec.contentHash(next))
            }
        }
    }

    @Test fun `brownout thresholds retain exact boundary neighbors and fractional values`() {
        for (threshold in listOf(3.0, Math.nextUp(3.0), 4.300000000000001, Math.nextDown(6.0), 6.0)) {
            val base = document(AresLeague.XRP, 0.45)
            val current = base.copy(runtimeOptions = AresRuntimeOptionsDocument(xrp = base.requireXrpRuntimeOptions().copy(brownoutThresholdVolts = threshold)))
            assertEquals(current, roundTrip(current), "threshold=$threshold")
        }
    }

    @Test fun `valid network names retain significant surrounding spaces`() {
        val base = document(AresLeague.XRP, 0.45)
        for (ssid in listOf(" Robotics Lab ", "A".repeat(31) + " ", "Lab\tNetwork")) {
            val current = base.copy(runtimeOptions = AresRuntimeOptionsDocument(xrp = base.requireXrpRuntimeOptions().copy(ssid = ssid)))
            assertTrue(validateAresProjectMetadata(current).isEmpty())
            assertEquals(ssid, roundTrip(current).requireXrpRuntimeOptions().ssid)
        }
    }

    @Test fun `valid display names are not silently normalized during unrelated edits`() {
        val current = document(dimension = 0.45).let { it.copy(identity = it.identity.copy(displayName = " Robot Team ")) }
        assertTrue(validateAresProjectMetadata(current).isEmpty())
        assertEquals(current.identity, roundTrip(current).identity)
    }

    @Test fun `new project workspace geometry retains exact configured measurements`() {
        for (league in League.entries) {
            val config = workspace(league)
            val draft = projectIdentityDraft(config, null)
            val validated = assertNotNull(validateProjectIdentityDraft(league, draft).document)
            assertEquals(config.robotLengthMeters, validated.robotLengthMeters)
            assertEquals(config.robotWidthMeters, validated.robotWidthMeters)
        }
    }

    @Test fun `opening and reviewing unchanged precise metadata produces no proposal or write`() = runTest {
        val root = temporary.newFolder()
        File(root, "TeamCode/src/main/java/Robot.kt").apply { parentFile.mkdirs(); writeText("class Robot") }
        val repository = ProjectMetadataRepository()
        val initial = document()
        repository.save(root.path, initial)
        val before = repository.file(root.path).readBytes()
        val model = ProjectIdentityViewModel(this, repository, StandardTestDispatcher(testScheduler))
        model.load(workspace(path = root.path)); advanceUntilIdle()
        model.review(); model.applyReviewed(); advanceUntilIdle()
        assertNull(model.state.value.proposal)
        assertContentEquals(before, repository.file(root.path).readBytes())
        assertFalse(File(root, ".ares/history/project").exists())
    }

    @Test fun `a name-only edit previews and saves no geometry or runtime changes`() = runTest {
        val root = temporary.newFolder()
        File(root, "TeamCode/src/main/java/Robot.kt").apply { parentFile.mkdirs(); writeText("class Robot") }
        val repository = ProjectMetadataRepository()
        val initial = document()
        val initialHash = repository.save(root.path, initial)
        val model = ProjectIdentityViewModel(this, repository, StandardTestDispatcher(testScheduler))
        model.load(workspace(path = root.path)); advanceUntilIdle()
        model.update(ProjectIdentityField.DISPLAY_NAME, "Renamed Robot"); model.review()
        val proposal = assertNotNull(model.state.value.proposal)
        assertEquals(listOf("Robot display name"), proposal.changes.map { it.label })
        model.applyReviewed(); advanceUntilIdle()
        assertEquals(initial.copy(identity = initial.identity.copy(displayName = "Renamed Robot")), repository.load(root.path).getOrThrow())
        assertEquals(initial, AresProjectMetadataCodec.decode(File(root, ".ares/history/project/$initialHash.json").readText()))
    }

    @Test fun `new project defaults select the matching coordinate frame and runtime section`() {
        val dimensions = mapOf(League.FTC to (3.6576 to 3.6576), League.FRC to (16.541 to 8.211), League.XRP to (2.54 to 1.4224))
        for (league in League.entries) {
            val validated = assertNotNull(validateProjectIdentityDraft(league, projectIdentityDraft(workspace(league), null)).document)
            assertEquals(AresLeague.valueOf(league.name), validated.league)
            assertEquals(if (league == League.FRC) AresCoordinateConvention.BLUE_CORNER_ORIGIN_CCW else AresCoordinateConvention.CENTER_ORIGIN_CCW, validated.coordinateConvention)
            assertEquals(dimensions.getValue(league).first, validated.fieldLengthMeters)
            assertEquals(dimensions.getValue(league).second, validated.fieldWidthMeters)
            assertEquals(AresProjectAuthoringModel.GUI_OWNED, validated.authoringModel)
            assertEquals(league == League.FTC, validated.runtimeOptions.ftc != null)
            assertEquals(league == League.XRP, validated.runtimeOptions.xrp != null)
            for (model in AresProjectAuthoringModel.entries) assertEquals(model, roundTrip(document(validated.league, 0.45).copy(authoringModel = model)).authoringModel)
        }
        val missing = projectIdentityDraft(workspace().copy(robotLengthMeters = null, robotWidthMeters = null), null)
        val errors = validateProjectIdentityDraft(League.FTC, missing).fieldErrors
        assertTrue(ProjectIdentityField.ROBOT_LENGTH in errors && ProjectIdentityField.ROBOT_WIDTH in errors)
    }

    @Test fun `geometry rejects nonfinite nonpositive underflow and footprint overflow with field diagnostics`() {
        val base = projectIdentityDraft(workspace(), document(dimension = 0.45))
        val setters: Map<ProjectIdentityField, (String) -> ProjectIdentityDraft> = mapOf(
            ProjectIdentityField.ROBOT_LENGTH to { base.copy(robotLengthMeters = it) },
            ProjectIdentityField.ROBOT_WIDTH to { base.copy(robotWidthMeters = it) },
            ProjectIdentityField.FIELD_LENGTH to { base.copy(fieldLengthMeters = it) },
            ProjectIdentityField.FIELD_WIDTH to { base.copy(fieldWidthMeters = it) },
        )
        for ((field, set) in setters) for (raw in listOf("", "word", "0", "-0.0", "-1", "NaN", "Infinity", "-Infinity", "1e309", "1e-400", "0,45")) {
            val result = validateProjectIdentityDraft(League.FTC, set(raw))
            assertNull(result.document, "$field=$raw")
            assertNotNull(result.fieldErrors[field], "$field=$raw")
        }
        assertNotNull(validateProjectIdentityDraft(League.FTC, base.copy(robotLengthMeters = " +4.5e-1 ")).document)
        for (draft in listOf(base.copy(robotLengthMeters = Math.nextUp(0.45).toString()), base.copy(robotWidthMeters = Math.nextUp(0.45).toString()))) {
            val result = validateProjectIdentityDraft(League.FTC, draft)
            assertNull(result.document)
            assertTrue(result.fieldErrors.isEmpty())
            assertTrue(result.generalErrors.any { it.contains("fit inside the field") })
        }
    }

    @Test fun `stable keys and names enforce exact contract lengths while normalizing only keys`() {
        val base = projectIdentityDraft(workspace(), document(dimension = 0.45))
        val setters: Map<ProjectIdentityField, (String) -> ProjectIdentityDraft> = mapOf(
            ProjectIdentityField.PROJECT_ID to { base.copy(projectId = it) },
            ProjectIdentityField.ROBOT_ID to { base.copy(robotId = it) },
            ProjectIdentityField.TEAM_ID to { base.copy(teamId = it) },
            ProjectIdentityField.SEASON_ID to { base.copy(seasonId = it) },
        )
        for ((field, set) in setters) {
            val isGroup = field == ProjectIdentityField.TEAM_ID || field == ProjectIdentityField.SEASON_ID
            val length = if (isGroup) 32 else 64
            assertNotNull(validateProjectIdentityDraft(League.FTC, set("A".repeat(length))).document)
            for (raw in listOf("", " ", ".bad", "bad/id", "équipe", "A".repeat(length + 1))) {
                assertNotNull(validateProjectIdentityDraft(League.FTC, set(raw)).fieldErrors[field], "$field=$raw")
            }
            assertEquals(isGroup, validateProjectIdentityDraft(League.FTC, set("123")).document != null)
        }
        val normalized = assertNotNull(validateProjectIdentityDraft(League.FTC, base.copy(
            projectId = " project ", teamId = " 123 ", seasonId = " 2026 ", robotId = " robot ", displayName = " Name ",
        )).document)
        assertEquals("project", normalized.projectId)
        assertEquals(AresProjectIdentityDocument("123", "2026", "robot", " Name "), normalized.identity)
        for (name in listOf("", " \t ", "A".repeat(81), "A".repeat(80) + " ")) {
            assertNotNull(validateProjectIdentityDraft(League.FTC, base.copy(displayName = name)).fieldErrors[ProjectIdentityField.DISPLAY_NAME])
        }
        assertNotNull(validateProjectIdentityDraft(League.FTC, base.copy(displayName = "A".repeat(80))).document)
    }

    @Test fun `XRP safety and link bounds reject reserved fractional and nonfinite inputs`() {
        val base = projectIdentityDraft(workspace(League.XRP), document(AresLeague.XRP, 0.45))
        val setters: Map<ProjectIdentityField, (String) -> ProjectIdentityDraft> = mapOf(
            ProjectIdentityField.XRP_LINK_PORT to { base.copy(xrpLinkPort = it) },
            ProjectIdentityField.XRP_DEADMAN_TIMEOUT to { base.copy(xrpDeadmanTimeoutMs = it) },
            ProjectIdentityField.XRP_BROWNOUT_THRESHOLD to { base.copy(xrpBrownoutThresholdVolts = it) },
            ProjectIdentityField.XRP_SSID to { base.copy(xrpSsid = it) },
        )
        val invalid = mapOf(
            ProjectIdentityField.XRP_LINK_PORT to listOf("1023", "65536", "5810", "5811.5", "2147483648", "NaN", ""),
            ProjectIdentityField.XRP_DEADMAN_TIMEOUT to listOf("99", "1001", "200.5", "Infinity", ""),
            ProjectIdentityField.XRP_BROWNOUT_THRESHOLD to listOf(Math.nextDown(3.0).toString(), Math.nextUp(6.0).toString(), "NaN", "Infinity", ""),
            ProjectIdentityField.XRP_SSID to listOf("", " \t ", "A".repeat(33)),
        )
        for ((field, values) in invalid) for (value in values) {
            val result = validateProjectIdentityDraft(League.XRP, setters.getValue(field)(value))
            assertNull(result.document)
            assertNotNull(result.fieldErrors[field], "$field=$value")
        }
        for (port in listOf("1024", "65535")) for (deadman in listOf("100", "1000")) for (volts in listOf("3", "6")) {
            assertNotNull(validateProjectIdentityDraft(League.XRP, base.copy(xrpLinkPort = port, xrpDeadmanTimeoutMs = deadman, xrpBrownoutThresholdVolts = volts)).document)
        }
        for (mode in listOf("AP", "STATION")) assertNotNull(validateProjectIdentityDraft(League.XRP, base.copy(xrpWifiMode = mode)).document)
        val badMode = validateProjectIdentityDraft(League.XRP, base.copy(xrpWifiMode = "AP "))
        assertNull(badMode.document)
        assertTrue(badMode.generalErrors.any { it.contains("wifiMode") })
        assertNotNull(validateProjectIdentityDraft(League.FTC, projectIdentityDraft(workspace(), document(dimension = 0.45)).copy(xrpSsid = "", xrpLinkPort = "bad")).document)
    }

    @Test fun `review diff includes every editable canonical and FTC runtime field exactly once`() {
        val base = document(dimension = 0.45).copy(fieldLengthMeters = 3.6576, fieldWidthMeters = 3.6576)
        val variants = listOf(
            "Stable project ID" to base.copy(projectId = "new-project"),
            "Team ID" to base.copy(identity = base.identity.copy(teamId = "88888")),
            "Season ID" to base.copy(identity = base.identity.copy(seasonId = "2027")),
            "Robot ID" to base.copy(identity = base.identity.copy(robotId = "new-robot")),
            "Robot display name" to base.copy(identity = base.identity.copy(displayName = "Robot 2")),
            "Robot length (m)" to base.copy(robotLengthMeters = Math.nextUp(0.45)),
            "Robot width (m)" to base.copy(robotWidthMeters = 0.46),
            "Field length (m)" to base.copy(fieldLengthMeters = 3.7),
            "Field width (m)" to base.copy(fieldWidthMeters = 3.7),
            "Authoring model" to base.copy(authoringModel = AresProjectAuthoringModel.GUI_OWNED),
            "FTC hub command transport" to base.copy(runtimeOptions = AresRuntimeOptionsDocument(ftc = base.requireFtcRuntimeOptions().copy(hubCommandTransport = AresFtcHubCommandTransport.STANDARD_SDK))),
            "Limelight camera proxy" to base.copy(runtimeOptions = AresRuntimeOptionsDocument(ftc = base.requireFtcRuntimeOptions().copy(limelightProxyEnabled = false))),
        )
        assertTrue(projectIdentityChanges(base, base).isEmpty())
        for ((label, proposed) in variants) {
            assertTrue(validateAresProjectMetadata(proposed).isEmpty())
            val change = projectIdentityChanges(base, proposed).single()
            assertEquals(label, change.label)
            assertNotEquals(change.before, change.after)
        }
        val creation = projectIdentityChanges(null, base)
        assertEquals(14, creation.size)
        assertEquals(creation.size, creation.map { it.label }.toSet().size)
        assertTrue(creation.all { it.before == "missing" })
    }

    @Test fun `XRP diffs include every runtime option and preserve their exact values`() {
        val base = document(AresLeague.XRP, 0.45)
        val original = base.requireXrpRuntimeOptions()
        val variants = listOf(
            "XRP controller model" to original.copy(controllerModel = AresXrpControllerModel.SPARKFUN_XRP_RP2350),
            "XRP Wi-Fi mode" to original.copy(wifiMode = "STATION"),
            "XRP Wi-Fi network" to original.copy(ssid = " New network "),
            "XRP Link port" to original.copy(port = 5821),
            "XRP deadman timeout (ms)" to original.copy(deadmanTimeoutMs = 250),
            "XRP brownout threshold (V)" to original.copy(brownoutThresholdVolts = Math.nextUp(4.3)),
        )
        for ((label, options) in variants) {
            val proposed = base.copy(runtimeOptions = AresRuntimeOptionsDocument(xrp = options))
            assertEquals(proposed, roundTrip(proposed))
            assertEquals(label, projectIdentityChanges(base, proposed).single().label)
        }
        val frc = document(AresLeague.FRC, 0.45)
        assertTrue(projectIdentityChanges(frc, frc).isEmpty())
        assertEquals(12, projectIdentityChanges(null, frc).size)
        assertEquals(setOf("League", "Coordinate convention"), projectIdentityChanges(document(dimension = 0.45), frc).map { it.label }.toSet())
    }

    @Test fun `suggested IDs remain deterministic ASCII keys across hostile workspace labels`() {
        for (value in listOf("", "123", "...", "équipe/机器人", "A".repeat(200), " -- \t / robot ")) {
            val config = workspace().copy(teamId = value, robotId = value, seasonId = value)
            val id = projectIdentityDraft(config, null).projectId
            assertEquals(id, projectIdentityDraft(config, null).projectId)
            assertTrue(id.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")), id)
            assertTrue(id.startsWith("team"))
        }
        assertEquals("test-project", projectIdentityDraft(workspace().copy(teamId = "changed"), document(dimension = 0.45)).projectId)
    }
}
