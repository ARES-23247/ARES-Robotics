import org.gradle.api.publish.PublishingExtension

plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.dokka") version "1.9.20"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
    // 0.37.0 requires the Dokka v2 / Gradle 9 publishing stack. Keep 0.34.0 until
    // that toolchain migration is validated as one release milestone.
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}

val aresVersionPolicy = listOf(
    rootProject.file("../build-logic/ares-versioning.gradle"),
    rootProject.file("build-logic/ares-versioning.gradle"),
).firstOrNull(File::isFile)
    ?: error("ARES build policy is missing; use the source monorepo or a generated standalone mirror.")
apply(from = aresVersionPolicy)

val aresGroup = "org.aresfirst.ares"
val aresVersion = rootProject.extra["aresReleaseVersion"] as String
val allowFinalReleaseValidation = providers.gradleProperty("allowFinalReleaseValidation")
    .map(String::toBoolean)
    .orElse(false)
val publishedProjectPaths = listOf(
    ":telemetry-schema",
    ":project-schema",
    ":simulation-foundation",
    ":project-model",
    ":project-compiler",
    ":core",
    ":codegen",
    ":ftc-mocks",
    ":ftc-hardware",
    ":frc-runtime",
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
            force("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.4.10")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.4.10")
            force("org.jetbrains.kotlin:kotlin-stdlib-common:2.4.10")
        }
    }
}

subprojects {
    // The documented release matrix intentionally runs apiDump and apiCheck in one invocation.
    // Order them without making apiCheck depend on apiDump: standalone apiCheck must continue to
    // compare source against the committed baseline instead of rewriting that baseline first.
    tasks.matching { it.name == "apiCheck" }.configureEach {
        mustRunAfter("apiDump")
    }

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
                maven {
                    name = "github"
                    url = rootProject.layout.buildDirectory.dir("github-repository").get().asFile.toURI()
                }
            }
        }

        tasks.withType<Jar>().configureEach {
            from(rootProject.file("LICENSE")) {
                into("META-INF")
            }
            from(rootProject.file("NOTICE")) {
                into("META-INF")
            }
            from(rootProject.file("THIRD_PARTY_NOTICES.md")) {
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

val largeProductionKotlinBaseline = file("config/maintainability/large-production-kotlin-baseline.txt")
val verifyAresLibSourceFileSizes = tasks.register("verifyAresLibSourceFileSizes") {
    group = "verification"
    description = "Prevents ARESLib monoliths from growing and new production Kotlin files from exceeding 500 lines."
    inputs.file(largeProductionKotlinBaseline)
    inputs.files(fileTree(rootDir) { include("*/src/main/**/*.kt") })

    doLast {
        val allowed = largeProductionKotlinBaseline.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .associate { line ->
                val separator = line.lastIndexOf('=')
                require(separator > 0) { "Invalid maintainability baseline entry: $line" }
                line.substring(0, separator) to line.substring(separator + 1).toInt()
            }
        val violations = rootDir.walkTopDown()
            .filter { source ->
                source.isFile && source.extension == "kt" &&
                    source.invariantSeparatorsPath.contains("/src/main/") &&
                    !source.invariantSeparatorsPath.contains("/build/")
            }
            .mapNotNull { source ->
                val relative = source.relativeTo(rootDir).invariantSeparatorsPath
                val lineCount = source.useLines { lines -> lines.count() }
                val baseline = allowed[relative]
                when {
                    lineCount <= 500 -> null
                    baseline == null -> "$relative is a new $lineCount-line production file (limit 500)."
                    lineCount > baseline -> "$relative grew from the $baseline-line baseline to $lineCount lines."
                    else -> null
                }
            }
            .toList()
        if (violations.isNotEmpty()) {
            throw GradleException(
                "ARESLib production Kotlin size ratchet failed:\n" +
                    violations.joinToString("\n") { " - $it" } +
                    "\nExtract a cohesive component instead of increasing the baseline.",
            )
        }
        logger.lifecycle(
            "ARESLib Kotlin size ratchet passed (${allowed.size} grandfathered files, 500-line new-file limit).",
        )
    }
}

tasks.matching { it.name == "apiCheck" }.configureEach {
    dependsOn(verifyAresLibSourceFileSizes)
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
                    "-ParesVersion=<candidate>-rc.<commit>. Final-version validation is reserved " +
                    "for the protected release workflow."
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

tasks.register("validateGitHubRepositoryVersion") {
    group = "verification"
    description = "The GitHub-hosted repository serves final versions; validation coordinates belong in build/release-repository."
    dependsOn("validateAresVersion")
    doLast {
        check('-' !in aresVersion) {
            "GitHub repository releases must use final versions: $aresVersion"
        }
    }
}

tasks.register("publishGitHubRepository") {
    group = "publishing"
    description = "Publishes every public artifact under final coordinates to build/github-repository for the ARESLib-Kotlin 'maven' branch."
    dependsOn("validateGitHubRepositoryVersion")
    dependsOn(publishedProjectPaths.map { "$it:publishAllPublicationsToGithubRepository" })
}

tasks.register("stageMavenCentral") {
    group = "publishing"
    description = "Signs and uploads every ARES artifact as one manually released Maven Central deployment."
    dependsOn("validateReleaseVersion")
    dependsOn(publishedProjectPaths.map { "$it:publishAllPublicationsToMavenCentralRepository" })
}
