package com.ares.analytics.viewmodel.hardware

import com.ares.analytics.service.hardware.HardwareReviewRequest
import com.ares.analytics.service.hardware.HardwarePhysicalValidationRequest
import com.ares.analytics.service.hardware.HardwareSetupService
import com.ares.analytics.service.hardware.HardwareSetupSnapshot
import com.ares.analytics.shared.models.League
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HardwareSetupState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val snapshot: HardwareSetupSnapshot? = null,
    val reviewerName: String = "",
    val wiringMatched: Boolean = false,
    val addressesChecked: Boolean = false,
    val directionsChecked: Boolean = false,
    val neutralOutputsChecked: Boolean = false,
    val limitsChecked: Boolean = false,
    val physicalValidatorName: String = "",
    val physicalEvidenceSummary: String = "",
    val directionsAndPolarityTested: Boolean = false,
    val unitsAndSensorsTested: Boolean = false,
    val disabledNeutralTested: Boolean = false,
    val limitsAndCurrentTested: Boolean = false,
    val faultRecoveryTested: Boolean = false,
    val error: String? = null,
) {
    val checklistComplete: Boolean
        get() = wiringMatched && addressesChecked && directionsChecked && neutralOutputsChecked && limitsChecked

    val canSaveReview: Boolean
        get() = !loading && !saving && reviewerName.trim().length >= 2 && checklistComplete && snapshot?.canReview == true

    val physicalChecklistComplete: Boolean
        get() = directionsAndPolarityTested && unitsAndSensorsTested && disabledNeutralTested &&
            limitsAndCurrentTested && faultRecoveryTested

    val canSavePhysicalValidation: Boolean
        get() = !loading && !saving && snapshot?.readyForPhysicalValidation == true && physicalValidatorName.trim().length >= 2 &&
            physicalEvidenceSummary.trim().length >= 20 && physicalChecklistComplete
}

/** State holder for the descriptor-backed physical hardware review workflow. */
class HardwareSetupViewModel(
    private val projectPath: String,
    private val league: League,
    private val service: HardwareSetupService,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(HardwareSetupState())
    val state: StateFlow<HardwareSetupState> = _state.asStateFlow()
    private val operationLock = Any()
    private var operationToken: Any? = null
    private var operation: Job? = null

    init {
        refresh()
    }

    fun refresh() = startOperation(
        loading = true,
        failureMessage = "Hardware Setup could not inspect this project.",
    ) { service.inspect(projectPath, league) }

    fun setReviewerName(value: String) {
        _state.update { it.copy(reviewerName = value.take(80), error = null) }
    }

    fun setWiringMatched(value: Boolean) {
        _state.update { it.copy(wiringMatched = value, error = null) }
    }

    fun setAddressesChecked(value: Boolean) {
        _state.update { it.copy(addressesChecked = value, error = null) }
    }

    fun setDirectionsChecked(value: Boolean) {
        _state.update { it.copy(directionsChecked = value, error = null) }
    }

    fun setNeutralOutputsChecked(value: Boolean) {
        _state.update { it.copy(neutralOutputsChecked = value, error = null) }
    }

    fun setLimitsChecked(value: Boolean) {
        _state.update { it.copy(limitsChecked = value, error = null) }
    }

    fun setPhysicalValidatorName(value: String) {
        _state.update { it.copy(physicalValidatorName = value.take(80), error = null) }
    }

    fun setPhysicalEvidenceSummary(value: String) {
        _state.update { it.copy(physicalEvidenceSummary = value.take(1_000), error = null) }
    }

    fun setDirectionsAndPolarityTested(value: Boolean) { _state.update { it.copy(directionsAndPolarityTested = value, error = null) } }
    fun setUnitsAndSensorsTested(value: Boolean) { _state.update { it.copy(unitsAndSensorsTested = value, error = null) } }
    fun setDisabledNeutralTested(value: Boolean) { _state.update { it.copy(disabledNeutralTested = value, error = null) } }
    fun setLimitsAndCurrentTested(value: Boolean) { _state.update { it.copy(limitsAndCurrentTested = value, error = null) } }
    fun setFaultRecoveryTested(value: Boolean) { _state.update { it.copy(faultRecoveryTested = value, error = null) } }

    fun saveReview() = startOperation(
        loading = false,
        failureMessage = "The hardware review could not be recorded.",
        canStart = { it.canSaveReview },
    ) { submitted ->
        service.saveReview(
            projectPath,
            league,
            HardwareReviewRequest(
                reviewerName = submitted.reviewerName,
                wiringMatched = submitted.wiringMatched,
                addressesChecked = submitted.addressesChecked,
                directionsChecked = submitted.directionsChecked,
                neutralOutputsChecked = submitted.neutralOutputsChecked,
                limitsChecked = submitted.limitsChecked,
            ),
        )
    }

    fun savePhysicalValidation() = startOperation(
        loading = false,
        failureMessage = "Physical validation evidence could not be recorded.",
        canStart = { it.canSavePhysicalValidation },
    ) { submitted ->
        service.savePhysicalValidation(
            projectPath,
            league,
            HardwarePhysicalValidationRequest(
                validatedBy = submitted.physicalValidatorName,
                evidenceSummary = submitted.physicalEvidenceSummary,
                directionsAndPolarityTested = submitted.directionsAndPolarityTested,
                unitsAndSensorsTested = submitted.unitsAndSensorsTested,
                disabledNeutralTested = submitted.disabledNeutralTested,
                limitsAndCurrentTested = submitted.limitsAndCurrentTested,
                faultRecoveryTested = submitted.faultRecoveryTested,
            ),
        )
    }

    private fun startOperation(
        loading: Boolean,
        failureMessage: String,
        canStart: (HardwareSetupState) -> Boolean = { true },
        action: (HardwareSetupState) -> HardwareSetupSnapshot,
    ) {
        synchronized(operationLock) {
            val submitted = _state.value
            if (!canStart(submitted)) return
            // Blocking file IO may outlive cancellation. Only the latest operation may publish.
            val token = Any()
            operationToken = token
            operation?.cancel()
            _state.update { it.copy(loading = loading, saving = !loading, error = null) }
            if (operationToken !== token) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val snapshot = withContext(Dispatchers.IO) { action(submitted) }
                    currentCoroutineContext().ensureActive()
                    publish(token) { it.withSnapshot(snapshot) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    publish(token) {
                        val current = if (loading) it.withSnapshot(null) else it
                        current.copy(loading = false, saving = false, error = failure.message ?: failureMessage)
                    }
                }
            }
            operation = job
            // Completion also runs when an already-cancelled owner prevents the body from starting.
            job.invokeOnCompletion {
                publish(token) { it.copy(loading = false, saving = false) }
            }
            job.start()
        }
    }

    private fun publish(token: Any, transform: (HardwareSetupState) -> HardwareSetupState) {
        synchronized(operationLock) {
            if (operationToken === token) _state.update(transform)
        }
    }

    private fun HardwareSetupState.withSnapshot(next: HardwareSetupSnapshot?): HardwareSetupState {
        val unchanged = next != null && snapshot?.inventoryHash == next.inventoryHash
        val keepReview = unchanged && snapshot.canReview && next.canReview
        val keepPhysical = unchanged && snapshot.readyForPhysicalValidation && next.readyForPhysicalValidation
        return copy(
            loading = false,
            saving = false,
            snapshot = next,
            reviewerName = reviewerName.ifBlank { next?.reviewedBy.orEmpty() },
            wiringMatched = keepReview && wiringMatched,
            addressesChecked = keepReview && addressesChecked,
            directionsChecked = keepReview && directionsChecked,
            neutralOutputsChecked = keepReview && neutralOutputsChecked,
            limitsChecked = keepReview && limitsChecked,
            physicalEvidenceSummary = if (keepPhysical) physicalEvidenceSummary else "",
            directionsAndPolarityTested = keepPhysical && directionsAndPolarityTested,
            unitsAndSensorsTested = keepPhysical && unitsAndSensorsTested,
            disabledNeutralTested = keepPhysical && disabledNeutralTested,
            limitsAndCurrentTested = keepPhysical && limitsAndCurrentTested,
            faultRecoveryTested = keepPhysical && faultRecoveryTested,
            error = null,
        )
    }
}
