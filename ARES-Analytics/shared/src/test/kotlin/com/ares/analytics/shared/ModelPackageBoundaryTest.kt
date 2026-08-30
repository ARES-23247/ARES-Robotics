package com.ares.analytics.shared

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

class ModelPackageBoundaryTest {
    @Test
    fun `root shared package does not re-export canonical models`() {
        val source = sequenceOf(
            File("shared/src/main/kotlin/com/ares/analytics/shared/Models.kt"),
            File("src/main/kotlin/com/ares/analytics/shared/Models.kt"),
        ).firstOrNull(File::isFile)
        checkNotNull(source) { "Could not locate shared Models.kt" }

        assertFalse(
            source.readText().lineSequence().any { it.trimStart().startsWith("typealias ") },
            "Canonical shared.models types must not be re-exported through compatibility aliases.",
        )
    }
}
