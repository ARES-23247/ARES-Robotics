import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import java.util.zip.ZipFile

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}

description = "FTC hardware adapters and robot foundations for ARES season projects."

// Compile production adapters against the actual controller API. Desktop mocks belong only on
// the test/simulator classpath: Kotlin accessors in a mock must never determine robot bytecode.
abstract class FtcAarClasses : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val artifact = inputArtifact.get().asFile
        val output = outputs.file(artifact.nameWithoutExtension + ".jar")
        ZipFile(artifact).use { archive ->
            archive.getInputStream(requireNotNull(archive.getEntry("classes.jar"))).use { input ->
                output.outputStream().use(input::copyTo)
            }
        }
    }
}

val artifactType = Attribute.of("artifactType", String::class.java)
val ftcSdk by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val sdkClasses = ftcSdk.incoming.artifactView {
    attributes.attribute(artifactType, "jar")
}.files

repositories {
    mavenCentral()
    google()
    maven("https://repo.dairy.foundation/releases")
}

dependencies {
    registerTransform(FtcAarClasses::class) {
        from.attribute(artifactType, "aar")
        to.attribute(artifactType, "jar")
    }
    for (module in listOf("RobotCore", "Hardware", "FtcCommon", "Vision")) {
        ftcSdk("org.firstinspires.ftc:$module:12.0.0@aar")
    }
    implementation(kotlin("stdlib"))
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    compileOnly(sdkClasses)
    compileOnly("com.google.android:android:4.1.1.4")
    testImplementation(project(":ftc-mocks"))
    testImplementation(kotlin("test"))
    testImplementation("org.ow2.asm:asm:9.9")
}

tasks.test {
    useJUnitPlatform()
    inputs.files(sdkClasses)
    doFirst {
        systemProperty("ares.ftc.sdk.classpath", sdkClasses.asPath)
        systemProperty("ares.ftc.hardware.classes", layout.buildDirectory.dir("classes/kotlin/main").get().asFile.absolutePath)
    }
}

kotlin {
    jvmToolchain(17)
}
