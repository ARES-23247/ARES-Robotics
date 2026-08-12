import java.io.File

plugins {
    kotlin("jvm")
    id("edu.wpi.first.GradleRIO") version "2026.2.1"
    `maven-publish`
}

group = "com.areslib"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":core"))

    // WPILib dependencies list iteration for Kotlin DSL
    wpi.java.deps.wpilib().forEach { dep ->
        implementation(dep)
    }
    
    // Vendor dependencies list iteration
    wpi.java.vendor.java().forEach { dep ->
        implementation(dep)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Add WPILib native JNI dependencies for desktop test runs
    val desktopPlatform = wpi.platforms.javaClass.getField("desktop").get(wpi.platforms) as String
    wpi.java.deps.wpilibJniRelease(desktopPlatform).forEach { dep ->
        testRuntimeOnly(dep)
    }
    wpi.java.vendor.jniRelease(desktopPlatform).forEach { dep ->
        testRuntimeOnly(dep)
    }
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "com.areslib"
            artifactId = "frc-hardware"
            version = "1.0-SNAPSHOT"
        }
    }
}

val extractTestNatives by tasks.registering(Copy::class) {
    dependsOn(configurations.testRuntimeClasspath)
    from(configurations.testRuntimeClasspath.get().map { 
        if (it.isDirectory) it else if (it.name.endsWith(".zip") || it.name.endsWith(".jar")) zipTree(it) else it
    })
    into(layout.buildDirectory.dir("jni/release"))
    include("**/*.dll", "**/*.so", "**/*.dylib")
    eachFile {
        relativePath = RelativePath(true, name)
    }
    includeEmptyDirs = false
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    dependsOn(extractTestNatives)
    useJUnitPlatform()
    
    // Configure test task to run using WPILib's compatible JDK to avoid JNI loader MSVC runtime crashes
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        val wpilibJdk = File("C:/Users/Public/wpilib/2026/jdk/bin/java.exe")
        if (wpilibJdk.isFile) {
            executable = wpilibJdk.absolutePath
        }
    }

    // Set the library path to the extracted native binaries directory
    val jniPath = layout.buildDirectory.dir("jni/release").get().asFile.absolutePath
    systemProperty("java.library.path", jniPath)
    
    // Prepend the native binaries directory to PATH so Windows can resolve transitive DLL dependencies
    environment("PATH", "$jniPath${File.pathSeparator}${System.getenv("PATH").orEmpty()}")

    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
