import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}

description = "ARES project, autonomous, controls, and subsystem Kotlin code generation tools."

dependencies {
    api(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.setSrcDirs(listOf("../core/src/main/kotlin"))
            kotlin.include(
                "com/areslib/codegen/AresKotlinProjectGenerator.kt",
                "com/areslib/codegen/AresProjectCodegenCli.kt",
                "com/areslib/codegen/FtcStarterContractMigration.kt",
                "com/areslib/codegen/SubsystemKotlinGenerator.kt",
                "com/areslib/codegen/SubsystemStarterReconciler.kt",
                "com/areslib/codegen/SuperstructureKotlinGenerator.kt",
            )
        }
        test {
            kotlin.setSrcDirs(listOf("../core/src/test/kotlin"))
            kotlin.include("com/areslib/codegen/**")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
