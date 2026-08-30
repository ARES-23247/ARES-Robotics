package com.areslib.codegen

import com.areslib.subsystem.SubsystemPlatform

enum class GeneratedSubsystemSourceSet { MAIN, TEST }

enum class SubsystemArtifactGroup { DOMAIN, CONTROL, HARDWARE, SIMULATION, GENERATED_PLUMBING, VERIFICATION }

enum class SubsystemArtifact {
    DEFINITION,
    STATE,
    IO_CONTRACT,
    CONTROLLER,
    SUBSYSTEM_LIFECYCLE,
    PLATFORM_IO,
    MOCK_IO,
    CONTRACT_TEST,
    REGISTRY,
}

enum class SubsystemArtifactOwnership { USER_OWNED, GENERATED_STARTER, GENERATED_DO_NOT_EDIT }

data class GeneratedSubsystemFile(
    val relativePath: String,
    val content: String,
    val sourceSet: GeneratedSubsystemSourceSet = GeneratedSubsystemSourceSet.MAIN,
    val artifact: SubsystemArtifact,
    val group: SubsystemArtifactGroup,
    val ownership: SubsystemArtifactOwnership,
    val description: String,
)

data class SubsystemKotlinCodegenTarget(
    val platform: SubsystemPlatform,
    val basePackage: String,
)
