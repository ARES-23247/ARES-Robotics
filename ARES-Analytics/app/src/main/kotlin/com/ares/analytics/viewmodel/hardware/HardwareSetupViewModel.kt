package com.ares.analytics.viewmodel.hardware

import com.ares.analytics.service.hardware.HardwareReviewRequest
import com.ares.analytics.service.hardware.HardwarePhysicalValidationRequest
import com.ares.analytics.service.hardware.HardwareSetupService
import com.ares.analytics.service.hardware.HardwareSetupSnapshot
import com.ares.analytics.shared.models.League
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        get() = !loading && !saving && snapshot?.reviewStatus == com.ares.analytics.service.hardware.HardwareReviewStatus.CURRENT &&
            snapshot.simulationVerification.verified && physicalValidatorName.trim().length >= 2 &&
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
    private var operation: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        operation?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        operation = scope.launch {
            runCatching { withContext(Dispatchers.IO) { service.inspect(projectPath, league) } }
                .onSuccess { snapshot ->
                    _state.value = _state.value.copy(
                        loading = false,
                        snapshot = snapshot,
                        reviewerName = _state.value.reviewerName.ifBlank { snapshot.reviewedBy.orEmpty() },
                        error = null,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        snapshot = null,
                        error = error.message ?: "Hardware Setup could not inspect this project.",
                    )
                }
        }
    }

    fun setReviewerName(value: String) {
        _state.value = _state.value.copy(reviewerName = value.take(80), error = null)
    }

    fun setWiringMatched(value: Boolean) {
        _state.value = _state.value.copy(wiringMatched = value, error = null)
    }

    fun setAddressesChecked(value: Boolean) {
        _state.value = _state.value.copy(addressesChecked = value, error = null)
    }

    fun setDirectionsChecked(value: Boolean) {
        _state.value = _state.value.copy(directionsChecked = value, error = null)
    }

    fun setNeutralOutputsChecked(value: Boolean) {
        _state.value = _state.value.copy(neutralOutputsChecked = value, error = null)
    }

    fun setLimitsChecked(value: Boolean) {
        _state.value = _state.value.copy(limitsChecked = value, error = null)
    }

    fun setPhysicalValidatorName(value: String) {
        _state.value = _state.value.copy(physicalValidatorName = value.take(80), error = null)
    }

    fun setPhysicalEvidenceSummary(value: String) {
        _state.value = _state.value.copy(physicalEvidenceSummary = value.take(1_000), error = null)
    }

    fun setDirectionsAndPolarityTested(value: Boolean) { _state.value = _state.value.copy(directionsAndPolarityTested = value, error = null) }
    fun setUnitsAndSensorsTested(value: Boolean) { _state.value = _state.value.copy(unitsAndSensorsTested = value, error = null) }
    fun setDisabledNeutralTested(value: Boolean) { _state.value = _state.value.copy(disabledNeutralTested = value, error = null) }
    fun setLimitsAndCurrentTested(value: Boolean) { _state.value = _state.value.copy(limitsAndCurrentTested = value, error = null) }
    fun setFaultRecoveryTested(value: Boolean) { _state.value = _state.value.copy(faultRecoveryTested = value, error = null) }

    fun saveReview() {
        val current = _state.value
        if (!current.canSaveReview) return
        operation?.cancel()
        _state.value = current.copy(saving = true, error = null)
        operation = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    service.saveReview(
                        projectPath,
                        league,
                        HardwareReviewRequest(
                            reviewerName = current.reviewerName,
                            wiringMatched = current.wiringMatched,
                            addressesChecked = current.addressesChecked,
                            directionsChecked = current.directionsChecked,
                            neutralOutputsChecked = current.neutralOutputsChecked,
                            limitsChecked = current.limitsChecked,
                        ),
                    )
                }
            }.onSuccess { snapshot ->
                _state.value = current.copy(
                    loading = false,
                    saving = false,
                    snapshot = snapshot,
                    error = null,
                )
            }.onFailure { error ->
                _state.value = current.copy(
                    saving = false,
                    error = error.message ?: "The hardware review could not be recorded.",
                )
            }
        }
    }

    fun savePhysicalValidation() {
        val current = _state.value
        if (!current.canSavePhysicalValidation) return
        operation?.cancel()
        _state.value = current.copy(saving = true, error = null)
        operation = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    service.savePhysicalValidation(
                        projectPath,
                        league,
                        HardwarePhysicalValidationRequest(
                            validatedBy = current.physicalValidatorName,
                            evidenceSummary = current.physicalEvidenceSummary,
                            directionsAndPolarityTested = current.directionsAndPolarityTested,
                            unitsAndSensorsTested = current.unitsAndSensorsTested,
                            disabledNeutralTested = current.disabledNeutralTested,
                            limitsAndCurrentTested = current.limitsAndCurrentTested,
                            faultRecoveryTested = current.faultRecoveryTested,
                        ),
                    )
                }
            }.onSuccess { snapshot ->
                _state.value = current.copy(saving = false, snapshot = snapshot, error = null)
            }.onFailure { error ->
                _state.value = current.copy(saving = false, error = error.message ?: "Physical validation evidence could not be recorded.")
            }
        }
    }
}
