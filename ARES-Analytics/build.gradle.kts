import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension

plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
}

val aresVersionPolicy = listOf(
    rootProject.file("../build-logic/ares-versioning.gradle"),
    rootProject.file("build-logic/ares-versioning.gradle"),
).firstOrNull(File::isFile)
    ?: error("ARES build policy is missing; use the source monorepo or a generated standalone mirror.")
apply(from = aresVersionPolicy)

group = "com.ares.analytics"
version = rootProject.extra["aresStudioVersion"] as String

val resolvedAresVersion = rootProject.extra["aresReleaseVersion"] as String

extra["aresVersion"] = resolvedAresVersion

// Release metadata is intentionally duplicated in runtime Kotlin and the GitHub workflow, where
// neither can safely import the other. This fast preflight makes gradle.properties authoritative
// and names every stale copy before tests, simulator builds, or native packaging consume minutes.
val verifyReleaseVersionAlignment = tasks.register("verifyReleaseVersionAlignment") {
    group = "verification"
    description = "Fails fast when ARES, app, or bundled starter release pins disagree."

    val releasePropertiesFile = rootProject.extra["aresReleaseManifestFile"] as File
    val starterArtifactsFile = rootProject.file("../release/starter-artifacts.properties")
    val appBuildFile = file("app/build.gradle.kts")
    val templateServiceFile = file(
        "app/src/main/kotlin/com/ares/analytics/service/project/RobotProjectTemplateService.kt",
    )
    val workflowFile = listOf(
        rootProject.file("../.github/workflows/build-distributions.yml"),
        file(".github/workflows/build-distributions.yml"),
    ).firstOrNull(File::isFile)
        ?: error("The protected desktop packaging workflow is missing.")
    val templateDirectory = file("app/src/main/resources/project-templates")
    inputs.files(releasePropertiesFile, starterArtifactsFile, appBuildFile, templateServiceFile, workflowFile)
    inputs.dir(templateDirectory)
    inputs.property("resolvedAresVersion", resolvedAresVersion)

    doLast {
        val properties = java.util.Properties().apply {
            releasePropertiesFile.inputStream().use(::load)
        }
        val starterArtifacts = java.util.Properties().apply {
            starterArtifactsFile.inputStream().use(::load)
        }
        fun requiredProperty(name: String): String = properties.getProperty(name)?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw GradleException("Release preflight: gradle.properties is missing '$name'.")
        fun requiredArtifact(name: String): String = starterArtifacts.getProperty(name)?.trim()
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: throw GradleException("Release preflight: starter-artifacts.properties has no valid '$name'.")
        fun requireContains(file: File, token: String, label: String) {
            if (!file.readText().contains(token)) {
                throw GradleException(
                    "Release preflight: stale $label in ${file.relativeTo(rootDir)}. Expected: $token",
                )
            }
        }
        fun sha256(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val aresVersion = requiredProperty("aresVersion")
        val appVersion = requiredProperty("studioVersion")
        val ftcVersion = requiredProperty("ftcStarterVersion")
        val ftcHash = requiredArtifact("ftcStarterSha256").lowercase()
        val frcVersion = requiredProperty("frcStarterVersion")
        val frcHash = requiredArtifact("frcStarterSha256").lowercase()

        if (resolvedAresVersion != aresVersion) {
            val validationRepository = providers.gradleProperty("aresRepository").orNull?.trim().orEmpty()
            if (validationRepository.isEmpty() || !resolvedAresVersion.startsWith("$aresVersion-")) {
                throw GradleException(
                    "Release preflight: resolved ARES dependency $resolvedAresVersion does not match " +
                        "the pinned release $aresVersion. Use the pinned release, or pass an explicit " +
                        "isolated -ParesRepository with a $aresVersion-* validation candidate.",
                )
            }
            logger.lifecycle(
                "Release preflight is validating isolated ARES candidate $resolvedAresVersion " +
                    "for pinned release $aresVersion.",
            )
        }

        requireContains(
            appBuildFile,
            "rootProject.extra[\"aresStudioVersion\"] as String",
            "application-version source",
        )
        requireContains(templateServiceFile, "id = \"ares-ftc-starter-$ftcVersion\"", "FTC template ID")
        requireContains(templateServiceFile, "aresVersion = \"$ftcVersion\"", "FTC template version")
        requireContains(
            templateServiceFile,
            "ARES-Robotics/releases/download/v$appVersion/ARES-FTC-Starter-$ftcVersion.zip",
            "FTC template URL",
        )
        requireContains(templateServiceFile, "archiveSha256 = \"$ftcHash\"", "FTC template hash")
        requireContains(
            templateServiceFile,
            "bundledResourcePath = \"/project-templates/ARES-FTC-Starter-$ftcVersion.zip\"",
            "FTC bundled-template path",
        )
        requireContains(templateServiceFile, "id = \"ares-frc-starter-$frcVersion\"", "FRC template ID")
        requireContains(templateServiceFile, "aresVersion = \"$frcVersion\"", "FRC template version")
        requireContains(
            templateServiceFile,
            "ARES-Robotics/releases/download/v$appVersion/ARES-FRC-Starter-$frcVersion.zip",
            "FRC template URL",
        )
        requireContains(templateServiceFile, "archiveSha256 = \"$frcHash\"", "FRC template hash")
        requireContains(
            templateServiceFile,
            "bundledResourcePath = \"/project-templates/ARES-FRC-Starter-$frcVersion.zip\"",
            "FRC bundled-template path",
        )

        requireContains(workflowFile, "ARES_VERSION: $aresVersion", "workflow ARES version")
        requireContains(
            workflowFile,
            "ARES-Robotics/releases/download/v$appVersion/ARES-FTC-Starter-$ftcVersion.zip",
            "workflow FTC template URL",
        )
        requireContains(workflowFile, "FTC_STARTER_SHA256: $ftcHash", "workflow FTC template hash")
        requireContains(
            workflowFile,
            "ARES-Robotics/releases/download/v$appVersion/ARES-FRC-Starter-$frcVersion.zip",
            "workflow FRC template URL",
        )
        requireContains(workflowFile, "FRC_STARTER_SHA256: $frcHash", "workflow FRC template hash")

        listOf("FTC" to ftcVersion to ftcHash, "FRC" to frcVersion to frcHash).forEach { entry ->
            val (leagueAndVersion, expectedHash) = entry
            val (league, version) = leagueAndVersion
            val archive = File(templateDirectory, "ARES-$league-Starter-$version.zip")
            if (!archive.isFile) {
                throw GradleException("Release preflight: missing bundled template ${archive.relativeTo(rootDir)}.")
            }
            val actualHash = sha256(archive)
            if (actualHash != expectedHash) {
                throw GradleException(
                    "Release preflight: ${archive.name} SHA-256 is $actualHash, expected $expectedHash.",
                )
            }
        }

        logger.lifecycle(
            "Release preflight passed: Studio $appVersion, ARES dependency $resolvedAresVersion " +
                "(release pin $aresVersion), " +
                "FTC starter $ftcVersion, FRC starter $frcVersion.",
        )
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    tasks.withType<org.gradle.language.jvm.tasks.ProcessResources>().configureEach {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
        from(rootProject.file("NOTICE")) {
            into("META-INF")
        }
        from(rootProject.file("LICENSE_POLICY.md")) {
            into("META-INF")
        }
        from(rootProject.file("THIRD_PARTY_NOTICES.md")) {
            into("META-INF")
        }
    }

    // A running desktop app uses an isolated runtime classpath, so compilation and clean tasks
    // must be safe while it is open. Only a new run owns replacement of an existing app process;
    // coupling clean to killExisting made unrelated agent rebuilds silently close the UI.
    tasks.matching { it.name == "run" }.configureEach {
        if (!project.hasProperty("fromRootRun") && !project.hasProperty("skipKill")) {
            dependsOn(":killExisting")
        }
    }

    // Skip the default sequential subproject run tasks when running from the root project
    tasks.matching { it.name == "run" }.configureEach {
        onlyIf {
            val taskNames = gradle.startParameter.taskNames
            val isRootRun = taskNames.any { it == "run" || it == ":run" }
            !isRootRun
        }
    }

    apply(plugin = "org.jetbrains.kotlinx.kover")

    val lineCoverageFloor = when (name) {
        "app" -> 38
        "gateway", "shared" -> 52
        else -> null
    }
    if (lineCoverageFloor != null) {
        extensions.configure<KoverProjectExtension> {
            if (name == "app") {
                currentProject {
                    instrumentation {
                        disabledForTestTasks.addAll(
                            "dashboardHardware",
                            "dashboardSmoke",
                            "dashboardSoak",
                            "simulatorControlSoak",
                        )
                    }
                }
            }
            reports {
                verify {
                    rule("released baseline line coverage") {
                        minBound(lineCoverageFloor)
                    }
                }
            }
        }
    }

    tasks.matching { task ->
        task.name == "test" || task.name.startsWith("packageRelease")
    }.configureEach {
        dependsOn(verifyReleaseVersionAlignment)
    }
}

val largeProductionKotlinBaseline = file("config/maintainability/large-production-kotlin-baseline.txt")
val productionKotlinRoots = listOf(file("app/src/main"), file("shared/src/main"), file("gateway/src/main"))

val verifyProductionKotlinFileSizes = tasks.register("verifyProductionKotlinFileSizes") {
    group = "verification"
    description = "Prevents existing Kotlin monoliths from growing and new production files from exceeding 500 lines."
    inputs.file(largeProductionKotlinBaseline)
    inputs.files(productionKotlinRoots.map { root -> fileTree(root) { include("**/*.kt") } })

    doLast {
        val allowed = largeProductionKotlinBaseline.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .associate { line ->
                val separator = line.lastIndexOf('=')
                require(separator > 0) { "Invalid maintainability baseline entry: $line" }
                line.substring(0, separator) to line.substring(separator + 1).toInt()
            }
        val violations = buildList {
            productionKotlinRoots.forEach { sourceRoot ->
                sourceRoot.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { source ->
                        val relative = source.relativeTo(rootDir).invariantSeparatorsPath
                        val lineCount = source.useLines { lines -> lines.count() }
                        val baseline = allowed[relative]
                        if (lineCount > 500 && (baseline == null || lineCount > baseline)) {
                            add(
                                if (baseline == null) "$relative is a new $lineCount-line production file (limit 500)."
                                else "$relative grew from the $baseline-line baseline to $lineCount lines.",
                            )
                        }
                    }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Production Kotlin size ratchet failed:\n" + violations.joinToString("\n") { " - $it" } +
                    "\nExtract a cohesive component instead of increasing the baseline.",
            )
        }
        logger.lifecycle(
            "Production Kotlin size ratchet passed (${allowed.size} grandfathered files, 500-line new-file limit).",
        )
    }
}

tasks.register("studioReleaseVerification") {
    group = "verification"
    description = "Runs all deterministic Studio suites, coverage ratchets, dashboard budgets, and maintainability gates."
    dependsOn(
        verifyReleaseVersionAlignment,
        verifyProductionKotlinFileSizes,
        ":shared:test",
        ":gateway:test",
        ":app:test",
        ":shared:koverVerify",
        ":gateway:koverVerify",
        ":app:koverVerify",
        ":app:dashboardPerformanceBaseline",
    )
}

tasks.register("killExisting") {
    // A replacement run must prove its own app bytecode is buildable before closing the healthy
    // instance. mustRunAfter adds ordering only when :app:jar is already in the task graph; a
    // direct, explicit killExisting invocation remains immediate.
    mustRunAfter(":app:jar")
    doFirst {
        println("[ARES-Analytics] Checking for existing orphaned ARES Analytics processes...")
        var killedCount = 0

        // Only terminate JVMs that identify as ARES Analytics. Port ownership is
        // not an application identity; killing every listener on 5810/8080 could
        // terminate an unrelated simulator or developer service.
        try {
            val jpsProc = ProcessBuilder("jps", "-l").start()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(jpsProc.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val parts = line!!.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    val pidString = parts[0]
                    val mainClass = parts[1]
                    if (mainClass.contains("com.ares.analytics")) {
                        val pid = pidString.toLongOrNull()
                        if (pid != null && pid != ProcessHandle.current().pid()) {
                            ProcessHandle.of(pid).ifPresent { handle ->
                                println("[ARES-Analytics] Killing orphaned process $mainClass (PID $pid)...")
                                handle.destroyForcibly()
                                killedCount++
                            }
                        }
                    }
                }
            }
            jpsProc.waitFor()
        } catch (e: Exception) {
            println("[ARES-Analytics] Failed to check via JPS: ${e.message}")
        }
        
        if (killedCount > 0) {
            println("[ARES-Analytics] Successfully terminated $killedCount orphaned process(es).")
        } else {
            println("[ARES-Analytics] No orphaned processes found.")
        }
    }
}

tasks.register("run") {
    if (!project.hasProperty("skipKill")) {
        dependsOn("killExisting")
    }
    dependsOn(":shared:jar", ":gateway:classes", ":app:classes")
    doLast {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val gradlew = if (isWindows) "gradlew.bat" else "./gradlew"
        
        val logDir = layout.buildDirectory.dir("run-logs").get().asFile
        logDir.mkdirs()
        val gatewayLog = java.io.File(logDir, "gateway.log")
        val appLog = java.io.File(logDir, "app.log")
        
        println("[ARES-Analytics] Launching Gateway in background, logging to gateway.log...")
        val gatewayProcess = ProcessBuilder(
            if (isWindows) listOf("cmd.exe", "/c", gradlew, ":gateway:run", "-PfromRootRun=true")
            else listOf("bash", "-c", "$gradlew :gateway:run -PfromRootRun=true")
        ).redirectOutput(ProcessBuilder.Redirect.to(gatewayLog))
         .redirectError(ProcessBuilder.Redirect.to(gatewayLog))
         .start()
        
        // Wait a brief moment for gateway to initialize ports
        Thread.sleep(1000)
        
        println("[ARES-Analytics] Launching App in foreground, logging to app.log...")
        val appProcess = ProcessBuilder(
            if (isWindows) listOf("cmd.exe", "/c", gradlew, ":app:run", "-PfromRootRun=true")
            else listOf("bash", "-c", "$gradlew :app:run -PfromRootRun=true")
        ).redirectOutput(ProcessBuilder.Redirect.to(appLog))
         .redirectError(ProcessBuilder.Redirect.to(appLog))
         .start()
        
        // Add shutdown hook to kill both processes if the Gradle process is killed
        val shutdownHook = Thread {
            println("[ARES-Analytics] Shutting down Gateway and App processes...")
            gatewayProcess.destroyForcibly()
            appProcess.destroyForcibly()
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        
        appProcess.waitFor()
        gatewayProcess.destroyForcibly()
        Runtime.getRuntime().removeShutdownHook(shutdownHook)
    }
}
