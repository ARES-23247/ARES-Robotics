package com.areslib.subsystem

/** Implementation ownership, linkage, and simulator-contract validation. */
internal object SubsystemImplementationValidation {
    fun validateImplementation(
        document: SubsystemDocument,
        issue: (path: String, message: String) -> Unit,
    ) {
        val implementation = document.implementation
        val duplicateSourceFiles = duplicateSubsystemIds(implementation.sourceFiles)
        duplicateSourceFiles.forEach { issue("implementation.sourceFiles", "Source file '$it' is duplicated") }
        implementation.sourceFiles.forEachIndexed { index, path ->
            if (!path.isSafeSubsystemProjectRelativeKotlinPath()) {
                issue(
                    "implementation.sourceFiles[$index]",
                    "Source files must be normalized project-relative Kotlin paths",
                )
            }
        }
        implementation.modulePath?.let { modulePath ->
            if (!modulePath.matches(SUBSYSTEM_GRADLE_MODULE_PATH)) {
                issue("implementation.modulePath", "Module path must be a Gradle project path such as ':TeamCode'")
            }
        }
        listOf(
            "subsystemClassName" to implementation.subsystemClassName,
            "ioContractClassName" to implementation.ioContractClassName,
            "hardwareAdapterClassName" to implementation.hardwareAdapterClassName,
            "simulation.adapterClassName" to implementation.simulation.adapterClassName,
        ).forEach { (field, className) ->
            if (className != null && !className.matches(SUBSYSTEM_QUALIFIED_KOTLIN_NAME)) {
                issue("implementation.$field", "Class name must be a fully qualified Kotlin name")
            }
        }
        val teaching = implementation.teaching
        if (teaching.documentationPath != null && !teaching.documentationPath.isSafeSubsystemProjectRelativePath()) {
            issue("implementation.teaching.documentationPath", "Documentation must use a normalized project-relative path")
        }
        if (teaching.summary.isBlank() && teaching.documentationPath != null) {
            issue("implementation.teaching.summary", "A documented teaching example requires a short summary")
        }
        teaching.concepts.forEachIndexed { index, concept ->
            if (concept.isBlank()) issue("implementation.teaching.concepts[$index]", "Teaching concepts cannot be blank")
        }
        duplicateSubsystemIds(teaching.concepts).forEach {
            issue("implementation.teaching.concepts", "Teaching concept '$it' is duplicated")
        }
        duplicateSubsystemIds(document.capabilityActionKeys).forEach {
            issue("capabilityActionKeys", "Capability action '$it' is duplicated")
        }
        document.capabilityActionKeys.forEachIndexed { index, key ->
            if (!key.matches(SUBSYSTEM_CAPABILITY_KEY)) {
                issue("capabilityActionKeys[$index]", "Capability action key '$key' is invalid")
            }
        }
    
        when (implementation.kind) {
            SubsystemImplementationKind.DECLARATIVE_GENERATED -> {
                if (implementation.ownership != SubsystemSourceOwnership.GENERATED_DO_NOT_EDIT) {
                    issue("implementation.ownership", "Declarative generated runtimes must use GENERATED_DO_NOT_EDIT ownership")
                }
                if (implementation.modulePath != null || implementation.sourceFiles.isNotEmpty() ||
                    implementation.subsystemClassName != null || implementation.ioContractClassName != null ||
                    implementation.hardwareAdapterClassName != null
                ) {
                    issue("implementation", "Declarative generated source locations come from the Gradle generated-source target")
                }
                if (!document.generateMockIo) {
                    issue("generateMockIo", "Declarative generated subsystems require a simulator/mock adapter")
                }
                if (!document.generateTest) {
                    issue("generateTest", "Declarative generated subsystems require baseline generated safety verification")
                }
                if (implementation.simulation.support != SubsystemSimulationSupport.GENERATED_MOCK ||
                    implementation.simulation.adapterClassName != null
                ) {
                    issue(
                        "implementation.simulation",
                        "Declarative generated subsystems require the generated simulator/mock contract",
                    )
                }
                if (document.capabilityActionKeys.isNotEmpty()) {
                    issue("capabilityActionKeys", "Declarative generated actions are derived from target state fields")
                }
            }
    
            SubsystemImplementationKind.GENERATED_STARTER -> {
                if (implementation.ownership != SubsystemSourceOwnership.GENERATED_STARTER) {
                    issue("implementation.ownership", "Generated starters must use GENERATED_STARTER ownership")
                }
                if (implementation.modulePath != null || implementation.sourceFiles.isNotEmpty() ||
                    implementation.subsystemClassName != null || implementation.ioContractClassName != null ||
                    implementation.hardwareAdapterClassName != null
                ) {
                    issue("implementation", "Generated starter source locations come from the code-generation target")
                }
                val expectedSimulation = if (document.generateMockIo) {
                    SubsystemSimulationSupport.GENERATED_MOCK
                } else {
                    SubsystemSimulationSupport.UNAVAILABLE
                }
                if (implementation.simulation.support != expectedSimulation ||
                    implementation.simulation.adapterClassName != null
                ) {
                    issue(
                        "implementation.simulation",
                        "Generated starter simulation metadata must match generateMockIo",
                    )
                }
                if (document.capabilityActionKeys.isNotEmpty()) {
                    issue("capabilityActionKeys", "Generated starter actions are derived from target state fields")
                }
            }
    
            SubsystemImplementationKind.HAND_AUTHORED -> {
                if (implementation.ownership != SubsystemSourceOwnership.USER_OWNED) {
                    issue("implementation.ownership", "Hand-authored Kotlin must use USER_OWNED ownership")
                }
                if (implementation.modulePath == null) {
                    issue("implementation.modulePath", "Hand-authored subsystems require an owning Gradle module")
                }
                if (implementation.sourceFiles.isEmpty()) {
                    issue("implementation.sourceFiles", "Hand-authored subsystems require at least one user-owned source file")
                }
                listOf(
                    "subsystemClassName" to implementation.subsystemClassName,
                    "ioContractClassName" to implementation.ioContractClassName,
                    "hardwareAdapterClassName" to implementation.hardwareAdapterClassName,
                ).forEach { (field, className) ->
                    if (className == null) issue("implementation.$field", "Hand-authored subsystems must name this runtime type")
                }
                if (document.generateMockIo || document.generateTest) {
                    issue(
                        "implementation",
                        "Hand-authored descriptors cannot request generated starter or test files",
                    )
                }
                when (implementation.simulation.support) {
                    SubsystemSimulationSupport.GENERATED_MOCK -> issue(
                        "implementation.simulation.support",
                        "Hand-authored subsystems cannot claim a generated mock",
                    )
                    SubsystemSimulationSupport.HAND_AUTHORED_MOCK,
                    SubsystemSimulationSupport.HAND_AUTHORED_SIMULATOR -> if (implementation.simulation.adapterClassName == null) {
                        issue("implementation.simulation.adapterClassName", "Available simulation support requires its adapter class")
                    }
                    SubsystemSimulationSupport.UNAVAILABLE -> if (implementation.simulation.adapterClassName != null) {
                        issue("implementation.simulation.adapterClassName", "Unavailable simulation support cannot name an adapter")
                    }
                }
            }
        }
    }
    
    fun validateLinkage(document: SubsystemDocument, issue: (String, String) -> Unit) {
        if (!document.linkage.enabled) return
        val path = "linkage"
        val linkage = document.linkage
        val finiteValues = listOf(
            linkage.link1LengthMeters,
            linkage.link2LengthMeters,
            linkage.link1MassKg,
            linkage.link2MassKg,
            linkage.link1CenterOfMassMeters,
            linkage.link2CenterOfMassMeters,
            linkage.joint1MinRad,
            linkage.joint1MaxRad,
            linkage.joint2MinRad,
            linkage.joint2MaxRad,
            linkage.joint1TorquePerVoltNm,
            linkage.joint2TorquePerVoltNm,
            linkage.joint1DampingNmPerRadPerSec,
            linkage.joint2DampingNmPerRadPerSec,
        )
        if (finiteValues.any { !it.isFinite() }) issue(path, "Every linkage geometry, mass, and limit value must be finite")
        if (linkage.link1LengthMeters <= 0.0) issue("$path.link1LengthMeters", "Link 1 length must be positive")
        if (linkage.link2LengthMeters <= 0.0) issue("$path.link2LengthMeters", "Link 2 length must be positive")
        if (linkage.link1MassKg <= 0.0) issue("$path.link1MassKg", "Link 1 mass must be positive for dynamics simulation")
        if (linkage.link2MassKg <= 0.0) issue("$path.link2MassKg", "Link 2 mass must be positive for dynamics simulation")
        if (linkage.link1CenterOfMassMeters !in 0.0..linkage.link1LengthMeters) {
            issue("$path.link1CenterOfMassMeters", "Link 1 center of mass must lie on link 1")
        }
        if (linkage.link2CenterOfMassMeters !in 0.0..linkage.link2LengthMeters) {
            issue("$path.link2CenterOfMassMeters", "Link 2 center of mass must lie on link 2")
        }
        if (document.linkage.joint1MinRad >= document.linkage.joint1MaxRad) {
            issue("$path.joint1MinRad", "Joint 1 minimum angle must be less than maximum angle")
        }
        if (document.linkage.joint2MinRad >= document.linkage.joint2MaxRad) {
            issue("$path.joint2MinRad", "Joint 2 minimum angle must be less than maximum angle")
        }
        val fieldsById = document.stateFields.associateBy { it.fieldId }
        listOf(
            "joint1AngleFieldId" to linkage.joint1AngleFieldId,
            "joint2AngleFieldId" to linkage.joint2AngleFieldId,
        ).forEach { (name, id) ->
            val field = id?.let(fieldsById::get)
            if (field == null || field.type != SubsystemValueType.DOUBLE || field.role != SubsystemFieldRole.MEASUREMENT) {
                issue("$path.$name", "Each linkage joint requires a double measurement state field in radians")
            }
        }
        val hardwareById = document.hardware.associateBy { it.hardwareId }
        listOf(
            "joint1ActuatorId" to linkage.joint1ActuatorId,
            "joint2ActuatorId" to linkage.joint2ActuatorId,
        ).forEach { (name, id) ->
            val actuator = id?.let(hardwareById::get)
            if (actuator == null || actuator.kind != SubsystemHardwareKind.MOTOR || actuator.following != null) {
                issue("$path.$name", "Each linkage joint requires an independently controlled motor actuator")
            }
        }
        if (linkage.joint1ActuatorId != null && linkage.joint1ActuatorId == linkage.joint2ActuatorId) {
            issue(path, "Linkage joints must use distinct actuators")
        }
        if (linkage.joint1TorquePerVoltNm <= 0.0 || linkage.joint2TorquePerVoltNm <= 0.0) {
            issue(path, "Each linkage joint requires a positive torque-per-volt simulation constant")
        }
        if (linkage.joint1DampingNmPerRadPerSec < 0.0 || linkage.joint2DampingNmPerRadPerSec < 0.0) {
            issue(path, "Linkage damping values cannot be negative")
        }
    }
    
    fun validateSimInteraction(document: SubsystemDocument, issue: (String, String) -> Unit) {
        val interaction = document.implementation.simulation.interaction
        if (interaction.role == SimInteractionRole.NONE) return
        val path = "implementation.simulation.interaction"
        val trigger = interaction.triggerActuatorId?.let { id -> document.hardware.singleOrNull { it.hardwareId == id } }
        if (trigger == null || trigger.kind !in SUBSYSTEM_ACTUATOR_KINDS || trigger.following != null) {
            issue("$path.triggerActuatorId", "Field interaction requires an independently controlled actuator output")
        }
        if (interaction.storageCapacity < 1) issue("$path.storageCapacity", "Storage capacity must be at least 1")
        if (interaction.intakeDistanceMeters <= 0.0) issue("$path.intakeDistanceMeters", "Intake distance must be positive")
        if (interaction.captureRadiusMeters <= 0.0) issue("$path.captureRadiusMeters", "Capture radius must be positive")
        if (interaction.launchSpeedMps <= 0.0) issue("$path.launchSpeedMps", "Launch speed must be positive")
        if (interaction.launchElevationDeg !in 0.0..90.0) issue("$path.launchElevationDeg", "Launch elevation must be between 0 and 90 degrees")
    }

    private fun duplicateSubsystemIds(ids: List<String>): Set<String> {
        val seen = hashSetOf<String>()
        return ids.filterNot(seen::add).toSet()
    }

    private val SUBSYSTEM_ACTUATOR_KINDS = setOf(
        SubsystemHardwareKind.MOTOR,
        SubsystemHardwareKind.POSITIONAL_SERVO,
        SubsystemHardwareKind.CONTINUOUS_SERVO,
        SubsystemHardwareKind.INDICATOR_LIGHT,
        SubsystemHardwareKind.PRISM_DRIVER,
        SubsystemHardwareKind.SOLENOID,
    )
}

