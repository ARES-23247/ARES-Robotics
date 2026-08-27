import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}

description = "Linux x86-64 native runtime dependencies for the ARES desktop simulator."

dependencies {
    api(project(":simulator"))

    val wpiVersion = "2024.3.2"
    listOf("wpinet", "ntcore", "wpiutil", "wpimath", "hal").forEach { module ->
        runtimeOnly("edu.wpi.first.$module:$module-jni:$wpiVersion:linuxx86-64")
    }
    runtimeOnly("org.lwjgl:lwjgl:3.3.3:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-glfw:3.3.3:natives-linux")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}
