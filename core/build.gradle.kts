import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}

description = "Platform-neutral math, control, state, pathing, telemetry, logging, and robot infrastructure."

repositories {
    mavenCentral()
    maven("https://frcmaven.wpi.edu/artifactory/release/")
    maven("https://maven.ctr-electronics.com/release/")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.google.code.gson:gson:2.14.0")
    api("org.java-websocket:Java-WebSocket:1.6.0") // transitive dep of NT4Server (extends WebSocketServer)
    implementation("org.msgpack:msgpack-core:0.9.12")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.nanohttpd:nanohttpd:2.3.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    include("**/*Test.class", "**/*Tests.class")
}

tasks.register<JavaExec>("fitLocalizationCalibration") {
    group = "verification"
    description = "Fits localization Q/R recommendations and NIS/NEES consistency from robot calibration CSV files"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.areslib.math.estimation.LocalizationCalibrationCli")
    doFirst {
        val input = project.findProperty("calibrationFiles")?.toString()
            ?: error("Pass -PcalibrationFiles=<csv>[|<csv>...]")
        val cliArgs = input.split('|').filter(String::isNotBlank).toMutableList()
        project.findProperty("calibrationOutput")?.toString()?.let {
            cliArgs += "--output"
            cliArgs += it
        }
        args = cliArgs
    }
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.exclude(
                "com/areslib/codegen/AresKotlinProjectGenerator.kt",
                "com/areslib/codegen/AresProjectCodegenCli.kt",
                "com/areslib/codegen/FtcStarterContractMigration.kt",
                "com/areslib/codegen/ProjectVerificationKotlinGenerator.kt",
                "com/areslib/codegen/SubsystemKotlinGenerator.kt",
                "com/areslib/codegen/SubsystemStarterReconciler.kt",
                "com/areslib/codegen/SuperstructureKotlinGenerator.kt",
            )
        }
        test {
            kotlin.exclude(
                "com/areslib/codegen/AresKotlinProjectGeneratorTest.kt",
                "com/areslib/codegen/AresProjectCodegenCliTest.kt",
                "com/areslib/codegen/FtcStarterContractMigrationTest.kt",
                "com/areslib/codegen/ProjectVerificationKotlinGeneratorTest.kt",
                "com/areslib/codegen/SubsystemKotlinGeneratorTest.kt",
                "com/areslib/codegen/RepresentativeZeroCodeRobotTest.kt",
                "com/areslib/codegen/SubsystemStarterReconcilerTest.kt",
                "com/areslib/codegen/SuperstructureKotlinGeneratorTest.kt",
            )
        }
    }
}
