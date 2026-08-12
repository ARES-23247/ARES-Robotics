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
    implementation("com.google.code.gson:gson:2.10.1")
    api("org.java-websocket:Java-WebSocket:1.5.3") // transitive dep of NT4Server (extends WebSocketServer)
    implementation("org.msgpack:msgpack-core:0.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
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
                "com/areslib/codegen/SubsystemKotlinGenerator.kt",
                "com/areslib/codegen/SubsystemStarterReconciler.kt",
            )
        }
        test {
            kotlin.exclude("com/areslib/codegen/**")
        }
    }
}
