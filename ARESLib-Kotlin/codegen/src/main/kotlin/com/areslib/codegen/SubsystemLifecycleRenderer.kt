package com.areslib.codegen

import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFieldRole

/** Renders the subsystem lifecycle bridge between cached IO, Redux, control, and cleanup. */
internal object SubsystemLifecycleRenderer {
    fun render(document: SubsystemDocument, pkg: String): String {
        val copies = document.hardware.flatMap { it.measurements }.mapNotNull { measurement ->
            val field = document.field(measurement.fieldId) ?: return@mapNotNull null
            "            ${field.fieldId} = io.${field.fieldId}"
        }.distinct().joinToString(",\n")
        val setters = document.stateFields.filter { it.role == SubsystemFieldRole.TARGET }.joinToString("\n\n") { field ->
            val cap = field.fieldId.pascalCase()
            val nextCommand = if (document.hasSafetyRequestHandshake()) {
                """
        val nextCommandSequence = if (current.commandSequence == Long.MAX_VALUE) 1L else current.commandSequence + 1L
        store.dispatch(RobotAction.UpdateNamedSubsystemState(
            ID,
            current.copy(${field.fieldId} = value, commandSequence = nextCommandSequence),
        ))
                """.trimIndent()
            } else {
                "store.dispatch(RobotAction.UpdateNamedSubsystemState(ID, current.copy(${field.fieldId} = value)))"
            }
            """    fun set$cap(store: Store, value: ${field.kotlinType()}) {
        val current = state(store.state)
        $nextCommand
    }"""
        }
        val feedbackTimeoutMs = document.safety.feedbackTimeoutMs ?: Long.MAX_VALUE
        val registryPackage = pkg.substringBeforeLast('.')
        val interlockPermit = if (document.interlocks.isEmpty()) {
            "true"
        } else {
            "GeneratedSubsystemRegistry.interlocksPermit${document.kotlinTypeName}(state)"
        }
        return """
            package $pkg

            import com.areslib.Store
            import com.areslib.action.RobotAction
            import com.areslib.state.RobotState
            import com.areslib.subsystem.Subsystem
            import com.areslib.tuning.TuningValue
            import com.areslib.tuning.TypedTuningConsumer
            import $registryPackage.GeneratedSubsystemRegistry

            /** Robot-loop host. Hardware reads, Redux updates, and output writes remain separated. */
            class ${document.kotlinTypeName}Subsystem(private val io: ${document.kotlinTypeName}IO) : Subsystem, TypedTuningConsumer {
                private val controller = ${document.kotlinTypeName}Controller(io)

                /** Copies the already-refreshed hardware snapshot into immutable Redux state. */
                override fun readSensors(store: Store, timestampMs: Long) {
                    val snapshotAgeMs = if (timestampMs >= io.feedbackTimestampMs) {
                        timestampMs - io.feedbackTimestampMs
                    } else {
                        Long.MAX_VALUE
                    }
                    val updated = state(store.state).copy(
            $copies${if (copies.isBlank()) "" else ","}
                        feedbackValid = io.feedbackValid && snapshotAgeMs <= ${feedbackTimeoutMs}L,
                        feedbackTimestampMs = io.feedbackTimestampMs,
                        configurationHealthy = io.configurationHealthy,
                        homed = io.homed,
                        homingFaultLatched = io.homingFaultLatched,
                        calibrated = io.calibrated,
                        currentReadingValid = io.currentReadingValid,
                        outputFaultLatched = io.outputFaultLatched,
                    )
                    store.dispatch(RobotAction.UpdateNamedSubsystemState(ID, updated, timestampMs))
                }

                /** Applies immutable state to IO through the safety-gated controller. */
                override fun writeOutputs(state: RobotState, scale: Double) {
                    controller.update(state(state), scale, $interlockPermit)
                }

                override fun supportsTuningParameter(parameterUid: String): Boolean =
                    controller.supportsTuningParameter(parameterUid)

                override fun applyTuningParameter(parameterUid: String, value: TuningValue): Boolean =
                    controller.applyTuningParameter(parameterUid, value)

            $setters

                /** Resets controller history, commands neutral, and releases owned IO idempotently. */
                override fun close() {
                    controller.reset()
                    io.safe()
                    io.close()
                }

                companion object {
                    const val ID: String = ${document.documentId.quoted()}

                    fun state(robotState: RobotState): ${document.kotlinTypeName}State =
                        robotState.superstructure.subsystems[ID] as? ${document.kotlinTypeName}State ?: ${document.kotlinTypeName}State()
                }
            }
        """.trimIndent() + "\n"
    }
}

