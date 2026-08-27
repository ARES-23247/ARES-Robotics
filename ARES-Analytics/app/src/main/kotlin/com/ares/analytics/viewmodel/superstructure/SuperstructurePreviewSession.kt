package com.ares.analytics.viewmodel.superstructure

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.reducer.rootReducer
import com.areslib.sequencer.Task
import com.areslib.state.RobotState
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemValueType
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureFieldReference
import com.areslib.superstructure.SuperstructurePortHealthBits
import com.areslib.superstructure.SuperstructureRuntime
import com.areslib.superstructure.SuperstructureRuntimeBinding
import com.areslib.superstructure.SuperstructureRuntimeState

enum class PreviewPortCondition {
    HEALTHY,
    STALE,
    INVALID,
    UNCONFIGURED,
    UNHOMED_OR_UNCALIBRATED,
    CURRENT_INVALID,
    OUTPUT_FAULT,
}

data class SuperstructurePreviewPort(
    val reference: SuperstructureFieldReference,
    val label: String,
    val type: SubsystemValueType,
    val unit: String?,
    val numericValue: Double? = null,
    val booleanValue: Boolean? = null,
    val stringValue: String? = null,
    val ageMs: Long,
    val healthBits: Int,
    val condition: PreviewPortCondition,
)

data class SuperstructurePreviewSnapshot(
    val nowMs: Long,
    val isEnabled: Boolean,
    val currentStateId: String,
    val previousStateId: String,
    val stateAgeMs: Long,
    val transitionSequence: Long,
    val candidateTransitionId: String?,
    val isFaulted: Boolean,
    val faultReason: String?,
    val lastRejectionReason: String?,
    val lastLifecycleError: String?,
    val lifecycleActions: List<String>,
    val ports: List<SuperstructurePreviewPort>,
)

/**
 * Editor-only harness that executes the production [SuperstructureRuntime] against caller-owned
 * cached values. It is a deterministic state-machine preview, not hardware or mechanism physics.
 */
class SuperstructurePreviewSession(
    private val document: SuperstructureDocument,
    subsystems: List<SubsystemDocument>,
) {
    private data class Port(
        val reference: SuperstructureFieldReference,
        val label: String,
        val type: SubsystemValueType,
        val unit: String?,
        val maxAgeMs: Long,
        var numericValue: Double = Double.NaN,
        var booleanValue: Boolean? = null,
        var stringValue: String? = null,
        var sampleTimestampMs: Long = 0L,
        var baseHealthBits: Int = SuperstructurePortHealthBits.CONTROL_READY_MASK and SuperstructurePortHealthBits.FRESH.inv(),
        var condition: PreviewPortCondition = PreviewPortCondition.HEALTHY,
    )

    private val bySubsystemUid = subsystems.associateBy { it.uid }
    private val ports = referencedPorts(document).mapIndexed { _, reference ->
        val subsystem = requireNotNull(bySubsystemUid[reference.subsystemUid])
        val field = requireNotNull(subsystem.stateFields.singleOrNull { it.uid == reference.fieldUid })
        val maxAge = subsystem.hardware.asSequence()
            .flatMap { it.measurements.asSequence() }
            .filter { it.fieldId == field.fieldId }
            .mapNotNull { it.maxAgeMs }
            .minOrNull() ?: subsystem.safety.feedbackTimeoutMs ?: 250L
        Port(
            reference = reference,
            label = "${subsystem.displayName} · ${field.displayName}",
            type = field.type,
            unit = field.unit,
            maxAgeMs = maxAge,
            numericValue = field.defaultNumber ?: field.defaultInt?.toDouble() ?: Double.NaN,
            booleanValue = field.defaultBoolean,
            stringValue = field.defaultText,
        )
    }
    private val indexByKey = ports.withIndex().associate { indexed ->
        (indexed.value.reference.subsystemUid to indexed.value.reference.fieldUid) to indexed.index
    }
    private val lifecycleActions = mutableListOf<String>()
    private val store = Store(RobotState(), ::rootReducer)
    private val binding = PreviewBinding()
    private val runtime = SuperstructureRuntime(document, binding)
    private var nowMs = 0L
    var enabled: Boolean = true

    init {
        runtime.readSensors(store, nowMs)
    }

    fun snapshot(): SuperstructurePreviewSnapshot {
        val machine = machineState()
        return SuperstructurePreviewSnapshot(
            nowMs = nowMs,
            isEnabled = enabled,
            currentStateId = machine.currentStateId,
            previousStateId = machine.previousStateId,
            stateAgeMs = (nowMs - machine.stateEntryTimestampMs).coerceAtLeast(0L),
            transitionSequence = machine.transitionSequence,
            candidateTransitionId = machine.candidateTransitionId,
            isFaulted = machine.isFaulted,
            faultReason = machine.faultReason,
            lastRejectionReason = machine.lastRejectionReason,
            lastLifecycleError = machine.lastLifecycleError,
            lifecycleActions = lifecycleActions.toList(),
            ports = ports.map { port ->
                val healthBits = binding.healthBits(port)
                SuperstructurePreviewPort(
                    reference = port.reference,
                    label = port.label,
                    type = port.type,
                    unit = port.unit,
                    numericValue = port.numericValue.takeIf { it.isFinite() },
                    booleanValue = port.booleanValue,
                    stringValue = port.stringValue,
                    ageMs = (nowMs - port.sampleTimestampMs).coerceAtLeast(0L),
                    healthBits = healthBits,
                    condition = port.condition,
                )
            },
        )
    }

    fun tick(deltaMs: Long = 20L): SuperstructurePreviewSnapshot {
        require(deltaMs in 0L..60_000L) { "Preview step must be between 0 and 60000 ms" }
        nowMs += deltaMs
        runtime.readSensors(store, nowMs)
        return snapshot()
    }

    fun request(actionKey: String): SuperstructurePreviewSnapshot {
        store.dispatch(
            SuperstructureRuntime.requestAction(
                robotState = store.state,
                superstructureId = document.superstructureId,
                initialStateId = document.initialStateId,
                actionKey = actionKey,
                timestampMs = nowMs,
            )
        )
        return tick(0L)
    }

    fun setNumeric(reference: SuperstructureFieldReference, value: Double): SuperstructurePreviewSnapshot {
        require(value.isFinite()) { "Preview value must be finite" }
        port(reference).apply { numericValue = value; sampleTimestampMs = nowMs; condition = PreviewPortCondition.HEALTHY; baseHealthBits = healthyBaseBits() }
        return tick(0L)
    }

    fun setBoolean(reference: SuperstructureFieldReference, value: Boolean): SuperstructurePreviewSnapshot {
        port(reference).apply { booleanValue = value; sampleTimestampMs = nowMs; condition = PreviewPortCondition.HEALTHY; baseHealthBits = healthyBaseBits() }
        return tick(0L)
    }

    fun setString(reference: SuperstructureFieldReference, value: String): SuperstructurePreviewSnapshot {
        port(reference).apply { stringValue = value; sampleTimestampMs = nowMs; condition = PreviewPortCondition.HEALTHY; baseHealthBits = healthyBaseBits() }
        return tick(0L)
    }

    fun inject(reference: SuperstructureFieldReference, condition: PreviewPortCondition): SuperstructurePreviewSnapshot {
        val port = port(reference)
        port.condition = condition
        port.baseHealthBits = when (condition) {
            PreviewPortCondition.HEALTHY, PreviewPortCondition.STALE -> healthyBaseBits()
            PreviewPortCondition.INVALID -> healthyBaseBits() and SuperstructurePortHealthBits.VALID.inv()
            PreviewPortCondition.UNCONFIGURED -> healthyBaseBits() and SuperstructurePortHealthBits.CONFIGURED.inv()
            PreviewPortCondition.UNHOMED_OR_UNCALIBRATED -> healthyBaseBits() and
                SuperstructurePortHealthBits.HOMED.inv() and SuperstructurePortHealthBits.CALIBRATED.inv()
            PreviewPortCondition.CURRENT_INVALID -> healthyBaseBits() and SuperstructurePortHealthBits.CURRENT_VALID.inv()
            PreviewPortCondition.OUTPUT_FAULT -> healthyBaseBits() and SuperstructurePortHealthBits.OUTPUT_HEALTHY.inv()
        }
        if (condition == PreviewPortCondition.STALE) port.sampleTimestampMs = nowMs - port.maxAgeMs - 1L
        else port.sampleTimestampMs = nowMs
        return tick(0L)
    }

    private fun port(reference: SuperstructureFieldReference): Port = ports[requireNotNull(indexByKey[reference.subsystemUid to reference.fieldUid])]

    private fun machineState(): SuperstructureRuntimeState = SuperstructureRuntime.state(
        store.state,
        document.superstructureId,
        document.initialStateId,
    )

    private inner class PreviewBinding : SuperstructureRuntimeBinding {
        override fun isRobotEnabled(): Boolean = enabled
        override fun resolvePort(subsystemUid: String, fieldUid: String): Int = indexByKey[subsystemUid to fieldUid] ?: -1
        override fun portType(port: Int): SubsystemValueType? = ports.getOrNull(port)?.type
        override fun readNumeric(port: Int, state: RobotState): Double = ports.getOrNull(port)?.numericValue ?: Double.NaN
        override fun readBoolean(port: Int, state: RobotState): Boolean? = ports.getOrNull(port)?.booleanValue
        override fun readString(port: Int, state: RobotState): String? = ports.getOrNull(port)?.stringValue
        override fun readHealthBits(port: Int, state: RobotState, nowMs: Long): Int = ports.getOrNull(port)?.let(::healthBits) ?: 0

        fun healthBits(port: Port): Int {
            val fresh = nowMs >= port.sampleTimestampMs && nowMs - port.sampleTimestampMs <= port.maxAgeMs
            return if (fresh) port.baseHealthBits or SuperstructurePortHealthBits.FRESH else port.baseHealthBits
        }

        override fun createDoubleTargetTask(port: Int, value: Double): Task? = targetTask(port) { numericValue = value }
        override fun createIntTargetTask(port: Int, value: Int): Task? = targetTask(port) { numericValue = value.toDouble() }
        override fun createBooleanTargetTask(port: Int, value: Boolean): Task? = targetTask(port) { booleanValue = value }
        override fun createStringTargetTask(port: Int, value: String): Task? = targetTask(port) { stringValue = value }
        override fun createLifecycleActionTask(actionKey: String, timestampMs: Long): Task = immediateTask("Lifecycle($actionKey)") {
            lifecycleActions += actionKey
        }

        private fun targetTask(port: Int, apply: Port.() -> Unit): Task? = ports.getOrNull(port)?.let { target ->
            immediateTask("PreviewTarget(${target.label})") { target.apply() }
        }
    }

    private fun immediateTask(name: String, action: () -> Unit): Task = object : Task {
        override val name: String = name
        override fun initialize(state: RobotState): List<RobotAction> {
            super.initialize(state)
            action()
            return emptyList()
        }
        override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = true
    }

    private companion object {
        fun healthyBaseBits(): Int = SuperstructurePortHealthBits.CONTROL_READY_MASK and SuperstructurePortHealthBits.FRESH.inv()

        fun referencedPorts(document: SuperstructureDocument): List<SuperstructureFieldReference> {
            val references = linkedMapOf<Pair<String, String>, SuperstructureFieldReference>()
            fun add(reference: SuperstructureFieldReference?) {
                if (reference != null) references.putIfAbsent(reference.subsystemUid to reference.fieldUid, reference)
            }
            document.states.forEach { state -> state.subsystemTargets.forEach { target -> add(target.target); add(target.source) } }
            document.transitions.forEach { edge -> edge.guards.forEach { add(it.source) } }
            document.interlocks.forEach { add(it.primary); add(it.constrained) }
            document.healthFallbacks.forEach { add(it.source) }
            return references.values.toList()
        }
    }
}
