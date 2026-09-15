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

    @Test
    fun `duplicate destinations are rejected before any starter is written`() {
        val content = "// ARES OWNERSHIP: GENERATED STARTER\nval version = 1\n"
        for (alias in listOf("State.kt", "nested/../State.kt", "nested\\..\\State.kt")) {
            val files = listOf(starter("State.kt", content), starter(alias, content.replace("1", "2")))
            assertThrows(IllegalArgumentException::class.java) { SubsystemStarterReconciler.apply(root, files) }
            assertTrue(Files.notExists(root.resolve("State.kt")))
        }
    }

    @Test
    fun `existing directories are protected before unrelated adds are written`() {
        Files.createDirectory(root.resolve("Z.kt"))
        val files = listOf(starter("A.kt", "new source"), starter("Z.kt", "replacement"))
        assertEquals(SubsystemStarterChangeKind.PROTECTED,
            SubsystemStarterReconciler.plan(root, files).changes.single { it.relativePath == "Z.kt" }.kind)
        assertThrows(IllegalArgumentException::class.java) { SubsystemStarterReconciler.apply(root, files) }
        assertTrue(Files.notExists(root.resolve("A.kt")))
        assertTrue(Files.isDirectory(root.resolve("Z.kt")))
    }

    @Test
    fun `starter paths must be relative descendants of their selected root`() {
        for (path in listOf("", ".", "..", "../escaped.kt", root.resolve("Absolute.kt").toString())) {
            assertThrows(IllegalArgumentException::class.java, {
                SubsystemStarterReconciler.plan(root, listOf(starter(path, "source")))
            }, path)
        }
    }

    @Test
    fun `changed current content invalidates an earlier replacement token`() {
        val first = starter("State.kt", "// ARES OWNERSHIP: GENERATED STARTER\nval version = 1\n")
        SubsystemStarterReconciler.apply(root, listOf(first))
        val second = first.copy(content = first.content.replace("1", "2"))
        val token = SubsystemStarterReconciler.plan(root, listOf(second)).confirmationToken
        val intervening = first.content.replace("1", "3")
        Files.writeString(root.resolve("State.kt"), intervening)
        assertThrows(IllegalArgumentException::class.java) {
            SubsystemStarterReconciler.apply(root, listOf(second), token)
        }
        assertEquals(intervening, Files.readString(root.resolve("State.kt")))
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
