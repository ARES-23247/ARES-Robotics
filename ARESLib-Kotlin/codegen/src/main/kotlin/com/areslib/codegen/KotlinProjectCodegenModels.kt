package com.areslib.codegen

import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.RoutineDocument
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.compiler.RobotProjectIr
import com.areslib.subsystem.SubsystemTargetCapability

/** Renderer layout applied only after an effective project has been lowered to typed compiler IR. */
data class KotlinProjectCompilationRequest(
    val project: RobotProjectIr,
    val packageName: String,
    val objectName: String = "GeneratedAresProject",
    val registryInterfaceName: String = "GeneratedAresProjectCapabilities",
    /** Fully-qualified generated registry that creates the corresponding Redux tasks. */
    val subsystemRegistryFqn: String? = null,
    /** Parameterless action keys implemented by generated orchestration registries. */
    val generatedActionRegistryBindings: Map<String, String> = emptyMap(),
)

/** Internal renderer input retained while focused renderers are extracted from the legacy file. */
internal data class KotlinProjectCodegenRequest(
    val packageName: String,
    val objectName: String = "GeneratedAresProject",
    val registryInterfaceName: String = "GeneratedAresProjectCapabilities",
    val catalog: CapabilityCatalogDocument,
    val routines: Collection<RoutineDocument>,
    val autonomousCatalog: AutonomousCatalogDocument? = null,
    val controlSchemes: Collection<ControlSchemeDocument> = emptyList(),
    val controllerProfiles: Collection<ControllerProfileDocument> = emptyList(),
    /** Robot-side InputFrame adapter whose learned HID indexes must be emitted. */
    val targetInputPlatform: ControllerInputPlatform? = null,
    /** Canonical project geometry. Build-time CLI projects require `.ares/project.json`. */
    val projectMetadata: AresProjectMetadataDocument? = null,
    /** Target setters derived from subsystem documents rather than hand-authored catalog entries. */
    val subsystemActions: Collection<SubsystemTargetCapability> = emptyList(),
    /** Fully-qualified generated registry that creates the corresponding Redux tasks. */
    val subsystemRegistryFqn: String? = null,
    /** Parameterless action keys implemented by generated orchestration registries. */
    val generatedActionRegistryBindings: Map<String, String> = emptyMap(),
)

/** Generated source and the hashes a build can use to verify deterministic disposable output. */
data class GeneratedKotlinSource(
    val source: String,
    val contentHash: String,
    val sourceHash: String
)
