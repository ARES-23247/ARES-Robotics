package com.ares.analytics.ui.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeveloperReferenceCatalogTest {
    @Test
    fun `reference entries name source units invariants and verification`() {
        assertTrue(DeveloperReferenceCatalog.entries.isNotEmpty())
        DeveloperReferenceCatalog.entries.forEach { entry ->
            assertTrue(entry.sourcePath.startsWith("ARESLib-Kotlin/"), entry.id)
            assertTrue(entry.units.isNotBlank(), entry.id)
            assertTrue(entry.invariants.isNotEmpty(), entry.id)
            assertTrue(entry.relatedTests.isNotBlank(), entry.id)
        }
    }

    @Test
    fun `search recognizes student terminology`() {
        assertEquals("pose-estimator", DeveloperReferenceCatalog.search("kalman").single().id)
        assertEquals("hardware-registry", DeveloperReferenceCatalog.search("cached readings").single().id)
        assertEquals("robot-clock", DeveloperReferenceCatalog.search("deterministic").single().id)
    }
}
