import org.gradle.api.publish.PublishingExtension

plugins {
    kotlin("jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.dokka") version "1.9.20"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}

val aresGroup = "org.aresfirst.ares"
val aresVersion = providers.gradleProperty("aresVersion").orElse("8.0.0").get()
val allowFinalReleaseValidation = providers.gradleProperty("allowFinalReleaseValidation")
    .map(String::toBoolean)
    .orElse(false)
val publishedProjectPaths = listOf(
    ":core",
    ":codegen",
    ":ftc-mocks",
    ":ftc-hardware",
    ":frc-hardware",
    ":simulator",
    ":simulator-runtime-windows",
    ":simulator-runtime-linux",
    ":simulator-runtime-macos",
    ":ares-bom",
)

allprojects {
    group = aresGroup
    version = aresVersion
}

buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.0")
    }
}

allprojects {
    repositories {
        mavenCentral()
        google()
        maven("https://frcmaven.wpi.edu/artifactory/release/")
        maven("https://maven.ctr-electronics.com/release/")
        maven("https://repo.dairy.foundation/releases")
    }
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-common:2.2.21")
        }
    }
}

subprojects {
    if (name != "tools" && name != "FtcRobotController") {
        apply(plugin = "org.jetbrains.dokka")
        apply(plugin = "org.jetbrains.kotlinx.kover")
    }

    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "releaseValidation"
                    url = rootProject.layout.buildDirectory.dir("release-repository").get().asFile.toURI()
                }
            }
        }

        tasks.withType<Jar>().configureEach {
            from(rootProject.file("LICENSE")) {
                into("META-INF")
            }
        }
    }
}

apiValidation {
    ignoredProjects.addAll(
        listOf(
            "ares-bom",
            "simulator-runtime-windows",
            "simulator-runtime-linux",
            "simulator-runtime-macos",
        )
    )
}

tasks.register("validateAresVersion") {
    group = "verification"
    description = "Validates the shared ARES Maven version and any Git tag/version agreement."
    doLast {
        val semanticVersion = Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?""")
        check(semanticVersion.matches(aresVersion)) {
            "aresVersion '$aresVersion' is not a valid semantic Maven version."
        }

        val ref = System.getenv("GITHUB_REF").orEmpty()
        if (ref.startsWith("refs/tags/v")) {
            val tagVersion = ref.removePrefix("refs/tags/v")
            check(tagVersion == aresVersion) {
                "Git tag v$tagVersion does not match aresVersion $aresVersion."
            }
        }
    }
}

tasks.register("validateReleaseVersion") {
    group = "verification"
    description = "Rejects snapshot versions before staging a Maven Central release."
    dependsOn("validateAresVersion")
    doLast {
        check(!aresVersion.endsWith("-SNAPSHOT", ignoreCase = true)) {
            "Maven Central releases must not use a -SNAPSHOT version: $aresVersion"
        }
    }
}

tasks.register("validateReleaseCandidateVersion") {
    group = "verification"
    description = "Requires isolated validation builds to use a unique prerelease coordinate."
    dependsOn("validateAresVersion")
    doLast {
        if (!allowFinalReleaseValidation.get()) {
            check('-' in aresVersion) {
                "Release validation must use a unique prerelease version, for example " +
                    "-ParesVersion=8.0.0-rc.<commit>. Final-version validation is reserved " +
                    "for the protected Maven Central workflow."
            }
        }
    }
}

tasks.register("publishReleaseValidation") {
    group = "publishing"
    description = "Publishes every public artifact to an isolated repository under build/ for consumer testing."
    dependsOn("validateReleaseCandidateVersion")
    dependsOn(publishedProjectPaths.map { "$it:publishAllPublicationsToReleaseValidationRepository" })
}

tasks.register("stageMavenCentral") {
    group = "publishing"
    description = "Signs and uploads every ARES artifact as one manually released Maven Central deployment."
    dependsOn("validateReleaseVersion")
    dependsOn(publishedProjectPaths.map { "$it:publishAllPublicationsToMavenCentralRepository" })
}
