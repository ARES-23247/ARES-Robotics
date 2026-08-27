// Auto-detect and configure Java 17 if running on an older version
val currentVersion = System.getProperty("java.version") ?: ""
if (!currentVersion.startsWith("17") && !currentVersion.startsWith("21") && !currentVersion.startsWith("22")) {
    val homeDir = System.getProperty("user.home")
    val gradlePropertiesFile = java.io.File(homeDir, ".gradle/gradle.properties")
    val standardJdk17 = java.io.File("C:/Program Files/Java/jdk-17")
    val androidStudioJdk = java.io.File("C:/Program Files/Android/Android Studio/jbr")
    
    val detectedJdk = when {
        standardJdk17.exists() -> standardJdk17
        androidStudioJdk.exists() -> androidStudioJdk
        else -> null
    }
    
    if (detectedJdk != null) {
        gradlePropertiesFile.parentFile.mkdirs()
        gradlePropertiesFile.appendText("\norg.gradle.java.home=${detectedJdk.absolutePath.replace("\\", "/")}\n")
        throw GradleException(
            "\n" +
            "=================================================================================\n" +
            "ARES BUILD ENVIRONMENT AUTO-CONFIGURED\n" +
            "=================================================================================\n" +
            "We detected that you were running with Java $currentVersion, but AGP requires Java 17.\n" +
            "We have automatically configured your local user-level gradle.properties file at:\n" +
            "  ${gradlePropertiesFile.absolutePath}\n" +
            "to use the detected JDK 17 at:\n" +
            "  ${detectedJdk.absolutePath}\n\n" +
            "Please run your command again now!\n" +
            "=================================================================================\n"
        )
    } else {
        throw GradleException(
            "\n" +
            "=================================================================================\n" +
            "ERROR: JAVA 17 REQUIRED\n" +
            "=================================================================================\n" +
            "Your current Gradle JVM is running on Java $currentVersion, but the Android Gradle\n" +
            "Plugin requires Java 17 to build the project.\n\n" +
            "Please install JDK 17 and either:\n" +
            "  1. Set your JAVA_HOME environment variable to point to it, or\n" +
            "  2. Add the following line to your local user-level gradle.properties file\n" +
            "     (located at ${gradlePropertiesFile.absolutePath}):\n\n" +
            "     org.gradle.java.home=C:/Program Files/Java/jdk-17\n" +
            "=================================================================================\n"
        )
    }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val aresRepositoryUrl = providers.gradleProperty("aresRepository").orNull
dependencyResolutionManagement {
    repositories {
        if (!aresRepositoryUrl.isNullOrBlank()) {
            maven(aresRepositoryUrl)
        }
        google()
        mavenCentral()
        maven("https://raw.githubusercontent.com/ARES-23247/ARESLib-Kotlin/maven")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "ARES-Analytics"

include(":shared")
include(":app")
include(":gateway")

// Keep the dashboard and its shared robotics models in lockstep during local
// development. CI/release builds can still resolve the published artifact when
// this sibling checkout is absent.
val useSiblingAresLib = providers.gradleProperty("aresUseSiblingLib")
    .map(String::toBoolean)
    .getOrElse(false)
if (useSiblingAresLib && file("../ARESLib-Kotlin").exists()) {
    includeBuild("../ARESLib-Kotlin")
}
