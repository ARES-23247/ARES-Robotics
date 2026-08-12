import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    kotlin("jvm")
    application
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}

description = "Platform-neutral ARES desktop physics simulator and FTC OpMode runner."

repositories {
    mavenCentral()
    maven("https://frcmaven.wpi.edu/artifactory/release/")
}

application {
    mainClass.set("com.areslib.sim.DesktopSimLauncher")
}

dependencies {
    api(project(":core"))
    api(project(":ftc-hardware"))
    api(project(":ftc-mocks"))
    
    // Dyn4j Physics Engine
    implementation("org.dyn4j:dyn4j:4.2.2")

    // JSON Parser
    implementation("com.google.code.gson:gson:2.10.1")

    // Java APIs are platform-neutral. JNI and LWJGL natives are supplied by one of the
    // simulator-runtime-{windows,linux,macos} artifacts.
    val wpiVersion = "2024.3.2"

    implementation("edu.wpi.first.wpilibj:wpilibj-java:$wpiVersion")
    implementation("edu.wpi.first.cameraserver:cameraserver-java:$wpiVersion")
    implementation("edu.wpi.first.wpinet:wpinet-java:$wpiVersion")
    implementation("edu.wpi.first.ntcore:ntcore-java:$wpiVersion")
    implementation("edu.wpi.first.wpiutil:wpiutil-java:$wpiVersion")
    implementation("edu.wpi.first.wpimath:wpimath-java:$wpiVersion")
    implementation("edu.wpi.first.hal:hal-java:$wpiVersion")
    
    // Slf4j for logging (optional, usually good to have)
    implementation("org.slf4j:slf4j-simple:2.0.12")
    
    // LWJGL Core
    implementation("org.lwjgl:lwjgl:3.3.3")
    
    // LWJGL GLFW for robust cross-platform Gamepad support (auto-extracts natives)
    implementation("org.lwjgl:lwjgl-glfw:3.3.3")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

val hostRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Host-only natives for local simulator execution and tests; never published."
}

val hostOs = System.getProperty("os.name").lowercase()
val hostWpiPlatform = when {
    hostOs.contains("windows") -> "windowsx86-64"
    hostOs.contains("mac") -> "osxuniversal"
    hostOs.contains("linux") -> "linuxx86-64"
    else -> error("Unsupported simulator host: ${System.getProperty("os.name")}")
}
val hostLwjglClassifier = when {
    hostOs.contains("windows") -> "natives-windows"
    hostOs.contains("mac") -> "natives-macos"
    hostOs.contains("linux") -> "natives-linux"
    else -> error("Unsupported simulator host: ${System.getProperty("os.name")}")
}
val hostWpiVersion = "2024.3.2"

dependencies {
    listOf("wpinet", "ntcore", "wpiutil", "wpimath", "hal").forEach { module ->
        add(hostRuntime.name, "edu.wpi.first.$module:$module-jni:$hostWpiVersion:$hostWpiPlatform")
    }
    add(hostRuntime.name, "org.lwjgl:lwjgl:3.3.3:$hostLwjglClassifier")
    add(hostRuntime.name, "org.lwjgl:lwjgl-glfw:3.3.3:$hostLwjglClassifier")
}

configurations.testRuntimeClasspath {
    extendsFrom(hostRuntime)
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin.srcDirs("src/main/kotlin")
        }
    }
}

val javaToolchains = project.extensions.getByType<JavaToolchainService>()

tasks.named<JavaExec>("run") {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
    classpath += hostRuntime
    if (project.hasProperty("appArgs")) {
        args(project.property("appArgs").toString().split(" "))
    }
}

tasks.register<JavaExec>("runFakeController") {
    group = "application"
    mainClass.set("com.areslib.sim.infra.FakeControllerClient")
    classpath = sourceSets.main.get().runtimeClasspath + hostRuntime
    standardInput = System.`in`
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

tasks.register<JavaExec>("runVerification") {
    group = "application"
    mainClass.set("com.areslib.sim.VerificationAppKt")
    classpath = sourceSets.main.get().runtimeClasspath + hostRuntime
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

tasks.register<Jar>("fatJar") {
    group = "build"
    manifest {
        attributes["Main-Class"] = "com.areslib.sim.DesktopSimLauncher"
    }
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath, hostRuntime)
    from({
        (configurations.runtimeClasspath.get() + hostRuntime).filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })
}
