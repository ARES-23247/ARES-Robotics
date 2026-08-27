package com.areslib.codegen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SubsystemStarterReconcilerTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `missing starter is created but replacement requires exact reviewed token`() {
        val first = starter("sample/State.kt", "// ARES OWNERSHIP: GENERATED STARTER\nval version = 1\n")
        val addPlan = SubsystemStarterReconciler.plan(root, listOf(first))
        assertEquals(SubsystemStarterChangeKind.ADD, addPlan.changes.single().kind)
        SubsystemStarterReconciler.apply(root, listOf(first))

        val second = first.copy(content = "// ARES OWNERSHIP: GENERATED STARTER\nval version = 2\n")
        val replacePlan = SubsystemStarterReconciler.plan(root, listOf(second))
        assertEquals(SubsystemStarterChangeKind.REPLACE, replacePlan.changes.single().kind)
        assertTrue(replacePlan.changes.single().diff.contains("-val version = 1"))
        assertTrue(replacePlan.changes.single().diff.contains("+val version = 2"))
        assertNotNull(replacePlan.confirmationToken)
        assertThrows(IllegalArgumentException::class.java) {
            SubsystemStarterReconciler.apply(root, listOf(second))
        }
        SubsystemStarterReconciler.apply(root, listOf(second), replacePlan.confirmationToken)
        assertTrue(Files.readString(root.resolve("sample/State.kt")).contains("version = 2"))
    }

    @Test
    fun `user owned and unknown source can never be overwritten`() {
        val proposed = starter("sample/State.kt", "// ARES OWNERSHIP: GENERATED STARTER\nval generated = true\n")
        val path = root.resolve("sample/State.kt")
        Files.createDirectories(path.parent)
        Files.writeString(path, "// ARES OWNERSHIP: USER-OWNED\nval custom = true\n")
        var plan = SubsystemStarterReconciler.plan(root, listOf(proposed))
        assertEquals(SubsystemStarterChangeKind.PROTECTED, plan.changes.single().kind)
        assertThrows(IllegalArgumentException::class.java) {
            SubsystemStarterReconciler.apply(root, listOf(proposed), plan.confirmationToken)
        }
        assertTrue(Files.readString(path).contains("custom = true"))

        Files.writeString(path, "package hand.authored\n")
        plan = SubsystemStarterReconciler.plan(root, listOf(proposed))
        assertEquals(SubsystemStarterChangeKind.PROTECTED, plan.changes.single().kind)
        assertThrows(IllegalArgumentException::class.java) {
            SubsystemStarterReconciler.apply(root, listOf(proposed), plan.confirmationToken)
        }
    }

    private fun starter(path: String, content: String) = GeneratedSubsystemFile(
        relativePath = path,
        content = content,
        artifact = SubsystemArtifact.STATE,
        group = SubsystemArtifactGroup.DOMAIN,
        ownership = SubsystemArtifactOwnership.GENERATED_STARTER,
        description = "Immutable state",
    )
}
