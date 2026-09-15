package com.areslib.sequencer

import org.junit.jupiter.api.Test
import kotlin.test.*

class TaskResourceMaskTest {
    @Test fun `allocated resource ranges are distinct one-bit nonnegative masks`() {
        val builtins = listOf(TaskResources.DRIVE, TaskResources.INTAKE, TaskResources.FLYWHEEL,
            TaskResources.FEEDER, TaskResources.FLOOR, TaskResources.ELEVATOR, TaskResources.ARM,
            TaskResources.WRIST, TaskResources.CLIMBER, TaskResources.LIGHTING, TaskResources.SUPERSTRUCTURE_SHARED)
        val generated = (0..31).map(TaskResources::generatedSubsystem)
        val season = (0..14).map(TaskResources::season)
        val all = builtins + generated + season
        assertEquals(58, all.toSet().size)
        for (mask in all) { assertTrue(mask > 0); assertEquals(1, java.lang.Long.bitCount(mask)) }
        assertEquals(1L shl 16, generated.first()); assertEquals(1L shl 47, generated.last())
        assertEquals(1L shl 48, season.first()); assertEquals(1L shl 62, season.last())
        assertEquals(0L, TaskResources.NONE)
    }
    @Test fun `resource indices reject negative and out of range without shift wrapping`() {
        for (index in listOf(Int.MIN_VALUE, -1, 32, 64, Int.MAX_VALUE))
            assertFailsWith<IllegalArgumentException> { TaskResources.generatedSubsystem(index) }
        for (index in listOf(Int.MIN_VALUE, -1, 15, 64, Int.MAX_VALUE))
            assertFailsWith<IllegalArgumentException> { TaskResources.season(index) }
    }
    @Test fun `resource descriptions preserve every known and custom bit`() {
        assertEquals("none", TaskResources.describe(0))
        assertEquals("drive, intake", TaskResources.describe(TaskResources.DRIVE or TaskResources.INTAKE))
        assertEquals("custom(0x10000)", TaskResources.describe(TaskResources.generatedSubsystem(0)))
        assertEquals("custom(0x8000000000000000)", TaskResources.describe(Long.MIN_VALUE))
        assertEquals("drive, custom(0x8000000000000000)", TaskResources.describe(Long.MIN_VALUE or TaskResources.DRIVE))
    }
    @Test fun `all built-in resources have distinct diagnostic names`() {
        assertEquals("drive, intake, flywheel, feeder, floor, elevator, arm, wrist, climber, lighting, superstructure-shared",
            TaskResources.describe((1L shl 11) - 1))
    }
}
