import java.util.Properties

plugins {
    kotlin("jvm")
    application
}

val siblingAresProps = file("../../ARESLib-Kotlin/gradle.properties")
val siblingAresVersion = if (siblingAresProps.exists()) {
    val props = Properties().apply { siblingAresProps.inputStream().use { load(it) } }
    props.getProperty("aresVersion")
} else null
val aresVersion = providers.gradleProperty("aresVersion").orElse(providers.provider { siblingAresVersion }).getOrElse("10.1.0")
val aresSimulatorRuntime = when {
    System.getProperty("os.name").contains("windows", ignoreCase = true) -> "simulator-runtime-windows"
    System.getProperty("os.name").contains("mac", ignoreCase = true) -> "simulator-runtime-macos"
    else -> "simulator-runtime-linux"
}

dependencies {
    implementation(platform("org.aresfirst.ares:ares-bom:$aresVersion"))
    implementation("org.aresfirst.ares:core")
    implementation("org.aresfirst.ares:ftc-hardware")
    implementation("org.aresfirst.ares:simulator")
    implementation("org.aresfirst.ares:ftc-mocks")
    runtimeOnly("org.aresfirst.ares:$aresSimulatorRuntime")
    
    val wpiVersion = "2024.3.2"
    implementation("edu.wpi.first.ntcore:ntcore-java:$wpiVersion")
    implementation("edu.wpi.first.wpilibj:wpilibj-java:$wpiVersion")
    implementation("edu.wpi.first.wpiutil:wpiutil-java:$wpiVersion")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

sourceSets {
    main {
        java.srcDirs(
            "../TeamCode/src/main/java",
            "../TeamCode/build/generated/ares/main/kotlin",
            "../TeamCode/build/generated/ares/drivebase/kotlin",
            "src/main/kotlin",
        )
    }
}

kotlin {
    jvmToolchain(21)
}

val javaToolchains = project.extensions.getByType<JavaToolchainService>()

// The simulator compiles the real editable adapters plus the same disposable registration source
// as the Android app. It must never grow a simulator-only wiring path.
tasks.named("compileKotlin") {
    dependsOn(":TeamCode:prepareAresSubsystemPlumbing")
}

tasks.named<JavaExec>("run") {
    group = "application"
    mainClass.set("com.areslib.sim.DesktopSimLauncher")
    classpath = sourceSets.main.get().runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    
    val argsList = mutableListOf<String>()
    if (project.hasProperty("appArgs")) {
        argsList.addAll(project.property("appArgs").toString().split(" "))
    }
    args(argsList)
}

tasks.register<JavaExec>("runCalibrationVerification") {
    group = "application"
    mainClass.set("org.firstinspires.ftc.teamcode.CalibrationVerificationAppKt")
    classpath = sourceSets.main.get().runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
