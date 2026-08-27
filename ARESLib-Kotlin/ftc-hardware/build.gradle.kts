import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}

description = "FTC hardware adapters and robot foundations for ARES season projects."

repositories {
    mavenCentral()
    google()
    maven("https://repo.dairy.foundation/releases")
}

dependencies {
    implementation(kotlin("stdlib"))
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    compileOnly(project(":ftc-mocks"))
    testImplementation(project(":ftc-mocks"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
