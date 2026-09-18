package com.ares.analytics.service.project

import com.ares.analytics.service.AresGenerationPhase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals

class ConsumerRoundtripSupportTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `completion waiter handles repeated identical immediate preflight failures`() = runBlocking {
        val driver = ConsumerRoundtripBuild("fast-failure", repository = null, version = null)
        try {
            withTimeout(5_000L) {
                repeat(3) {
                    driver.generate(temporaryFolder.root)
                    assertEquals(AresGenerationPhase.FAILED, driver.service.aresGenerationState.value.phase)
                }
            }
        } finally {
            driver.service.shutdownAndJoin()
        }
    }
}
