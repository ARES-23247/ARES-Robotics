package com.areslib.tuning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TuningProfileDocumentTest {
    @Test
    fun `typed declarations and canonical profile round trip deterministically`() {
        val declarations = declarations()
        val profile = profile("profile.base", values = declarations.map { TuningAssignment(it.uid, it.defaultValue) })
        val encoded = TuningProfileDocumentCodec.encode(profile, declarations)

        assertEquals(
            profile.copy(values = profile.values.sortedBy { it.parameterUid }),
            TuningProfileDocumentCodec.decode(encoded, declarations),
        )
        assertEquals(encoded, TuningProfileDocumentCodec.encode(profile.copy(values = profile.values.reversed()), declarations))
        assertEquals(64, TuningProfileDocumentCodec.contentHash(profile, declarations).length)
    }

    @Test
    fun `wrong typed nonfinite out of range and duplicate values reject`() {
        val declaration = declarations().first()
        val bad = profile(
            "profile.bad",
            values = listOf(
                TuningAssignment(declaration.uid, TuningValue(doubleValue = Double.NaN)),
                TuningAssignment(declaration.uid, TuningValue(textValue = "wrong")),
            ),
        )
        val messages = validateTuningProfileDocument(bad, listOf(declaration)).map { it.message }
        assertTrue(messages.any { it.contains("assigned twice") })
        assertTrue(messages.any { it.contains("finite") || it.contains("Expected") })
    }

    @Test
    fun `one-level explicit composition resolves and missing deep or cyclic parents reject`() {
        val declaration = declarations().first()
        val base = profile("profile.base", listOf(TuningAssignment(declaration.uid, TuningValue(doubleValue = 1.0))))
        val competition = profile(
            "profile.competition", listOf(TuningAssignment(declaration.uid, TuningValue(doubleValue = 2.0))),
            baseProfileUid = base.uid,
        )
        assertEquals(2.0, resolveTuningProfiles(listOf(competition, base), listOf(declaration)).getValue(competition.uid).getValue(declaration.uid).doubleValue)
        assertThrows(IllegalArgumentException::class.java) {
            resolveTuningProfiles(listOf(competition.copy(baseProfileUid = "profile.missing"), base), listOf(declaration))
        }
        val deep = profile("profile.deep", emptyList(), baseProfileUid = competition.uid)
        assertThrows(IllegalArgumentException::class.java) {
            resolveTuningProfiles(listOf(base, competition, deep), listOf(declaration))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveTuningProfiles(
                listOf(base.copy(baseProfileUid = competition.uid), competition.copy(baseProfileUid = base.uid)),
                listOf(declaration),
            )
        }
    }

    @Test
    fun `promotion requires matching hashed evidence and local overlays cannot claim it`() {
        val promotion = TuningPromotionData(
            "local.test", "a".repeat(64), listOf("reports/sysid.json"), listOf("b".repeat(64)),
            "mentor", "Reviewed against simulator and calibration data",
        )
        val canonical = profile("profile.promoted", emptyList()).copy(promotion = promotion)
        assertTrue(validateTuningProfileDocument(canonical, declarations()).isEmpty())
        val local = canonical.copy(authority = TuningProfileAuthority.LOCAL_EXPERIMENTAL)
        assertTrue(validateTuningProfileDocument(local, declarations()).any { it.path == "promotion" })
    }

    companion object {
        fun declarations() = listOf(
            TuningParameterDeclaration(
                "drive.main.heading.kp", "drive.heading.kP", "drive.main", "Heading P", "Heading proportional gain",
                TuningParameterType.DOUBLE, "rad/s per rad", 0.0, 20.0, TuningValue(doubleValue = 1.8),
                applyPolicy = TuningApplyPolicy.LIVE_SAFE,
            ),
            TuningParameterDeclaration(
                "drive.main.encoder.ticks", "drive.encoder.ticksPerMeter", "drive.main", "Ticks per meter",
                "Measured encoder scale", TuningParameterType.INT, "ticks/m", 1.0, 1_000_000.0,
                TuningValue(intValue = 2000), applyPolicy = TuningApplyPolicy.CALIBRATION_ONLY,
            ),
        )

        fun profile(
            uid: String,
            values: List<TuningAssignment>,
            baseProfileUid: String? = null,
        ) = TuningProfileDocument(
            uid = uid, profileId = uid.substringAfter('.').replace('.', '-'), displayName = uid,
            description = "Test robot profile", projectUid = "project.test", drivebaseUid = "drive.main",
            authority = TuningProfileAuthority.CANONICAL_CHECKED_IN, baseProfileUid = baseProfileUid, values = values,
        )
    }
}
