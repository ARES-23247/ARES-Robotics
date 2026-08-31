import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

// Single source of truth for the application version. Consumed both by the native
// distribution packaging below and by the generated BuildConfig (see generateBuildConfig).
val aresAnalyticsVersion = rootProject.extra["aresStudioVersion"] as String
val aresProductName = "ARES Robotics Studio"
val aresProductTagline = "Design • Simulate • Operate • Analyze"
val aresLegacyProductName = "ARES Analytics"
val googleOAuthClientIdEnvironment = providers.environmentVariable("ARES_GOOGLE_OAUTH_CLIENT_ID")
val googleOAuthBrokerUrlEnvironment = providers.environmentVariable("ARES_GOOGLE_OAUTH_BROKER_URL")
val githubAppClientIdEnvironment = providers.environmentVariable("ARES_GITHUB_APP_CLIENT_ID")
val githubAppSlugEnvironment = providers.environmentVariable("ARES_GITHUB_APP_SLUG")
val windowsUpdateSignerThumbprintsEnvironment = providers.environmentVariable("ARES_WINDOWS_UPDATE_SIGNER_THUMBPRINTS")
val googleOAuthClientId = providers.gradleProperty("googleOAuthClientId")
    .orElse(googleOAuthClientIdEnvironment)
    .orElse(providers.gradleProperty("aresPublicGoogleOAuthClientId"))
    .orElse("")
    .get()
    .trim()
val googleOAuthBrokerUrl = providers.gradleProperty("googleOAuthBrokerUrl")
    .orElse(googleOAuthBrokerUrlEnvironment)
    .orElse(providers.gradleProperty("aresPublicGoogleOAuthBrokerUrl"))
    .orElse("")
    .get()
    .trimEnd('/')
val githubAppClientId = providers.gradleProperty("githubAppClientId")
    .orElse(githubAppClientIdEnvironment)
    .orElse(providers.gradleProperty("aresPublicGitHubAppClientId"))
    .orElse("")
    .get()
    .trim()
val githubAppSlug = providers.gradleProperty("githubAppSlug")
    .orElse(githubAppSlugEnvironment)
    .orElse(providers.gradleProperty("aresPublicGitHubAppSlug"))
    .orElse("")
    .get()
    .trim()
val windowsUpdateSignerThumbprints = providers.gradleProperty("windowsUpdateSignerThumbprints")
    .orElse(windowsUpdateSignerThumbprintsEnvironment)
    .orElse("")
    .get()
    .split(',')
    .map { it.filter(Char::isLetterOrDigit).uppercase() }
    .filter { it.matches(Regex("[A-F0-9]{40,128}")) }
    .distinct()
val isGitHubActions = providers.environmentVariable("GITHUB_ACTIONS")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}


dependencies {
    val aresVersion = rootProject.extra["aresVersion"] as String

    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Shared module
    implementation(project(":shared"))
    
    // Versioned ARES libraries from the GitHub Maven release channel (or -ParesRepository for validation).
    implementation(platform("org.aresfirst.ares:ares-bom:$aresVersion"))
    implementation("org.aresfirst.ares:core")
    implementation("org.aresfirst.ares:codegen")
    implementation("org.aresfirst.ares:project-model")

    // Database — DuckDB via JDBC
    implementation("org.duckdb:duckdb_jdbc:1.5.5.1")

    // Networking — Ktor client
    implementation("io.ktor:ktor-client-cio:3.5.2")
    implementation("io.ktor:ktor-client-java:3.5.2")
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("io.ktor:ktor-client-websockets:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")

    // Embedded OAuth loopback server
    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-cio:3.5.2")

    // Serialization
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    // Windows Credential Protection (DPAPI) for OAuth refresh-token persistence.
    implementation("net.java.dev.jna:jna-platform:5.19.1")

    // Pure-Java project version history; students do not need a separate Git installation.
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.1.202607240634-r")

    // Math & Signal Processing
    implementation("org.ejml:ejml-simple:0.46.1")
    implementation("org.apache.commons:commons-math3:3.6.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.6.3")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.ktor:ktor-client-mock-jvm:3.5.2")
    testImplementation("org.mockito:mockito-core:5.23.0")
    
    // Compression
    implementation("org.tukaani:xz:1.12")

    // Gamepad Support (LWJGL / GLFW — no external SDL dependency)
    val lwjglVersion = "3.4.3"
    val lwjglOs = System.getProperty("os.name").lowercase()
    val lwjglArch = System.getProperty("os.arch").lowercase()
    val lwjglNatives = when {
        lwjglOs.contains("win") -> "natives-windows"
        lwjglOs.contains("mac") && (lwjglArch.contains("aarch64") || lwjglArch.contains("arm64")) -> "natives-macos-arm64"
        lwjglOs.contains("mac") -> "natives-macos"
        lwjglOs.contains("linux") && (lwjglArch.contains("aarch64") || lwjglArch.contains("arm64")) -> "natives-linux-arm64"
        lwjglOs.contains("linux") -> "natives-linux"
        else -> error("Unsupported LWJGL platform: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    }
    implementation("org.lwjgl:lwjgl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-glfw:$lwjglVersion")
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw:$lwjglVersion:$lwjglNatives")
}

// Generate BuildConfig.kt from gradle so the update-checker reads the real package version
// instead of a hand-maintained constant that drifts (AUDIT H12). The hand-maintained
// app/src/main/kotlin/.../BuildConfig.kt was deleted in favor of this generated file.
val generatedBuildConfigDir = layout.buildDirectory.dir("generated/buildconfig/src/main/kotlin")
tasks.register("generateBuildConfig") {
    val version = aresAnalyticsVersion
    val productName = aresProductName
    val productTagline = aresProductTagline
    val legacyProductName = aresLegacyProductName
    val oauthClientId = googleOAuthClientId
    val oauthBrokerUrl = googleOAuthBrokerUrl
    val githubClientId = githubAppClientId
    val githubSlug = githubAppSlug
    val updateSignerThumbprints = windowsUpdateSignerThumbprints.joinToString(",")
    inputs.property("aresAnalyticsVersion", version)
    inputs.property("aresProductName", productName)
    inputs.property("aresProductTagline", productTagline)
    inputs.property("aresLegacyProductName", legacyProductName)
    inputs.property("googleOAuthClientId", oauthClientId)
    inputs.property("googleOAuthBrokerUrl", oauthBrokerUrl)
    inputs.property("githubAppClientId", githubClientId)
    inputs.property("githubAppSlug", githubSlug)
    inputs.property("windowsUpdateSignerThumbprints", updateSignerThumbprints)
    outputs.dir(generatedBuildConfigDir)
    doLast {
        val pkgDir = generatedBuildConfigDir.get().asFile.resolve("com/ares/analytics")
        pkgDir.mkdirs()
        val escapedOAuthClientId = oauthClientId
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
        val escapedOAuthBrokerUrl = oauthBrokerUrl
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
        val escapedGitHubClientId = githubClientId
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val escapedGitHubAppSlug = githubSlug
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
        fun String.kotlinLiteral() = replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
        pkgDir.resolve("BuildConfig.kt").writeText(
            """
            |package com.ares.analytics
            |
            |object BuildConfig {
            |    const val VERSION = "$version"
            |    const val PRODUCT_NAME = "${productName.kotlinLiteral()}"
            |    const val PRODUCT_TAGLINE = "${productTagline.kotlinLiteral()}"
            |    const val LEGACY_PRODUCT_NAME = "${legacyProductName.kotlinLiteral()}"
            |    const val GOOGLE_OAUTH_CLIENT_ID = "$escapedOAuthClientId"
            |    const val GOOGLE_OAUTH_BROKER_URL = "$escapedOAuthBrokerUrl"
            |    const val GITHUB_APP_CLIENT_ID = "$escapedGitHubClientId"
            |    const val GITHUB_APP_SLUG = "$escapedGitHubAppSlug"
            |    const val WINDOWS_UPDATE_SIGNER_THUMBPRINTS = "$updateSignerThumbprints"
            |}
            """.trimMargin()
        )
    }
}

val brandResourceDir = layout.projectDirectory.dir("src/main/resources/brand")
val brandMaster = brandResourceDir.file("ares-studio-master.png")
val brandAppIcon = brandResourceDir.file("ares-studio-app.png")
val brandLinuxIcon = brandResourceDir.file("ares-studio.png")
val brandWindowsIcon = brandResourceDir.file("ares-studio.ico")
val brandMacIcon = brandResourceDir.file("ares-studio.icns")

tasks.register("verifyBrandAssets") {
    group = "verification"
    description = "Verifies the ARES Robotics Studio desktop icon family."
    inputs.files(brandMaster, brandAppIcon, brandLinuxIcon, brandWindowsIcon, brandMacIcon)
    doLast {
        fun verifyPng(file: File, expectedSize: Int, requireAlpha: Boolean) {
            require(file.isFile) { "Missing brand asset: $file" }
            val image = requireNotNull(ImageIO.read(file)) { "Unreadable PNG brand asset: $file" }
            require(image.width == expectedSize && image.height == expectedSize) {
                "${file.name} must be ${expectedSize}x$expectedSize, found ${image.width}x${image.height}"
            }
            if (requireAlpha) require(image.colorModel.hasAlpha()) { "${file.name} must preserve transparency" }
        }

        verifyPng(brandMaster.asFile, 1024, requireAlpha = true)
        verifyPng(brandAppIcon.asFile, 256, requireAlpha = true)
        verifyPng(brandLinuxIcon.asFile, 512, requireAlpha = true)

        val icoBytes = brandWindowsIcon.asFile.readBytes()
        require(
            icoBytes.size > 6 &&
                icoBytes[0] == 0.toByte() && icoBytes[1] == 0.toByte() &&
                icoBytes[2] == 1.toByte() && icoBytes[3] == 0.toByte(),
        ) { "${brandWindowsIcon.asFile.name} is not a valid ICO container" }
        val icoCount = (icoBytes[4].toInt() and 0xff) or ((icoBytes[5].toInt() and 0xff) shl 8)
        require(icoCount >= 9) { "Windows icon must contain at least 9 resolutions, found $icoCount" }

        val icnsBytes = brandMacIcon.asFile.readBytes()
        require(icnsBytes.size > 8 && icnsBytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "icns") {
            "${brandMacIcon.asFile.name} is not a valid ICNS container"
        }
    }
}

tasks.named("check") {
    dependsOn("verifyBrandAssets")
}

sourceSets {
    main {
        kotlin.srcDir(generatedBuildConfigDir)
    }
}

tasks.named("compileKotlin") {
    dependsOn("generateBuildConfig")
}

// A local validation repository is an explicit developer choice. Forward only a file URI to the
// running desktop app so its nested robot-project Gradle wrappers resolve the same ARES binaries.
// Native installers never embed this machine-local path, and mavenLocal is intentionally unused.
val nestedAresRepositoryUri = providers.gradleProperty("aresRepository").map { configured ->
    rootProject.uri(configured)
}.map { configuredUri ->
    require(configuredUri.scheme.equals("file", ignoreCase = true)) {
        "Nested robot builds require -ParesRepository to resolve to a local file URI"
    }
    configuredUri.toASCIIString()
}
val usesSiblingAresLib = providers.gradleProperty("aresUseSiblingLib")
    .map(String::toBoolean)
    .getOrElse(false)

// Automated desktop walkthroughs must never persist throwaway workspaces, credentials, or
// learning progress into the developer's real home directory. This is opt-in so ordinary
// `:app:run` keeps the installed application's normal data. Example:
//   -ParesIsolatedDesktopHome=build/ui-test-home
val isolatedDesktopHome = providers.gradleProperty("aresIsolatedDesktopHome").map { configured ->
    rootProject.file(configured).canonicalFile
}
tasks.withType<JavaExec>().configureEach {
    systemProperty("ares.version", rootProject.extra["aresVersion"] as String)
    nestedAresRepositoryUri.orNull?.let { uri ->
        systemProperty("ares.repository.uri", uri)
    }
    if (name == "run") {
        doFirst {
            require(!usesSiblingAresLib || nestedAresRepositoryUri.isPresent) {
                """
                -ParesUseSiblingLib=true only substitutes ARESLib into the desktop build. Newly
                created robot projects are separate Gradle builds and would still resolve the
                pinned remote ARES version. Launch with scripts/run-local-ares.ps1, or publish an
                isolated validation repository and pass the same -ParesVersion and
                -ParesRepository=file:///... values to :app:run.
                """.trimIndent()
            }
        }
        isolatedDesktopHome.orNull?.let { directory ->
            doFirst {
                val realHome = File(System.getProperty("user.home")).canonicalFile
                require(directory != realHome) {
                    "-ParesIsolatedDesktopHome must not point at the real user home"
                }
                require(directory.exists() || directory.mkdirs()) {
                    "Could not create isolated desktop home: ${directory.absolutePath}"
                }
            }
            systemProperty("user.home", directory.absolutePath)
        }
    }
}

// Compose's development run task normally points the child JVM at mutable build/classes
// directories. If another agent compiles or cleans while the app is open, a lazily loaded
// screen can disappear from that running classpath and crash minutes later. Snapshot every
// directory and project-owned runtime artifact into a unique OS temp directory immediately
// before launch. External dependency jars in Gradle's immutable cache remain in place.
var activeDesktopRunSnapshot: File? = null
val cleanupDesktopRunSnapshot = tasks.register("cleanupDesktopRunSnapshot") {
    doLast {
        activeDesktopRunSnapshot?.let { snapshot ->
            val tempRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
            val canonicalSnapshot = snapshot.canonicalFile
            if (canonicalSnapshot.parentFile == tempRoot && canonicalSnapshot.name.startsWith("ares-analytics-run-")) {
                canonicalSnapshot.deleteRecursively()
            }
        }
        activeDesktopRunSnapshot = null
    }
}

tasks.withType<JavaExec>().configureEach {
    if (name != "run") return@configureEach
    finalizedBy(cleanupDesktopRunSnapshot)
    doFirst {
        val projectRoot = rootProject.projectDir.toPath().toAbsolutePath().normalize()
        val snapshotRoot = Files.createTempDirectory("ares-analytics-run-").toFile()
        val isolatedClasspath = classpath.files.mapIndexed { index, entry ->
            val entryPath = entry.toPath().toAbsolutePath().normalize()
            val isMutableProjectArtifact = entryPath.startsWith(projectRoot)
            if (entry.isDirectory || isMutableProjectArtifact) {
                val destination = snapshotRoot.resolve("classpath-$index-${entry.name}")
                if (entry.isDirectory) {
                    project.copy {
                        from(entry)
                        into(destination)
                    }
                } else {
                    destination.parentFile.mkdirs()
                    Files.copy(entryPath, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                destination
            } else {
                entry
            }
        }

        activeDesktopRunSnapshot = snapshotRoot
        setClasspath(project.files(isolatedClasspath))

        // Compose appends its application-resources directory as a raw -D JVM argument rather
        // than through JavaExec.systemProperties. Replace that mutable build/ path explicitly;
        // merely adding a second property leaves argument ordering to Gradle and can still launch
        // against build/compose/tmp after another agent cleans it.
        val composeResourcesProperty = "compose.application.resources.dir"
        val composeResourcesPrefix = "-D$composeResourcesProperty="
        val configuredResourcesPath = systemProperties[composeResourcesProperty]?.toString()
            ?: jvmArgs.orEmpty()
                .firstOrNull { argument -> argument.startsWith(composeResourcesPrefix) }
                ?.removePrefix(composeResourcesPrefix)
        requireNotNull(configuredResourcesPath) {
            "Compose run task did not expose $composeResourcesProperty for runtime isolation"
        }

        val resourceDirectory = project.file(configuredResourcesPath)
        val isolatedResources = snapshotRoot.resolve("compose-application-resources")
        if (resourceDirectory.isDirectory) {
            project.copy {
                from(resourceDirectory)
                into(isolatedResources)
            }
        } else {
            // prepareAppResources is legitimately NO-SOURCE when the application has no
            // Compose-managed resources. Keep the property isolated anyway so a later build
            // cannot make a previously absent mutable directory appear under the running JVM.
            require(isolatedResources.mkdirs()) {
                "Could not create isolated empty Compose application resources directory"
            }
        }
        require(isolatedResources.isDirectory) {
            "Compose application resources snapshot was not created: ${isolatedResources.absolutePath}"
        }

        // Remove Compose's original raw argument before installing the isolated value as the
        // single authoritative system property.
        setJvmArgs(jvmArgs.orEmpty().filterNot { argument -> argument.startsWith(composeResourcesPrefix) })
        systemProperty(composeResourcesProperty, isolatedResources.absolutePath)
        require(jvmArgs.orEmpty().none { argument -> argument.startsWith(composeResourcesPrefix) }) {
            "Mutable Compose application resources JVM argument was not removed"
        }
        require(systemProperties[composeResourcesProperty] == isolatedResources.absolutePath) {
            "Compose application resources did not switch to the runtime snapshot"
        }

        println("[ARES-Analytics] Isolated desktop runtime classpath at ${snapshotRoot.absolutePath}")
        println("[ARES-Analytics] Isolated Compose application resources at ${isolatedResources.absolutePath}")
    }
}

// Opt-in official-template checks run in a forked test JVM. Forward only these reviewed
// release-check properties so a successful Gradle invocation cannot silently mean the test was
// skipped because its inputs were visible to Gradle but not to the test process.
listOf(
    "ares.officialTemplateArchiveDir",
    "ares.officialTemplateOutputDir",
    "ares.officialTemplateValidationRepository",
    "ares.officialTemplateValidationVersion",
    "ares.officialTemplateValidateProjects",
    "ares.genericStarterArchiveDir",
    "ares.genericStarterOutputDir",
    "ares.genericStarterValidationRepository",
    "ares.genericStarterValidationVersion",
    "ares.genericStarterTemplateVersion",
).forEach { propertyName ->
    providers.systemProperty(propertyName).orNull?.let { value ->
        tasks.withType<Test>().configureEach {
            systemProperty(propertyName, value)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.ares.analytics.MainKt"

        // The desktop app intentionally carries reflective and platform-specific libraries (DuckDB,
        // Ktor, JNA, and LWJGL). ProGuard cannot prove those optional entry points and aborts release
        // packaging with thousands of false unresolved-reference warnings. Keep the verified jlink
        // runtime image, but do not bytecode-shrink the release jars; packaged-project loading below
        // remains the executable release gate.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = aresProductName
            packageVersion = aresAnalyticsVersion
            description = "Robot design, simulation, mission control, learning, and analytics"
            vendor = "ARES Robotics"
            licenseFile.set(rootProject.file("LICENSE"))
            // Gson constructs immutable Kotlin project documents through sun.misc.Unsafe when
            // they do not expose a no-argument JVM constructor. jlink cannot discover this
            // reflective dependency, so the module must remain explicit in every native image.
            // JGit's pack window cache publishes an optional JMX bean on the first real fetch.
            // Packaged project parsing does not exercise that path, so keep java.management
            // explicit to prevent a fetch-only NoClassDefFoundError in the trimmed runtime.
            modules("java.sql", "java.naming", "java.management", "jdk.unsupported")

            windows {
                msiPackageVersion = aresAnalyticsVersion
                menuGroup = "ARES Robotics"
                // Preserve the legacy product's upgrade family so this public rename performs an
                // in-place upgrade instead of installing a second application.
                upgradeUuid = "a3e52324-7000-4224-8700-1c7b8d9e2a3c"
                iconFile.set(brandWindowsIcon)
            }

            macOS {
                dmgPackageVersion = aresAnalyticsVersion
                iconFile.set(brandMacIcon)
            }

            linux {
                debPackageVersion = aresAnalyticsVersion
                menuGroup = "ARES Robotics"
                iconFile.set(brandLinuxIcon)
            }
        }
    }
}

val packagedProjectFixture = layout.projectDirectory.dir("src/test/resources/packaged-runtime-project")
val mainDistributableRoot = layout.buildDirectory.dir("compose/binaries/main/app")

val verifyDistributableProjectLoading = tasks.register<Exec>("verifyDistributableProjectLoading") {
    group = "verification"
    description = "Loads every canonical ARES project document through the trimmed native runtime."
    dependsOn("createDistributable")
    inputs.dir(packagedProjectFixture)

    doFirst {
        val root = mainDistributableRoot.get().asFile
        val osName = System.getProperty("os.name").lowercase()
        val executable = when {
            osName.contains("win") -> root.resolve("$aresProductName/$aresProductName.exe")
            osName.contains("mac") -> root.resolve("$aresProductName.app/Contents/MacOS/$aresProductName")
            else -> root.resolve("$aresProductName/bin/$aresProductName")
        }
        require(executable.isFile) { "Native $aresProductName launcher was not created at $executable" }
        commandLine(
            executable.absolutePath,
            "--verify-packaged-project",
            packagedProjectFixture.asFile.absolutePath,
        )
    }
}

tasks.matching { task ->
    task.name in setOf(
        "packageMsi", "packageDmg", "packageDeb",
        "packageReleaseMsi", "packageReleaseDmg", "packageReleaseDeb",
    )
}.configureEach {
    dependsOn(verifyDistributableProjectLoading)
    doFirst {
        if (isGitHubActions.get()) {
            require(googleOAuthClientIdEnvironment.orNull?.trim() == googleOAuthClientId) {
                "Protected package jobs must provide ARES_GOOGLE_OAUTH_CLIENT_ID"
            }
            require(googleOAuthBrokerUrlEnvironment.orNull?.trimEnd('/') == googleOAuthBrokerUrl) {
                "Protected package jobs must provide ARES_GOOGLE_OAUTH_BROKER_URL"
            }
            require(githubAppClientIdEnvironment.orNull?.trim() == githubAppClientId) {
                "Protected package jobs must provide ARES_GITHUB_APP_CLIENT_ID"
            }
            require(githubAppSlugEnvironment.orNull?.trim() == githubAppSlug) {
                "Protected package jobs must provide ARES_GITHUB_APP_SLUG"
            }
        }
        require(
            googleOAuthClientId.length in 30..256 &&
                googleOAuthClientId.endsWith(".apps.googleusercontent.com") &&
                googleOAuthClientId.none(Char::isWhitespace)
        ) {
            "Official packages require -PgoogleOAuthClientId (or ARES_GOOGLE_OAUTH_CLIENT_ID) with a valid Google Desktop OAuth client ID"
        }
        require(googleOAuthBrokerUrl.startsWith("https://") && googleOAuthBrokerUrl.length <= 512) {
            "Official packages require an HTTPS Google OAuth broker URL"
        }
        require(
            githubAppClientId.matches(Regex("[A-Za-z0-9_-]{12,128}")) &&
                !githubAppClientId.contains("mock", ignoreCase = true)
        ) {
            "Official packages require -PgithubAppClientId (or ARES_GITHUB_APP_CLIENT_ID) with the public ARES GitHub App client ID"
        }
        require(githubAppSlug.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?"))) {
            "Official packages require -PgithubAppSlug (or ARES_GITHUB_APP_SLUG) with the public ARES GitHub App slug"
        }
    }
}

val releaseWindowsInstaller = layout.buildDirectory
    .file("compose/binaries/main-release/msi/$aresProductName-$aresAnalyticsVersion.msi")
val localReleaseDirectory = rootProject.layout.projectDirectory.dir("dist")

val verifyWindowsInstallerMaintenance = tasks.register<Exec>("verifyWindowsInstallerMaintenance") {
    group = "verification"
    description = "Verifies that rerunning the Windows installer exposes Repair and preserves upgrade identity."
    onlyIf { System.getProperty("os.name").lowercase().contains("win") }
    doFirst {
        val msi = releaseWindowsInstaller.get().asFile
        require(msi.isFile) { "Release MSI was not created at $msi" }
        commandLine(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", rootProject.file("scripts/verify-windows-installer.ps1").absolutePath,
            "-MsiFile", msi.absolutePath,
            "-ExpectedProductName", aresProductName,
            "-ExpectedProductVersion", aresAnalyticsVersion,
            "-ExpectedUpgradeCode", "{A3E52324-7000-4224-8700-1C7B8D9E2A3C}",
        )
    }
    doLast {
        val msi = releaseWindowsInstaller.get().asFile
        project.copy {
            from(msi)
            into(localReleaseDirectory)
        }
        println("Verified installer copied to ${localReleaseDirectory.asFile.resolve(msi.name)}")
    }
}

tasks.matching { it.name == "packageReleaseMsi" }.configureEach {
    finalizedBy(verifyWindowsInstallerMaintenance)
}

private val validationPropertyNames = listOf(
    "simulatedSeconds",
    "sampleRateHz",
    "topicCount",
    "batchSize",
    "queryIterations",
    "minIngestionFramesPerSecond",
    "maxQueryP95Ms",
    "maxReplayLoadMs",
    "maxReplayScrubP95Ms",
    "maxParquetOperationMs",
    "maxHeapGrowthMb",
    "maxDropRate",
    "hardwareEnabled",
    "hardwareHost",
    "hardwarePort",
    "hardwareObservationSeconds",
    "hardwareConnectTimeoutSeconds",
    "hardwareMinFrames",
    "hardwareMinTopics",
    "hardwareRequiredKeys"
)

fun Test.configureDashboardValidation(profile: String) {
    group = "verification"
    description = "Runs the $profile dashboard telemetry and performance validation profile."
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    systemProperty("java.awt.headless", "true")
    systemProperty("ares.validation.profile", profile)
    systemProperty(
        "ares.validation.reportDir",
        project.layout.buildDirectory.dir("reports/dashboard-validation").get().asFile.absolutePath
    )
    validationPropertyNames.forEach { name ->
        project.providers.gradleProperty("validation.$name").orNull?.let { value ->
            systemProperty("ares.validation.$name", value)
        }
    }
}

tasks.register<Test>("dashboardSmoke") {
    configureDashboardValidation("smoke")
    filter {
        includeTestsMatching("com.ares.analytics.validation.DashboardValidationTest")
        includeTestsMatching("com.ares.analytics.service.AppSimE2EPipelineTest")
        includeTestsMatching("com.ares.analytics.service.DatabaseServiceIntegrationTest")
        includeTestsMatching("com.ares.analytics.service.ExportServiceTest")
        includeTestsMatching("com.ares.analytics.service.ReplayEngineServiceTest")
        includeTestsMatching("com.ares.analytics.service.ReplayDeterminismTest")
        includeTestsMatching("com.ares.analytics.service.ReplayCacheAndClockTest")
        includeTestsMatching("com.ares.analytics.viewmodel.field.ReplayFieldSnapshotTest")
        includeTestsMatching("com.ares.analytics.ui.components.dashboard.ReplayDashboardModelsTest")
        includeTestsMatching("com.ares.analytics.service.AlertEngineServiceTest")
        includeTestsMatching("com.ares.analytics.service.AlertEngineCompositeTest")
    }
}

tasks.register<Test>("dashboardSoak") {
    configureDashboardValidation("soak")
    maxHeapSize = "2g"
    filter {
        includeTestsMatching("com.ares.analytics.validation.DashboardValidationTest")
        includeTestsMatching("com.ares.analytics.service.AppSimE2EPipelineTest")
    }
}

tasks.register<Test>("simulatorControlSoak") {
    group = "verification"
    description = "Runs a required wall-clock Analytics -> NT4 -> FTC simulator control soak with JFR. Start :TeamCode:runSim first."
    maxParallelForks = 1
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
    systemProperty("java.awt.headless", "true")
    systemProperty("ares.simSoak.required", "true")
    systemProperty("ares.simSoak.seconds", providers.gradleProperty("simSoak.seconds").orElse("3600").get())
    listOf("host", "port", "opMode").forEach { name ->
        providers.gradleProperty("simSoak.$name").orNull?.let { value ->
            systemProperty("ares.simSoak.$name", value)
        }
    }
    val jfrDirectory = layout.buildDirectory.dir("reports/simulator-control-soak").get().asFile
    jfrDirectory.mkdirs()
    val jfrPath = jfrDirectory.resolve("analytics-control-soak.jfr").absolutePath.replace('\\', '/')
    jvmArgs("-XX:StartFlightRecording=filename=$jfrPath,settings=default,dumponexit=true,maxsize=256m")
    filter {
        includeTestsMatching("com.ares.analytics.service.SimulatorControlSoakTest")
    }
}

tasks.register<Test>("dashboardPerformanceBaseline") {
    configureDashboardValidation("baseline")
    description = "Compares the generated smoke report with the checked-in performance baseline."
    dependsOn("dashboardSmoke")
    systemProperty(
        "ares.validation.baselineFile",
        rootProject.file("config/dashboard-performance-baseline.json").absolutePath
    )
    filter {
        includeTestsMatching("com.ares.analytics.validation.DashboardPerformanceBaselineTest")
    }
}

tasks.register<Test>("dashboardHardware") {
    configureDashboardValidation("hardware")
    description = "Validates dashboard telemetry against a physical robot or external simulator."
    filter {
        includeTestsMatching("com.ares.analytics.validation.HardwareDashboardValidationTest")
    }
}
