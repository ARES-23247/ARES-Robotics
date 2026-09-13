package com.areslib.subsystem

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SubsystemIdentityBoundaryAuditTest {
    @Test
    fun `shared duplicate detection preserves repeat order and emits each duplicate once`() {
        assertEquals(listOf("b", "a"), duplicateSubsystemIds(listOf("a", "b", "b", "a", "b", "c", "a")).toList())
        assertTrue(duplicateSubsystemIds(emptyList()).isEmpty())
        assertTrue(duplicateSubsystemIds(listOf("a", "b")).isEmpty())
    }

    @Test
    fun `project paths reject drive qualified paths and nonportable path characters`() {
        for (path in listOf("C:/outside/Arm.kt", "C:relative/Arm.kt", "src/Arm.kt:stream", "docs/bad\u0000name.md", "docs/line\nname.md")) {
            assertFalse(path.isSafeSubsystemProjectRelativePath(), path)
        }
        assertTrue("src/main/kotlin/example/Arm.kt".isSafeSubsystemProjectRelativeKotlinPath())
        assertTrue("docs/Arm notes.md".isSafeSubsystemProjectRelativePath())
    }

    @Test
    fun `different stable UIDs cannot share a generated document ID`() {
        val first = SubsystemBoundaryFixtures.motor()
        val other = first.copy(uid = "second-stable-uid")
        assertTrue(SubsystemSchema.validate(first).isEmpty())
        assertTrue(SubsystemSchema.validate(other).isEmpty())
        assertTrue(SubsystemSchema.validateAll(listOf(first, other)).any { it.path == "subsystems" && it.message.contains("arm") })
        assertTrue(SubsystemSchema.validateAll(listOf(first, other.copy(documentId = "elbow"))).isEmpty())
    }

    @Test
    fun `generated interlocks reject unused fallback overrides`() {
        val base = SubsystemBoundaryFixtures.motor()
        for (fallback in listOf(0.0, 0.5, Double.NaN)) {
            val document = base.copy(interlocks = listOf(SubsystemInterlockDocument("clearance", "other", "angle", safeFallbackValue = fallback)))
            assertTrue(SubsystemSchema.validate(document).any { it.path == "interlocks[0].safeFallbackValue" })
        }
    }

    @Test
    fun `cross subsystem interlocks reject ambiguous target UID and mismatched field types`() {
        val base = SubsystemBoundaryFixtures.motor()
        val owner = base.copy(documentId = "owner", uid = "owner", interlocks = listOf(SubsystemInterlockDocument("clearance", base.uid, "angle")))
        assertTrue(SubsystemSchema.validateAll(listOf(owner, base)).isEmpty())
        assertTrue(SubsystemSchema.validateAll(listOf(owner)).any { it.path.endsWith("targetSubsystemUid") })
        assertTrue(SubsystemSchema.validateAll(listOf(owner, base, base.copy(documentId = "duplicate"))).any { it.path.endsWith("targetSubsystemUid") })
        val boolean = base.copy(stateFields = base.stateFields.map { if (it.fieldId == "angle") it.copy(type = SubsystemValueType.BOOLEAN, defaultBoolean = false, defaultNumber = null, unit = null) else it })
        assertTrue(SubsystemSchema.validateAll(listOf(owner, boolean)).any { it.path.endsWith("comparison") })
    }
}
