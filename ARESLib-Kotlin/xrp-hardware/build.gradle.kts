import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}

description = "XRP hardware adapters, robot lifecycle, and sensor IO foundations for ARES XRP projects."

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    api(project(":core"))
    api(project(":telemetry-schema"))
    api(project(":project-schema"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
