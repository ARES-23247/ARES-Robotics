package org.aresfirst.marvin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrcStartupResourcesTest {
    private class Resource(val name: String, val closed: MutableList<String>, val failure: Throwable? = null) : AutoCloseable {
        override fun close() { closed.add(name); failure?.let { throw it } }
        override fun equals(other: Any?) = other is Resource
        override fun hashCode() = 0
    }

    @Test fun `later construction failure closes acquired resources in reverse order`() {
        val closed = mutableListOf<String>()
        val original = IllegalStateException("later constructor failed")
        val actual = assertThrows(IllegalStateException::class.java) {
            withFrcStartupResources { resources ->
                resources.own(Resource("first", closed))
                resources.own(Resource("second", closed))
                throw original
            }
        }
        assertSame(original, actual)
        assertEquals(listOf("second", "first"), closed)
    }

    @Test fun `adapter ownership transfer closes its children exactly once on rollback`() {
        val closed = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            withFrcStartupResources { resources ->
                val child = resources.own(Resource("motor", closed))
                val adapter = AutoCloseable { closed.add("adapter"); child.close() }
                resources.replace(adapter, child)
                error("next adapter failed")
            }
        }
        assertEquals(listOf("adapter", "motor"), closed)
    }

    @Test fun `throwing adapter constructor leaves its raw resource owned for rollback`() {
        val closed = mutableListOf<String>()
        fun failingAdapter(): AutoCloseable = error("adapter construction failed")
        assertThrows(IllegalStateException::class.java) {
            withFrcStartupResources { resources ->
                val child = resources.own(Resource("motor", closed))
                resources.replace(failingAdapter(), child)
            }
        }
        assertEquals(listOf("motor"), closed)
    }

    @Test fun `cleanup failures preserve construction error and do not skip other resources`() {
        val closed = mutableListOf<String>()
        val original = IllegalStateException("construction")
        val closeFailure = AssertionError("close")
        val actual = assertThrows(IllegalStateException::class.java) {
            withFrcStartupResources { resources ->
                resources.own(Resource("first", closed, original))
                resources.own(Resource("second", closed, closeFailure))
                throw original
            }
        }
        assertSame(original, actual)
        assertEquals(listOf("second", "first"), closed)
        assertEquals(listOf(closeFailure), original.suppressed.toList())
    }

    @Test fun `successful construction transfers ownership without closing the result`() {
        val closed = mutableListOf<String>()
        val result = withFrcStartupResources { it.own(Resource("owned by caller", closed)) }
        assertTrue(closed.isEmpty())
        result.close()
        assertEquals(listOf("owned by caller"), closed)
    }

    @Test fun `ownership tracks identity and rollback cannot close the same object twice`() {
        val closed = mutableListOf<String>()
        val resources = FrcStartupResources()
        val first = Resource("first", closed)
        resources.own(first)
        resources.own(first)
        resources.own(Resource("equal but distinct", closed))
        val failure = IllegalStateException("abort")
        resources.rollback(failure)
        resources.rollback(failure)
        assertEquals(listOf("equal but distinct", "first"), closed)
    }
}
