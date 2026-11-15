import java.util.Properties
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

val versionsPropertiesFile = rootProject.projectDir.parentFile.resolve("release/ares-versions.properties")
val releaseVersions = Properties().apply {
    if (versionsPropertiesFile.isFile) {
        versionsPropertiesFile.inputStream().use(::load)
    }
}

val aresVersion = rootProject.extra["aresVersion"] as String
val studioVersion = releaseVersions.getProperty("studioVersion")?.trim().orEmpty().ifEmpty { "7.0.79" }
val ftcStarterVersion = releaseVersions.getProperty("ftcStarterVersion")?.trim().orEmpty().ifEmpty { "20.0.6" }
val frcStarterVersion = releaseVersions.getProperty("frcStarterVersion")?.trim().orEmpty().ifEmpty { "20.0.6" }
val xrpStarterVersion = releaseVersions.getProperty("xrpStarterVersion")?.trim().orEmpty().ifEmpty { "3.0.70" }

val generatedMcpBuildConfigDir = layout.buildDirectory.dir("generated/source/mcpBuildConfig/main")

val generateMcpBuildConfig = tasks.register("generateMcpBuildConfig") {
    inputs.property("aresVersion", aresVersion)
    inputs.property("studioVersion", studioVersion)
    inputs.property("ftcStarterVersion", ftcStarterVersion)
    inputs.property("frcStarterVersion", frcStarterVersion)
    inputs.property("xrpStarterVersion", xrpStarterVersion)
    outputs.dir(generatedMcpBuildConfigDir)

    doLast {
        val outFile = generatedMcpBuildConfigDir.get().asFile
            .resolve("com/ares/analytics/mcp/McpBuildConfig.kt")
        outFile.parentFile.mkdirs()
        outFile.writeText(
            """
            |package com.ares.analytics.mcp
            |
            |object McpBuildConfig {
            |    const val STUDIO_VERSION: String = "$studioVersion"
            |    const val ARES_VERSION: String = "$aresVersion"
            |    const val FTC_STARTER_VERSION: String = "$ftcStarterVersion"
            |    const val FRC_STARTER_VERSION: String = "$frcStarterVersion"
            |    const val XRP_STARTER_VERSION: String = "$xrpStarterVersion"
            |}
            |
            """.trimMargin(),
        )
    }
}

sourceSets {
    main {
        kotlin.srcDir(generatedMcpBuildConfigDir)
    }
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(generateMcpBuildConfig)
}

application {
    mainClass.set("com.ares.analytics.mcp.AresMcpMainKt")
}

dependencies {
    implementation(project(":shared"))
    implementation(platform("org.aresfirst.ares:ares-bom:$aresVersion"))
    implementation("org.aresfirst.ares:core")
    implementation("org.aresfirst.ares:project-schema")
    implementation("org.aresfirst.ares:project-model")
    implementation("org.aresfirst.ares:project-compiler")
    implementation("org.aresfirst.ares:codegen")

    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.16.0")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.16.0")

    implementation("io.ktor:ktor-client-cio:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
    implementation("ch.qos.logback:logback-classic:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
