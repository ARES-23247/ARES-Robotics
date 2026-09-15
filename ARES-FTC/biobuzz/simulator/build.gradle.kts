plugins {
    kotlin("jvm")
    application
}

// Keep normal builds tied to this repository's immutable release. A sibling ARESLib checkout is
// used only when the caller explicitly supplies -ParesUseSiblingLib=true.
val aresVersion = rootProject.extra["aresReleaseVersion"] as String
val aresSimulatorRuntime = when {
    System.getProperty("os.name").contains("windows", ignoreCase = true) -> "simulator-runtime-windows"
    System.getProperty("os.name").contains("mac", ignoreCase = true) -> "simulator-runtime-macos"
    else -> "simulator-runtime-linux"
}
val canonicalMonorepoFtcRuntimeDir = rootProject.projectDir.parentFile
    .resolve("templates/ftc/runtime/src/main/kotlin")

dependencies {
    implementation("org.dyn4j:dyn4j:4.2.2")
    implementation("com.google.code.gson:gson:2.14.0")
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
        resources.srcDir("../shared/src/main/resources")
        java.srcDirs(
            "../TeamCode/src/main/java",
            "../TeamCode/build/generated/ares/main/kotlin",
            "../TeamCode/build/generated/ares/drivebase/kotlin",
            "src/main/kotlin",
            "../shared/src/main/kotlin",
        )
        if (canonicalMonorepoFtcRuntimeDir.isDirectory) {
            java.srcDir(canonicalMonorepoFtcRuntimeDir)
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    // Generated robot lifecycle tests must load this exported project's canonical .ares documents.
    workingDir(rootProject.projectDir)
}

val javaToolchains = project.extensions.getByType<JavaToolchainService>()

// The simulator compiles the real editable adapters plus the same disposable registration source
// as the Android app. It must never grow a simulator-only wiring path.
tasks.named("compileKotlin") {
    dependsOn(":TeamCode:prepareAresSubsystemPlumbing")
}

tasks.named<JavaExec>("run") {
    group = "application"
    mainClass.set("org.ares.biobuzz.BiobuzzSimLauncher")
    classpath = sourceSets.main.get().runtimeClasspath
    // Field, mechanism, and controller descriptors belong to this starter checkout. Gradle's
    // default subproject working directory would make the simulator miss them and discover an
    // unrelated developer sibling as a fallback.
    workingDir(rootProject.projectDir)
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
    workingDir(rootProject.projectDir)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

// Optional offline runtime built from this exact example and dependency set.
tasks.register<Jar>("fatJar") {
    dependsOn("classes")
    archiveFileName.set("simulator-all.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "org.ares.biobuzz.BiobuzzSimLauncher" }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
