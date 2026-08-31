import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}

description = "Platform-neutral simulator product selection and capability validation contracts."

dependencies {
    api(project(":project-schema"))
    implementation(project(":core"))
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
