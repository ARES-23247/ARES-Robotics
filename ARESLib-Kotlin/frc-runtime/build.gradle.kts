import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import java.io.File

plugins {
    kotlin("jvm")
    id("edu.wpi.first.GradleRIO") version "2026.2.1"
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}

description = "Vendor-neutral FRC lifecycle and generated-project host adapters for ARES."

dependencies {
    implementation(kotlin("stdlib"))
    api(project(":core"))
    wpi.java.deps.wpilib().forEach { dependency -> implementation(dependency) }

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

val extractTestNatives by tasks.registering(Copy::class) {
    dependsOn(configurations.testRuntimeClasspath)
    from(configurations.testRuntimeClasspath.get().map { dependency ->
        if (dependency.isDirectory) dependency else zipTree(dependency)
    })
    into(layout.buildDirectory.dir("jni/release"))
    include("**/*.dll", "**/*.so", "**/*.dylib")
    eachFile { relativePath = RelativePath(true, name) }
    includeEmptyDirs = false
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    dependsOn(extractTestNatives)
    useJUnitPlatform()
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        val wpilibJdk = File("C:/Users/Public/wpilib/2026/jdk/bin/java.exe")
        if (wpilibJdk.isFile) executable = wpilibJdk.absolutePath
    }
    val jniPath = layout.buildDirectory.dir("jni/release").get().asFile.absolutePath
    systemProperty("java.library.path", jniPath)
    environment("PATH", "$jniPath${File.pathSeparator}${System.getenv("PATH").orEmpty()}")
}
