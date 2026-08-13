package com.areslib.tuning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TypedTuningRuntimeTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `transport enforces every apply policy and rejects unknown invalid values`() {
        val runtime = runtime()
        val disarmed = TuningApplyContext(false, false)
        assertEquals(TuningUpdateResult.UNKNOWN_PARAMETER, runtime.apply("unknown", TuningValue(doubleValue = 1.0), disarmed))
        assertEquals(TuningUpdateResult.INVALID_VALUE, runtime.apply("p.live", TuningValue(doubleValue = Double.NaN), disarmed))
        assertEquals(TuningUpdateResult.SESSION_NOT_ARMED, runtime.apply("p.live", TuningValue(doubleValue = 2.0), disarmed))
        assertEquals(TuningUpdateResult.APPLIED, runtime.apply("p.live", TuningValue(doubleValue = 2.0), TuningApplyContext(true, false)))
        assertEquals(TuningUpdateResult.ROBOT_MUST_BE_DISABLED, runtime.apply("p.disabled", TuningValue(intValue = 2), TuningApplyContext(true, false)))
        assertEquals(TuningUpdateResult.APPLIED, runtime.apply("p.disabled", TuningValue(intValue = 2), TuningApplyContext(true, true)))
        assertEquals(TuningUpdateResult.RESTART_REQUIRED, runtime.apply("p.restart", TuningValue(booleanValue = false), TuningApplyContext(true, true)))
        assertEquals(TuningUpdateResult.REBUILD_REQUIRED, runtime.apply("p.rebuild", TuningValue(textValue = "b"), TuningApplyContext(true, true)))
        assertEquals(TuningUpdateResult.READ_ONLY_VENDOR, runtime.apply("p.vendor", TuningValue(doubleValue = 2.0), TuningApplyContext(true, true)))
        assertEquals(TuningUpdateResult.CALIBRATION_SESSION_REQUIRED, runtime.apply("p.calibration", TuningValue(doubleValue = 2.0), TuningApplyContext(true, true)))
        assertEquals(
            TuningUpdateResult.APPLIED,
            runtime.apply("p.calibration", TuningValue(doubleValue = 2.0), TuningApplyContext(true, true, setOf("p.calibration"))),
        )
        assertEquals(1.0, runtime.double("p.vendor"), 0.0)
    }

    @Test
    fun `runtime writes only an atomic robot-local experimental overlay`() {
        val runtime = runtime()
        runtime.apply("p.live", TuningValue(doubleValue = 3.0), TuningApplyContext(true, false))
        val overlay = runtime.localOverlay("local.test", "test-overlay", "Test overlay")
        val local = temp.resolve(".ares/local/tuning/test.arestuning")
        LocalTuningOverlayStore.writeAtomically(temp, local, overlay)

        assertTrue(Files.isRegularFile(local))
        assertTrue(Files.readString(local).contains("LOCAL_EXPERIMENTAL"))
        assertFalse(Files.exists(temp.resolve(".ares/tuning/test.arestuning")))
        assertThrows(IllegalArgumentException::class.java) {
            LocalTuningOverlayStore.writeAtomically(temp, temp.resolve(".ares/tuning/wrong.arestuning"), overlay)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalTuningOverlayStore.writeAtomically(
                temp, local, overlay.copy(authority = TuningProfileAuthority.CANONICAL_CHECKED_IN),
            )
        }
    }

    private fun runtime(): TypedTuningRuntime {
        val declarations = listOf(
            parameter("p.live", TuningParameterType.DOUBLE, TuningApplyPolicy.LIVE_SAFE, TuningValue(doubleValue = 1.0), 0.0, 5.0),
            parameter("p.disabled", TuningParameterType.INT, TuningApplyPolicy.DISABLED_ONLY, TuningValue(intValue = 1), 0.0, 5.0),
            parameter("p.restart", TuningParameterType.BOOLEAN, TuningApplyPolicy.RESTART_REQUIRED, TuningValue(booleanValue = true)),
            parameter("p.rebuild", TuningParameterType.ENUM, TuningApplyPolicy.REBUILD_REQUIRED, TuningValue(textValue = "a"), options = listOf("a", "b")),
            parameter("p.calibration", TuningParameterType.DOUBLE, TuningApplyPolicy.CALIBRATION_ONLY, TuningValue(doubleValue = 1.0), 0.0, 5.0),
            parameter("p.vendor", TuningParameterType.DOUBLE, TuningApplyPolicy.READ_ONLY_VENDOR, TuningValue(doubleValue = 1.0), 0.0, 5.0),
        )
        return TypedTuningRuntime(
            declarations, declarations.associate { it.uid to it.defaultValue },
            TuningMetadataSnapshot("project.test", "drive.main", "profile.base", declarations, listOf("profile.base")),
        )
    }

    private fun parameter(
        uid: String,
        type: TuningParameterType,
        policy: TuningApplyPolicy,
        default: TuningValue,
        minimum: Double? = null,
        maximum: Double? = null,
        options: List<String> = emptyList(),
    ) = TuningParameterDeclaration(
        uid, "test.${uid.substringAfter('.')}", "drive.main", uid, "Test parameter $uid", type,
        minimum = minimum, maximum = maximum, defaultValue = default, enumOptions = options, applyPolicy = policy,
    )
}
