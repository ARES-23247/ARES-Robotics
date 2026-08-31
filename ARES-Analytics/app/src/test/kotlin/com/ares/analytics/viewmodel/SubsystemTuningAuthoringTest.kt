package com.ares.analytics.viewmodel

import com.ares.analytics.service.tuning.TuningProfileRepository
import com.areslib.codegen.DrivetrainKotlinGenerator
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemFeedforwardDocument
import com.areslib.subsystem.SubsystemFeedforwardKind
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import com.areslib.subsystem.SubsystemSchema
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningAssignment
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningValue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubsystemTuningAuthoringTest {
    @Test
    fun `all parameter types and apply policies remain explicitly typed and valid`() {
        val base = subsystem()
        val seed = SubsystemTuningAuthoring.newParameter(base)
        val typed = TuningParameterType.entries.mapIndexed { index, type ->
            SubsystemTuningAuthoring.changeType(seed.copy(
                uid = "${base.uid}.parameter.${index + 1}",
                key = "subsystem.lift.value${index + 1}",
                applyPolicy = TuningApplyPolicy.entries[index],
            ), type)
        } + seed.copy(
            uid = "${base.uid}.parameter.readonly",
            key = "subsystem.lift.readOnly",
            applyPolicy = TuningApplyPolicy.READ_ONLY_VENDOR,
        )
        val document = base.copy(tuningParameters = typed)

        assertEquals(TuningParameterType.entries.toSet(), typed.take(TuningParameterType.entries.size).mapTo(hashSetOf()) { it.type })
        assertEquals(TuningApplyPolicy.entries.toSet(), typed.mapTo(hashSetOf()) { it.applyPolicy })
        assertTrue(SubsystemSchema.validate(document).none { it.path.startsWith("tuningParameters") })
    }

    @Test
    fun `duplicates bounds and enum options produce actionable validation`() {
        val base = subsystem()
        val first = SubsystemTuningAuthoring.newParameter(base)
        val duplicate = first.copy(displayName = "Duplicate")
        val invalidBounds = first.copy(
            uid = "${base.uid}.bounds",
            key = "subsystem.lift.bounds",
            minimum = 5.0,
            maximum = 1.0,
        )
        val invalidEnum = SubsystemTuningAuthoring.changeType(first, TuningParameterType.ENUM).copy(
            uid = "${base.uid}.mode",
            key = "subsystem.lift.mode",
            enumOptions = emptyList(),
            defaultValue = TuningValue(textValue = "missing"),
        )
        val issues = SubsystemSchema.validate(base.copy(tuningParameters = listOf(first, duplicate, invalidBounds, invalidEnum)))

        assertTrue(issues.any { it.message.contains("UID") && it.message.contains("duplicated") })
        assertTrue(issues.any { it.message.contains("key") && it.message.contains("duplicated") })
        assertTrue(issues.any { it.message.contains("Minimum cannot exceed maximum") })
        assertTrue(issues.any { it.message.contains("Enum parameters require options") })
    }

    @Test
    fun `PID and feedforward presets are truthful optional and idempotent`() {
        val base = subsystem()
        val loop = base.controlLoops.first().copy(
            strategy = com.areslib.subsystem.SubsystemControlStrategy.PROFILED_POSITION_PID,
            kP = 2.0,
            kI = 0.1,
            kD = 0.03,
            feedforward = SubsystemFeedforwardDocument(
                kind = SubsystemFeedforwardKind.ELEVATOR,
                kS = 0.2,
                kV = 1.1,
                kA = 0.05,
                kG = 0.4,
            ),
        )
        // Exercise the optional preset path independently from the recommended parameters that
        // capability templates now declare by default. Existing declarations are intentionally
        // never replaced: they may already contain reviewed team values.
        val configured = base.copy(controlLoops = listOf(loop), tuningParameters = emptyList())
        assertEquals(
            setOf(
                SubsystemTuningPreset.PID_GAINS,
                SubsystemTuningPreset.FEEDFORWARD_GAINS,
                SubsystemTuningPreset.MOTION_PROFILE_LIMITS,
            ),
            SubsystemTuningAuthoring.availablePresets(loop).toSet(),
        )

        val withPid = SubsystemTuningAuthoring.applyPreset(configured, loop.uid, SubsystemTuningPreset.PID_GAINS)
        val withBoth = SubsystemTuningAuthoring.applyPreset(withPid, loop.uid, SubsystemTuningPreset.FEEDFORWARD_GAINS)
        val repeated = SubsystemTuningAuthoring.applyPreset(withBoth, loop.uid, SubsystemTuningPreset.FEEDFORWARD_GAINS)

        assertEquals(7, withBoth.tuningParameters.size)
        assertEquals(withBoth, repeated)
        assertTrue(withBoth.tuningParameters.all { it.componentUid == loop.uid })
        assertTrue(withBoth.tuningParameters.all { it.applyPolicy == TuningApplyPolicy.DISABLED_ONLY })
        assertEquals(loop.kP, withBoth.tuningParameters.single { it.key.endsWith(".kp") }.defaultValue.doubleValue)
        assertEquals("V", withBoth.tuningParameters.single { it.key.endsWith(".kg") }.unit)
        assertTrue(withBoth.tuningParameters.all { it.minimum != null && it.maximum != null })

        val withMotion = SubsystemTuningAuthoring.applyPreset(
            withBoth,
            loop.uid,
            SubsystemTuningPreset.MOTION_PROFILE_LIMITS,
        )
        assertEquals(9, withMotion.tuningParameters.size)
        assertEquals(
            loop.motionProfile.maximumVelocity,
            withMotion.tuningParameters.single { it.key.endsWith(".maxvelocity") }.defaultValue.doubleValue,
        )
        assertEquals(
            loop.motionProfile.maximumAcceleration,
            withMotion.tuningParameters.single { it.key.endsWith(".maxacceleration") }.defaultValue.doubleValue,
        )

        val templateDefaults = base.tuningParameters
        val preserved = SubsystemTuningAuthoring.applyPreset(base, base.controlLoops.single().uid, SubsystemTuningPreset.PID_GAINS)
        assertEquals(templateDefaults, preserved.tuningParameters)
    }

    @Test
    fun `stable identity survives reorder codec round trip and metadata discovery`() {
        val base = subsystem()
        val first = SubsystemTuningAuthoring.newParameter(base)
        val second = first.copy(
            uid = "${base.uid}.parameter.2",
            key = "subsystem.lift.parameter2",
            displayName = "Second",
        )
        val ordered = base.copy(tuningParameters = listOf(first, second))
        val moved = ordered.copy(
            tuningParameters = SubsystemTuningAuthoring.moveByUid(ordered.tuningParameters, second.uid, -1),
        )
        assertEquals(listOf(second.uid, first.uid), moved.tuningParameters.map { it.uid })

        val encoded = SubsystemDocumentCodec.encode(moved)
        val decoded = SubsystemDocumentCodec.decode(encoded)
        assertEquals(moved, decoded)
        assertEquals(encoded, SubsystemDocumentCodec.encode(decoded))

        val project = Files.createTempDirectory("ares-subsystem-tuning-metadata").toFile()
        project.resolve(".ares/subsystems").mkdirs()
        project.resolve(".ares/subsystems/lift.aressubsystem").writeText(encoded)
        val catalog = TuningProfileRepository().load(project.path).getOrThrow().catalog
        assertEquals(listOf(second.uid, first.uid), catalog.map { it.uid })

        val profile = TuningProfileDocument(
            uid = "profile.test",
            profileId = "test",
            displayName = "Test profile",
            description = "Canonical metadata inclusion test",
            projectId = "project.test",
            drivebaseUid = "drive.test",
            authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
            values = catalog.map { TuningAssignment(it.uid, it.defaultValue) },
        )
        val generated = DrivetrainKotlinGenerator.generateProjectTuning(
            projectId = profile.projectId,
            canonicalProfileUid = profile.uid,
            drivebaseUid = requireNotNull(profile.drivebaseUid),
            declarations = catalog,
            profiles = listOf(profile),
            packageName = "example.generated",
        ).content
        catalog.forEach { declaration ->
            assertTrue(generated.contains(declaration.uid))
            assertTrue(generated.contains(declaration.componentUid))
            assertTrue(generated.contains("TuningApplyPolicy.${declaration.applyPolicy}"))
        }
    }

    @Test
    fun `moveByUid with invalid UID or out of bounds delta preserves parameter order`() {
        val base = subsystem()
        val first = SubsystemTuningAuthoring.newParameter(base)
        val second = first.copy(
            uid = "${base.uid}.parameter.2",
            key = "subsystem.lift.parameter2",
            displayName = "Second",
        )
        val third = first.copy(
            uid = "${base.uid}.parameter.3",
            key = "subsystem.lift.parameter3",
            displayName = "Third",
        )
        val original = listOf(first, second, third)

        assertEquals(original, SubsystemTuningAuthoring.moveByUid(original, "missing.uid", 1))
        assertEquals(original, SubsystemTuningAuthoring.moveByUid(original, first.uid, 0))
        assertEquals(original, SubsystemTuningAuthoring.moveByUid(original, second.uid, 0))
        assertEquals(original, SubsystemTuningAuthoring.moveByUid(original, first.uid, -1))
        assertEquals(original, SubsystemTuningAuthoring.moveByUid(original, first.uid, -5))
        assertEquals(original, SubsystemTuningAuthoring.moveByUid(original, third.uid, 1))
        assertEquals(original, SubsystemTuningAuthoring.moveByUid(original, third.uid, 5))
    }

    private fun subsystem() = SubsystemTemplates.create(
        SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
        "lift",
        "Lift",
        SubsystemPlatform.FTC,
    )
}
