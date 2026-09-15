package com.ares.analytics.service.hardware

import com.ares.analytics.service.commissioning.CommissioningVerificationService
import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.service.drivebase.DrivebaseProjectRepository
import com.ares.analytics.service.drivebase.defaultDrivebase
import com.ares.analytics.service.project.persistence.SubsystemProjectRepository
import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.hardware.HardwareSetupViewModel
import com.areslib.subsystem.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.mockito.Mockito
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class HardwareEvidencePersistenceTest {
    private class Fixture {
        val root = Files.createTempDirectory("ares-evidence-binding").toFile()
        val repository = SubsystemProjectRepository()
        val owner = SupervisorJob()
        private val scope = CoroutineScope(owner + Dispatchers.Unconfined)
        var afterVerification: () -> Unit = {}
        private val verifier = CommissioningVerificationService()
        private val observedVerifier = Mockito.mock(CommissioningVerificationService::class.java) { invocation ->
            when (invocation.method.name) {
                "verify" -> verifier.verify(invocation.getArgument(0)).also { afterVerification() }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val service = HardwareSetupService(
            commissioningVerificationService = observedVerifier,
            clock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC),
        )

        init {
            DrivebaseProjectRepository().saveReviewed(root.path, null, defaultDrivebase("team1-robot", DrivebaseKind.FTC_MECANUM, League.FTC))
            repository.save(root.path, SubsystemTemplates.create(SubsystemTemplate.SIMPLE_ACTUATOR, "arm", "Arm", SubsystemPlatform.FTC).let { document ->
                document.copy(hardware = document.hardware.map { it.copy(connection = it.connection.copy(hardwareMapName = "arm")) })
            })
        }

        suspend fun model(): HardwareSetupViewModel = HardwareSetupViewModel(root.path, League.FTC, service, scope).also { joinOperations() }
        suspend fun joinOperations() = withTimeout(10_000L) { owner.children.toList().joinAll() }
        fun changeHardware() {
            val current = repository.load(root.path, "arm")
            repository.save(root.path, current.copy(hardware = current.hardware.map { it.copy(inverted = !it.inverted) }))
        }
        fun records(kind: String): List<File> = File(root, ".ares/evidence/hardware/$kind").listFiles().orEmpty().filter { it.extension == "json" }
        suspend fun close() {
            withTimeout(10_000L) { owner.cancelAndJoin() }
            check(root.deleteRecursively()) { "Could not remove owned test project" }
        }
    }

    private suspend fun withFixture(block: suspend Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.close() }
    }

    private fun completeReview(model: HardwareSetupViewModel) {
        model.setReviewerName("Fixture reviewer")
        model.setWiringMatched(true); model.setAddressesChecked(true); model.setDirectionsChecked(true)
        model.setNeutralOutputsChecked(true); model.setLimitsChecked(true)
    }

    private fun completePhysical(model: HardwareSetupViewModel) {
        model.setPhysicalValidatorName("Fixture validator")
        model.setPhysicalEvidenceSummary("Synthetic test evidence; no real robot was tested.")
        model.setDirectionsAndPolarityTested(true); model.setUnitsAndSensorsTested(true)
        model.setDisabledNeutralTested(true); model.setLimitsAndCurrentTested(true); model.setFaultRecoveryTested(true)
    }

    @Test
    fun `review submitted from an older screen cannot approve newly edited hardware`() = runBlocking {
        withFixture {
            val model = model(); completeReview(model)
            val displayedHash = requireNotNull(model.state.value.snapshot).inventoryHash
            changeHardware()
            assertNotEquals(displayedHash, service.inspect(root.path, League.FTC).inventoryHash)
            model.saveReview(); joinOperations()
            assertNotNull(model.state.value.error)
            assertTrue(records("configuration").isEmpty(), "Old form assertions must not create a new-inventory review")
            assertEquals(HardwareReviewStatus.NOT_REVIEWED, service.inspect(root.path, League.FTC).reviewStatus)
        }
    }

    @Test
    fun `physical assertions from an old screen cannot attach to another reviewed inventory`() = runBlocking {
        withFixture {
            val oldScreen = model(); completeReview(oldScreen); oldScreen.saveReview(); joinOperations()
            completePhysical(oldScreen)
            val displayedHash = requireNotNull(oldScreen.state.value.snapshot).inventoryHash
            changeHardware()
            val currentScreen = model(); completeReview(currentScreen); currentScreen.saveReview(); joinOperations()
            val current = requireNotNull(currentScreen.state.value.snapshot)
            assertTrue(current.readyForPhysicalValidation)
            assertNotEquals(displayedHash, current.inventoryHash)
            oldScreen.savePhysicalValidation(); joinOperations()
            assertNotNull(oldScreen.state.value.error)
            assertTrue(records("physical").isEmpty(), "Old observed behavior must not become evidence for new hardware")
            assertNull(service.inspect(root.path, League.FTC).physicalValidation)
        }
    }

    @Test
    fun `unchanged displayed inventory records both forms of evidence through the real service`() = runBlocking {
        withFixture {
            val model = model()
            val displayedHash = requireNotNull(model.state.value.snapshot).inventoryHash
            completeReview(model); model.saveReview(); joinOperations()
            assertNull(model.state.value.error)
            assertEquals(HardwareReviewStatus.CURRENT, model.state.value.snapshot?.reviewStatus)
            completePhysical(model); model.savePhysicalValidation(); joinOperations()
            assertNull(model.state.value.error)
            assertEquals(displayedHash, model.state.value.snapshot?.physicalValidation?.inventoryHash)
            assertEquals(1, records("configuration").size)
            assertEquals(1, records("physical").size)
        }
    }

    @Test
    fun `source edit after inspection cannot mix later source hashes into the submitted review`() = runBlocking {
        withFixture {
            val model = model(); completeReview(model)
            val displayedHash = requireNotNull(model.state.value.snapshot).inventoryHash
            val displayedSourceHash = SubsystemDocumentCodec.contentHash(repository.load(root.path, "arm"))
            afterVerification = { afterVerification = {}; changeHardware() }
            model.saveReview(); joinOperations()
            assertNull(model.state.value.error)
            val record = Json.parseToJsonElement(records("configuration").single().readText()).jsonObject
            assertEquals(displayedHash, record.getValue("inventoryHash").jsonPrimitive.content)
            val source = record.getValue("sources").jsonArray.single { it.jsonObject.getValue("path").jsonPrimitive.content == ".ares/subsystems/arm.aressubsystem" }.jsonObject
            assertEquals(displayedSourceHash, source.getValue("sha256").jsonPrimitive.content)
            assertEquals(HardwareReviewStatus.STALE, model.state.value.snapshot?.reviewStatus)
            assertFalse(model.state.value.checklistComplete)
        }
    }

    @Test
    fun `service callers cannot bypass inventory binding with blank or unrelated hashes`() = runBlocking {
        withFixture {
            val model = model(); completeReview(model); model.saveReview(); joinOperations()
            assertTrue(requireNotNull(model.state.value.snapshot).readyForPhysicalValidation)
            val before = records("configuration").associate { it.name to it.readText() }
            for (expected in listOf("", "0".repeat(64))) {
                val reviewError = assertFailsWith<IllegalArgumentException> {
                    service.saveReview(root.path, League.FTC, HardwareReviewRequest(expected, "Another fixture reviewer", true, true, true, true, true))
                }
                assertTrue(reviewError.message.orEmpty().contains("changed since this checklist"))
                val physicalError = assertFailsWith<IllegalArgumentException> {
                    service.savePhysicalValidation(root.path, League.FTC, HardwarePhysicalValidationRequest(
                        expected, "Fixture validator", "Synthetic test evidence; no robot was tested.", true, true, true, true, true,
                    ))
                }
                assertTrue(physicalError.message.orEmpty().contains("changed since this checklist"))
            }
            assertEquals(before, records("configuration").associate { it.name to it.readText() })
            assertTrue(records("physical").isEmpty())
        }
    }
}
