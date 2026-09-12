package com.ares.analytics.viewmodel.hardware

import com.ares.analytics.service.commissioning.CommissioningSimulationStatus
import com.ares.analytics.service.commissioning.CommissioningSimulationSummary
import com.ares.analytics.service.hardware.*
import com.ares.analytics.shared.models.League
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.mockito.Mockito
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HardwareSetupViewModelTest {
    private fun snapshot(hash: String = "A") = HardwareSetupSnapshot(
        projectPath = "fixture", league = League.FTC, inventoryHash = hash,
        items = listOf(HardwareInventoryItem(
            uid = "motor", displayName = "Motor", owner = HardwareInventoryOwner.SUBSYSTEM,
            ownerDisplayName = "Arm", sourcePath = ".ares/subsystems/arm.aressubsystem",
            role = "motor", roleKey = "MOTOR", addressKind = HardwareAddressKind.FTC_HARDWARE_MAP,
            address = "arm", required = true, inverted = false,
        )),
        issues = emptyList(), reviewStatus = HardwareReviewStatus.CURRENT, reviewedBy = "Prior reviewer",
        simulationVerification = CommissioningSimulationSummary(CommissioningSimulationStatus.VERIFIED, 1, 1, emptyList(), emptyList()),
    )

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun block() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Test gate was not released" }
        }
        fun awaitEntry() = assertTrue(entered.await(5, TimeUnit.SECONDS), "Service did not enter test gate")
    }

    private inner class Fixture {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Unconfined)
        private val gates = mutableListOf<Gate>()
        var inspect: () -> HardwareSetupSnapshot = { snapshot() }
        var review: (HardwareReviewRequest) -> HardwareSetupSnapshot = { snapshot() }
        var physical: (HardwarePhysicalValidationRequest) -> HardwareSetupSnapshot = { snapshot() }
        val service = Mockito.mock(HardwareSetupService::class.java) { invocation ->
            when (invocation.method.name) {
                "inspect" -> inspect()
                "saveReview" -> review(invocation.getArgument(2))
                "savePhysicalValidation" -> physical(invocation.getArgument(2))
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        fun gate() = Gate().also { gates += it }
        fun model() = HardwareSetupViewModel("fixture", League.FTC, service, scope)
        suspend fun joinOperations() = withTimeout(5_000L) { owner.children.toList().joinAll() }
        suspend fun close() {
            gates.forEach { it.release.countDown() }
            withTimeout(5_000L) { owner.cancelAndJoin() }
        }
    }

    private suspend fun idle(model: HardwareSetupViewModel) = withTimeout(5_000L) {
        model.state.first { !it.loading && !it.saving }
    }

    private fun completeReview(model: HardwareSetupViewModel) {
        model.setReviewerName("First reviewer")
        model.setWiringMatched(true); model.setAddressesChecked(true); model.setDirectionsChecked(true)
        model.setNeutralOutputsChecked(true); model.setLimitsChecked(true)
    }

    private fun completePhysical(model: HardwareSetupViewModel) {
        model.setPhysicalValidatorName("First validator")
        model.setPhysicalEvidenceSummary("Original supervised fixture evidence summary.")
        model.setDirectionsAndPolarityTested(true); model.setUnitsAndSensorsTested(true)
        model.setDisabledNeutralTested(true); model.setLimitsAndCurrentTested(true); model.setFaultRecoveryTested(true)
    }

    private fun assertReviewCleared(state: HardwareSetupState) {
        assertFalse(state.wiringMatched)
        assertFalse(state.addressesChecked)
        assertFalse(state.directionsChecked)
        assertFalse(state.neutralOutputsChecked)
        assertFalse(state.limitsChecked)
    }

    private fun assertPhysicalCleared(state: HardwareSetupState) {
        assertFalse(state.directionsAndPolarityTested)
        assertFalse(state.unitsAndSensorsTested)
        assertFalse(state.disabledNeutralTested)
        assertFalse(state.limitsAndCurrentTested)
        assertFalse(state.faultRecoveryTested)
        assertEquals("", state.physicalEvidenceSummary)
    }

    @Test
    fun `cancelled refresh cannot replace the newer completed inventory`() = runBlocking {
        val fixture = Fixture()
        try {
            val old = fixture.gate()
            val calls = AtomicInteger()
            fixture.inspect = { if (calls.incrementAndGet() == 1) { old.block(); snapshot("old") } else snapshot("new") }
            val model = fixture.model(); old.awaitEntry()
            model.refresh()
            withTimeout(5_000L) { model.state.first { it.snapshot?.inventoryHash == "new" && !it.loading } }
            old.release.countDown(); fixture.joinOperations()
            assertEquals("new", model.state.value.snapshot?.inventoryHash)
            assertNull(model.state.value.error)
        } finally { fixture.close() }
    }

    @Test
    fun `refresh supersedes a save without keeping saving true or restoring the old snapshot`() = runBlocking {
        val fixture = Fixture()
        try {
            val saving = fixture.gate()
            fixture.review = { saving.block(); snapshot("old") }
            val model = fixture.model(); idle(model); completeReview(model)
            model.saveReview(); saving.awaitEntry()
            fixture.inspect = { snapshot("new") }
            model.refresh()
            withTimeout(5_000L) { model.state.first { it.snapshot?.inventoryHash == "new" && !it.loading } }
            assertFalse(model.state.value.saving)
            saving.release.countDown(); fixture.joinOperations()
            assertEquals("new", model.state.value.snapshot?.inventoryHash)
            assertNull(model.state.value.error)
        } finally { fixture.close() }
    }

    @Test
    fun `save results and failures preserve edits made while either evidence operation runs`() = runBlocking {
        for (physical in listOf(false, true)) for (failure in listOf(false, true)) {
            val fixture = Fixture()
            try {
                val saving = fixture.gate()
                var submittedName: String? = null
                fixture.review = { request ->
                    submittedName = request.reviewerName; saving.block()
                    if (failure) throw IOException("fixture write failure")
                    snapshot()
                }
                fixture.physical = { request ->
                    submittedName = request.validatedBy; saving.block()
                    if (failure) throw IOException("fixture write failure")
                    snapshot()
                }
                val model = fixture.model(); idle(model); completeReview(model); completePhysical(model)
                if (physical) model.savePhysicalValidation() else model.saveReview()
                saving.awaitEntry()
                model.setReviewerName("Edited reviewer"); model.setWiringMatched(false)
                model.setPhysicalValidatorName("Edited validator")
                model.setPhysicalEvidenceSummary("Edited supervised fixture evidence summary.")
                model.setFaultRecoveryTested(false)
                saving.release.countDown(); fixture.joinOperations()
                val state = model.state.value
                assertEquals(if (physical) "First validator" else "First reviewer", submittedName)
                assertEquals("Edited reviewer", state.reviewerName, "physical=$physical failure=$failure")
                assertFalse(state.wiringMatched)
                assertEquals("Edited validator", state.physicalValidatorName)
                assertEquals("Edited supervised fixture evidence summary.", state.physicalEvidenceSummary)
                assertFalse(state.faultRecoveryTested)
                assertFalse(state.saving)
                assertEquals(if (failure) "fixture write failure" else null, state.error)
            } finally { fixture.close() }
        }
    }

    @Test
    fun `changed or invalid inventory clears hardware-bound checklists and physical evidence text`() = runBlocking {
        for (invalid in listOf(false, true)) {
            val fixture = Fixture()
            try {
                val model = fixture.model(); idle(model); completeReview(model); completePhysical(model)
                fixture.inspect = {
                    if (invalid) snapshot().copy(issues = listOf(HardwareInventoryIssue(HardwareIssueSeverity.ERROR, "Malformed source")))
                    else snapshot("B")
                }
                model.refresh(); fixture.joinOperations()
                val state = model.state.value
                assertReviewCleared(state)
                assertPhysicalCleared(state)
                assertEquals("First reviewer", state.reviewerName)
                assertEquals("First validator", state.physicalValidatorName)
            } finally { fixture.close() }
        }
    }

    @Test
    fun `unchanged valid inventory retains the current checklist draft`() = runBlocking {
        val fixture = Fixture()
        try {
            val model = fixture.model(); idle(model); completeReview(model); completePhysical(model)
            model.refresh(); fixture.joinOperations()
            assertTrue(model.state.value.checklistComplete)
            assertTrue(model.state.value.physicalChecklistComplete)
            assertEquals("Original supervised fixture evidence summary.", model.state.value.physicalEvidenceSummary)
        } finally { fixture.close() }
    }

    @Test
    fun `inspection failure cannot leave approvals ready to revive on the next successful refresh`() = runBlocking {
        val fixture = Fixture()
        try {
            val model = fixture.model(); idle(model); completeReview(model); completePhysical(model)
            fixture.inspect = { throw IOException("fixture inspection failure") }
            model.refresh(); fixture.joinOperations()
            assertNull(model.state.value.snapshot)
            assertEquals("fixture inspection failure", model.state.value.error)
            assertReviewCleared(model.state.value)
            assertPhysicalCleared(model.state.value)
            fixture.inspect = { snapshot() }
            model.refresh(); fixture.joinOperations()
            assertFalse(model.state.value.canSaveReview)
            assertFalse(model.state.value.canSavePhysicalValidation)
        } finally { fixture.close() }
    }

    @Test
    fun `cancelled owner finishes without displaying cancellation as a service failure`() = runBlocking {
        val fixture = Fixture()
        try {
            val loading = fixture.gate(); fixture.inspect = { loading.block(); snapshot() }
            val model = fixture.model(); loading.awaitEntry()
            fixture.owner.cancel(); loading.release.countDown()
            fixture.owner.join()
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.saving)
            assertNull(model.state.value.error)
        } finally { fixture.close() }
    }

    @Test
    fun `already cancelled owner cannot leave an operation permanently busy`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.owner.cancelAndJoin()
            val model = fixture.model()
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.saving)
            assertNull(model.state.value.error)
        } finally { fixture.close() }
    }

    @Test
    fun `lost review or simulation readiness clears only the physical checklist`() = runBlocking {
        for (lostReview in listOf(false, true)) {
            val fixture = Fixture()
            try {
                val model = fixture.model(); idle(model); completeReview(model); completePhysical(model)
                fixture.inspect = {
                    if (lostReview) snapshot().copy(reviewStatus = HardwareReviewStatus.STALE)
                    else snapshot().copy(simulationVerification = snapshot().simulationVerification.copy(status = CommissioningSimulationStatus.NEEDS_REVIEW))
                }
                model.refresh(); fixture.joinOperations()
                assertTrue(model.state.value.checklistComplete)
                assertPhysicalCleared(model.state.value)
                assertFalse(model.state.value.canSavePhysicalValidation)
            } finally { fixture.close() }
        }
    }

    @Test
    fun `ineligible and repeated save clicks cannot start extra evidence writes`() = runBlocking {
        val fixture = Fixture()
        try {
            val writes = AtomicInteger()
            val saving = fixture.gate()
            fixture.review = { writes.incrementAndGet(); saving.block(); snapshot() }
            fixture.physical = { writes.incrementAndGet(); saving.block(); snapshot() }
            val model = fixture.model(); idle(model)
            model.saveReview(); model.savePhysicalValidation(); fixture.joinOperations()
            assertEquals(0, writes.get())
            completeReview(model); completePhysical(model)
            model.saveReview(); saving.awaitEntry()
            repeat(3) { model.saveReview(); model.savePhysicalValidation() }
            saving.release.countDown(); fixture.joinOperations()
            assertEquals(1, writes.get())
        } finally { fixture.close() }
    }

    @Test
    fun `owner cancellation during either save preserves draft without publishing a late error`() = runBlocking {
        for (physical in listOf(false, true)) {
            val fixture = Fixture()
            try {
                val saving = fixture.gate()
                fixture.review = { saving.block(); throw IOException("late write failure") }
                fixture.physical = { saving.block(); throw IOException("late write failure") }
                val model = fixture.model(); idle(model); completeReview(model); completePhysical(model)
                if (physical) model.savePhysicalValidation() else model.saveReview()
                saving.awaitEntry()
                model.setReviewerName("Edited reviewer")
                fixture.owner.cancel(); saving.release.countDown()
                withTimeout(5_000L) { fixture.owner.join() }
                assertFalse(model.state.value.loading)
                assertFalse(model.state.value.saving)
                assertNull(model.state.value.error)
                assertEquals("Edited reviewer", model.state.value.reviewerName)
            } finally { fixture.close() }
        }
    }

    @Test
    fun `checks entered without prerequisites cannot become approvals when readiness returns`() = runBlocking {
        for (missing in listOf("inventory", "review", "simulation")) {
            val fixture = Fixture()
            try {
                fixture.inspect = {
                    when (missing) {
                        "inventory" -> snapshot().copy(issues = listOf(HardwareInventoryIssue(HardwareIssueSeverity.ERROR, "Malformed source")))
                        "review" -> snapshot().copy(reviewStatus = HardwareReviewStatus.STALE)
                        else -> snapshot().copy(simulationVerification = snapshot().simulationVerification.copy(status = CommissioningSimulationStatus.NEEDS_REVIEW))
                    }
                }
                val model = fixture.model(); idle(model)
                // Checkboxes remain editable in the screen while the save button is disabled.
                completeReview(model); completePhysical(model)
                fixture.inspect = { snapshot() }
                model.refresh(); fixture.joinOperations()
                if (missing == "inventory") assertReviewCleared(model.state.value)
                else assertTrue(model.state.value.checklistComplete)
                assertPhysicalCleared(model.state.value)
                assertFalse(model.state.value.canSavePhysicalValidation)
            } finally { fixture.close() }
        }
    }
}
