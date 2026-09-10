package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.*
import com.areslib.state.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlin.test.*

class AprilTagImportContractTest {
    private val jobs = mutableListOf<Job>()
    @AfterTest fun cleanup() { jobs.forEach(Job::cancel) }

    @Test fun `WPILib origin rotates into each centered blue wall with rectangular extents`() {
        // A 10x4 WPILib field starts at the blue driver's right corner, facing inward.
        val references = listOf(
            Triple(DriverStationSide.WEST, Pair(-5.0, -2.0), 0.0),
            Triple(DriverStationSide.SOUTH, Pair(2.0, -5.0), 90.0),
            Triple(DriverStationSide.EAST, Pair(5.0, 2.0), -180.0),
            Triple(DriverStationSide.NORTH, Pair(-2.0, 5.0), -90.0),
        )
        for (type in listOf(FieldType.FTC, FieldType.XRP)) for ((wall, xy, yaw) in references) {
            val target = RobotFieldConfig(fieldType = type, blueDriverStation = wall)
            val preview = FieldAprilTagTransfer.decode(wpilib(), "map.json", target, emptyList(), league(type))
            val tag = preview.tags.single()
            assertEquals(xy.first, tag.x, 1e-12, "$type $wall X")
            assertEquals(xy.second, tag.y, 1e-12, "$type $wall Y")
            assertEquals(yaw, tag.yawDegrees, 1e-9, "$type $wall yaw")
            val swaps = wall == DriverStationSide.NORTH || wall == DriverStationSide.SOUTH
            assertEquals(if (swaps) 4.0 else 10.0, preview.fieldLengthMeters)
            assertEquals(if (swaps) 10.0 else 4.0, preview.fieldWidthMeters)
        }
    }

    @Test fun `WPILib export converts centered pose orientation and rectangular dimensions`() {
        val source = RobotFieldConfig(fieldType = FieldType.FTC, widthMeters = 4.0, heightMeters = 10.0,
            blueDriverStation = DriverStationSide.NORTH,
            apriltags = listOf(RobotFieldAprilTag(id = 1, x = -2.0, y = 5.0, z = 0.4,
                roll = 20.0, pitch = 30.0, yaw = -90.0)))
        val json = FieldAprilTagTransfer.encode(source, AprilTagExportFormat.WPILIB_JSON)
        val raw = AprilTagMapCodec.decodeWpilib(json)
        val tag = raw.tags.single()
        assertEquals(0.0, tag.x, 1e-12)
        assertEquals(0.0, tag.y, 1e-12)
        assertEquals(0.4, tag.z)
        assertEquals(20.0, tag.roll, 1e-9)
        assertEquals(30.0, tag.pitch, 1e-9)
        assertEquals(0.0, tag.yaw, 1e-9)
        assertEquals(10.0, raw.fieldLengthMeters)
        assertEquals(4.0, raw.fieldWidthMeters)
    }

    @Test fun `fmap replacement uses source dimensions for converted tag and new field`() {
        val vm = editor(League.FRC)
        preview(vm, fmap("\"fieldlength\":20,\"fieldwidth\":10,"), League.FRC)
        assertEquals(10.0, vm.state.value.aprilTagImportPreview!!.tags.single().x)
        vm.onIntent(FieldEditorIntent.ApplyAprilTagImport(true))
        assertEquals(10.0, vm.state.value.aprilTags.single().x)
        assertEquals(5.0, vm.state.value.aprilTags.single().y)
        assertEquals(20.0, vm.state.value.document!!.resolvedWidthMeters)
        assertEquals(10.0, vm.state.value.document!!.resolvedHeightMeters)
    }

    @Test fun `partial fmap dimensions update only known axis on replacement`() {
        val vm = editor(League.FRC)
        val oldHeight = vm.state.value.document!!.resolvedHeightMeters
        preview(vm, fmap("\"fieldlength\":20,"), League.FRC)
        vm.onIntent(FieldEditorIntent.ApplyAprilTagImport(true))
        assertEquals(10.0, vm.state.value.aprilTags.single().x)
        assertEquals(oldHeight / 2, vm.state.value.aprilTags.single().y)
        assertEquals(20.0, vm.state.value.document!!.resolvedWidthMeters)
        assertEquals(oldHeight, vm.state.value.document!!.resolvedHeightMeters)
    }

    @Test fun `merge preserves current dimensions and IDs with source-frame conversion`() {
        val vm = editor(League.FRC)
        val oldWidth = vm.state.value.document!!.resolvedWidthMeters
        val existing = AprilTagPlacement(id = "current", tagId = 8, x = 1.0, y = 2.0)
        vm.onIntent(FieldEditorIntent.AddAprilTag(existing))
        preview(vm, fmap("\"fieldlength\":20,\"fieldwidth\":10,"), League.FRC)
        vm.onIntent(FieldEditorIntent.ApplyAprilTagImport(false))
        assertEquals(existing, vm.state.value.aprilTags.first())
        assertEquals(10.0, vm.state.value.aprilTags.last().x)
        assertEquals(oldWidth, vm.state.value.document!!.resolvedWidthMeters)
    }

    @Test fun `ARES import transforms source frame instead of adopting target interpretation`() {
        val source = RobotFieldConfig(fieldType = FieldType.FRC, widthMeters = 10.0, heightMeters = 4.0,
            apriltags = listOf(RobotFieldAprilTag(id = 1, x = 0.0, y = 0.0, yaw = 0.0)))
        val target = RobotFieldConfig(fieldType = FieldType.FTC, blueDriverStation = DriverStationSide.NORTH)
        val result = FieldAprilTagTransfer.decode(RobotFieldDocument.encode(source), "field.json", target, emptyList(), League.FTC)
        assertEquals(-2.0, result.tags.single().x)
        assertEquals(5.0, result.tags.single().y)
        assertEquals(-90.0, result.tags.single().yawDegrees)
        assertEquals(4.0, result.fieldLengthMeters)
        assertEquals(10.0, result.fieldWidthMeters)
    }

    @Test fun `document edits invalidate a preview whose conversion and conflicts are stale`() {
        val vm = editor(League.FRC)
        preview(vm, fmap(""), League.FRC)
        val changed = vm.state.value.fieldImageConfig.copy(widthMeters = 20.0)
        vm.onIntent(FieldEditorIntent.UpdateFieldImageConfig(changed, null, League.FRC))
        assertNull(vm.state.value.aprilTagImportPreview)
        vm.onIntent(FieldEditorIntent.ApplyAprilTagImport(true))
        assertTrue(vm.state.value.aprilTags.isEmpty())
    }

    @Test fun `undo invalidates preview instead of applying against a different revision`() {
        val vm = editor(League.FRC)
        vm.onIntent(FieldEditorIntent.AddAprilTag(AprilTagPlacement(id = "current", tagId = 8, x = 1.0, y = 2.0)))
        preview(vm, fmap(""), League.FRC)
        vm.onIntent(FieldEditorIntent.Undo)
        assertNull(vm.state.value.aprilTagImportPreview)
    }

    @Test fun `applying an unchanged import consumes the preview without making an edit`() {
        val vm = editor(League.FRC)
        preview(vm, fmap(""), League.FRC)
        vm.onIntent(FieldEditorIntent.ApplyAprilTagImport(true))
        val revision = vm.state.value.document!!.revision
        preview(vm, fmap(""), League.FRC)
        vm.onIntent(FieldEditorIntent.ApplyAprilTagImport(true))
        assertNull(vm.state.value.aprilTagImportPreview)
        assertEquals(revision, vm.state.value.document!!.revision)
    }

    @Test fun `malformed WPILib errors are not replaced by a different format error`() {
        val root = JsonParser.parseString(wpilib()).asJsonObject
        root.getAsJsonArray("tags")[0].asJsonObject.addProperty("ID", 1.5)
        val error = assertFailsWith<IllegalArgumentException> {
            FieldAprilTagTransfer.decode(root.toString(), "map.json", RobotFieldConfig(), emptyList(), League.FTC)
        }
        assertTrue(error.message.orEmpty().contains("exact 32-bit integer"), error.message)
    }

    @Test fun `preview cannot change the loaded project league`() {
        val vm = editor(League.FTC)
        preview(vm, fmap(""), League.FRC)
        assertNull(vm.state.value.aprilTagImportPreview)
        vm.onIntent(FieldEditorIntent.AddAprilTag(AprilTagPlacement(id = "current", tagId = 8, x = 0.0, y = 0.0)))
        assertEquals(FieldType.FTC, vm.state.value.document!!.fieldType)
    }

    private fun editor(league: League): FieldEditorViewModel {
        val job = SupervisorJob().also(jobs::add)
        return FieldEditorViewModel(CoroutineScope(job + Dispatchers.Unconfined)).also {
            it.onIntent(FieldEditorIntent.LoadConfig(null, league))
        }
    }
    private fun preview(vm: FieldEditorViewModel, json: String, league: League) =
        vm.onIntent(FieldEditorIntent.PreviewAprilTagMap(json, "map.fmap", null, league))
    private fun league(type: FieldType) = if (type == FieldType.FTC) League.FTC else League.XRP
    private fun fmap(dimensions: String) = """{$dimensions"fiducials":[{"id":1,"transform":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]}]}"""
    private fun wpilib() = """{"field":{"length":10,"width":4},"tags":[{"ID":1,"pose":{"translation":{"x":0,"y":0,"z":0},"rotation":{"quaternion":{"W":1,"X":0,"Y":0,"Z":0}}}}]}"""
}
