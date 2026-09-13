package com.areslib.subsystem

import com.areslib.Store
import com.areslib.state.RobotState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GeneratedSubsystemStartupBoundaryAuditTest {
    private val closed = mutableListOf<String>()
    private fun subsystem(id: String, failure: Throwable? = null): Subsystem = object : Subsystem {
        override fun readSensors(store: Store, timestampMs: Long) = Unit
        override fun writeOutputs(state: RobotState, scale: Double) = Unit
        override fun close() { closed += id; failure?.let { throw it } }
    }

    @Test
    fun `required linkage failure closes earlier resources and retains fatal identity`() {
        val installed = mutableListOf(subsystem("first"), subsystem("second"))
        val fatal = LinkageError("vendor binding unavailable")
        val thrown = assertThrows<LinkageError> {
            GeneratedSubsystemRegistrySupport.install(installed, "broken", true) { throw fatal }
        }
        assertSame(fatal, thrown)
        assertEquals(listOf("second", "first"), closed)
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `optional linkage failure also aborts and rolls back the whole startup`() {
        val installed = mutableListOf(subsystem("first"))
        val fatal = LinkageError("optional vendor initialization failed")
        assertSame(fatal, assertThrows<LinkageError> {
            GeneratedSubsystemRegistrySupport.install(installed, "optional", false) { throw fatal }
        })
        assertEquals(listOf("first"), closed)
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `cleanup errors cannot strand remaining resources or replace the startup failure`() {
        val cleanup = AssertionError("cleanup failed")
        val installed = mutableListOf(subsystem("first"), subsystem("second", cleanup))
        val cause = IllegalArgumentException("configuration failed")
        val failure = assertThrows<IllegalStateException> {
            GeneratedSubsystemRegistrySupport.install(installed, "required", true) { throw cause }
        }
        assertSame(cause, failure.cause)
        assertEquals(listOf(cleanup), failure.suppressed.toList())
        assertEquals(listOf("second", "first"), closed)
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `a shared fatal throwable does not trigger self suppression or stop cleanup`() {
        val fatal = LinkageError("shared native binding")
        val other = AssertionError("another cleanup failure")
        val installed = mutableListOf(subsystem("first", other), subsystem("second", fatal))
        assertSame(fatal, assertThrows<LinkageError> {
            GeneratedSubsystemRegistrySupport.install(installed, "required", true) { throw fatal }
        })
        assertEquals(listOf(other), fatal.suppressed.toList())
        assertEquals(listOf("second", "first"), closed)
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `required null rolls back but optional null retains installed resources`() {
        val installed = mutableListOf(subsystem("first"))
        GeneratedSubsystemRegistrySupport.install(installed, "optional", false) { null }
        assertEquals(1, installed.size)
        assertTrue(closed.isEmpty())
        val failure = assertThrows<IllegalStateException> {
            GeneratedSubsystemRegistrySupport.install(installed, "required", true) { null }
        }
        assertTrue(failure.message!!.contains("required"))
        assertEquals("Required factory returned no subsystem", failure.cause!!.message)
        assertTrue(installed.isEmpty())
        assertEquals(listOf("first"), closed)
    }

    @Test
    fun `failed list insertion closes the newly created subsystem and earlier resources`() {
        val insertionFailure = IllegalStateException("list rejected insertion")
        val installed = object : ArrayList<Subsystem>() {
            var reject = false
            override fun add(element: Subsystem): Boolean {
                if (reject) throw insertionFailure
                return super.add(element)
            }
        }
        installed.add(subsystem("first"))
        installed.reject = true
        val failure = assertThrows<IllegalStateException> {
            GeneratedSubsystemRegistrySupport.install(installed, "optional", false) { subsystem("new") }
        }
        assertSame(insertionFailure, failure.cause)
        assertEquals(listOf("new", "first"), closed)
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `list insertion that throws after mutation closes each acquired instance once`() {
        val insertion = LinkageError("insertion failed after storing")
        val installed = object : ArrayList<Subsystem>() {
            var reject = false
            override fun add(element: Subsystem): Boolean {
                val added = super.add(element)
                if (reject) throw insertion
                return added
            }
        }
        installed.add(subsystem("first"))
        installed.reject = true
        assertSame(insertion, assertThrows<LinkageError> {
            GeneratedSubsystemRegistrySupport.install(installed, "new", true) { subsystem("new") }
        })
        assertEquals(listOf("new", "first"), closed)
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `false insertion result cannot silently lose ownership of the returned subsystem`() {
        val installed = object : ArrayList<Subsystem>() {
            override fun add(element: Subsystem): Boolean = false
        }
        assertThrows<IllegalStateException> {
            GeneratedSubsystemRegistrySupport.install(installed, "new", false) { subsystem("new") }
        }
        assertEquals(listOf("new"), closed)
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `duplicate cleanup errors and failed clear preserve the original failure`() {
        val cleanup = AssertionError("shared cleanup failure")
        val installed = object : ArrayList<Subsystem>() {
            override fun clear() { super.clear(); throw cleanup }
        }
        installed.add(subsystem("first", cleanup))
        installed.add(subsystem("second", cleanup))
        val cause = IllegalArgumentException("startup failed")
        val failure = assertThrows<IllegalStateException> {
            GeneratedSubsystemRegistrySupport.install(installed, "new", true) { throw cause }
        }
        assertSame(cause, failure.cause)
        assertEquals(listOf(cleanup), failure.suppressed.toList())
        assertEquals(listOf("second", "first"), closed)
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `successful buildList transfers resources in installation order without closing`() {
        val first = subsystem("first")
        val second = subsystem("second")
        val installed = buildList {
            GeneratedSubsystemRegistrySupport.install(this, "first", true) { first }
            GeneratedSubsystemRegistrySupport.install(this, "second", false) { second }
        }
        assertEquals(listOf(first, second), installed)
        assertTrue(closed.isEmpty())
    }
}
