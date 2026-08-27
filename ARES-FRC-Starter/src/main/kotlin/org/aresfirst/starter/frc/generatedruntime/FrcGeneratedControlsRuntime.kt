package org.aresfirst.starter.frc.generatedruntime

import com.areslib.action.RobotAction
import com.areslib.frc.runtime.FrcGeneratedProjectControlsRuntime
import com.areslib.routine.RoutineRequestResult
import com.areslib.routine.RoutineStartPolicy
import com.areslib.state.RobotState
import org.aresfirst.starter.frc.generated.GeneratedAresProject
import org.aresfirst.starter.frc.generated.GeneratedAresProjectCapabilities

internal typealias FrcControllerPortSampler = com.areslib.frc.runtime.FrcControllerPortSampler
internal typealias WpilibFrcControllerPortSampler = com.areslib.frc.runtime.WpilibFrcControllerPortSampler

/** Starter adapter; reusable WPILib scheduling lives in the ARES FRC runtime artifact. */
internal class FrcGeneratedControlsRuntime(
    stateProvider: () -> RobotState,
    dispatch: (RobotAction) -> Unit,
    capabilities: GeneratedAresProjectCapabilities,
    portSampler: FrcControllerPortSampler = com.areslib.frc.runtime.WpilibFrcControllerPortSampler(),
    driveEmissionGate: () -> Boolean = { true },
) {
    private val delegate = FrcGeneratedProjectControlsRuntime(
        definition = GeneratedAresProject.runtimeDefinition,
        stateProvider = stateProvider,
        dispatch = dispatch,
        capabilities = capabilities,
        portSampler = portSampler,
        driveEmissionGate = driveEmissionGate,
    )

    fun update() = delegate.update()

    fun requestRoutine(
        routineId: String,
        policy: RoutineStartPolicy = RoutineStartPolicy.RESTART_EXISTING,
    ): RoutineRequestResult = delegate.requestRoutine(routineId, policy)

    fun updateRoutines() = delegate.updateRoutines()

    fun cancelAll(reason: String) = delegate.cancelAll(reason)

    val controlsSource: String
        get() = delegate.controlsSource

    internal val activeControllerPortCount: Int
        get() = delegate.activeControllerPortCount
}
