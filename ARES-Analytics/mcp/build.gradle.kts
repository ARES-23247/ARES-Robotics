import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

val aresVersion = rootProject.extra["aresVersion"] as String
val studioVersion = rootProject.extra["aresStudioVersion"] as String
val ftcStarterVersion = rootProject.extra["aresFtcStarterVersion"] as String
val frcStarterVersion = rootProject.extra["aresFrcStarterVersion"] as String
val xrpStarterVersion = rootProject.extra["aresXrpStarterVersion"] as String

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

    implementation("io.ktor:ktor-client-cio:3.6.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.1.202611041320-r")
    implementation("ch.qos.logback:logback-classic:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

// cmd.exe expands APP_HOME for every jar. Java expands lib/* without the batch line limit.
tasks.startScripts {
    doLast {
        val script = windowsScript
        val lines = script.readLines()
        require(lines.count { it.startsWith("set CLASSPATH=") } == 1)
        script.writeText(lines.joinToString("\r\n", postfix = "\r\n") {
            if (it.startsWith("set CLASSPATH=")) "set CLASSPATH=%APP_HOME%\\lib\\*" else it
        })
    }
}
