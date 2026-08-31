// Configuration must not rewrite user-wide Gradle settings. Toolchains and the invoking IDE or
// shell own JDK selection; fail with portable instructions when the Gradle JVM is too old.
val currentJava = JavaVersion.current()
if (!currentJava.isCompatibleWith(JavaVersion.VERSION_17)) {
    throw GradleException(
        "ARESLib requires a Gradle JVM on Java 17 or newer " +
            "(currently ${System.getProperty("java.version")}). Set JAVA_HOME or select a compatible " +
            "Gradle JVM in the IDE, then run the command again. No user-level Gradle files were changed.",
    )
}

rootProject.name = "ARESLib-Kotlin"

include("core")
include("telemetry-schema")
include("project-schema")
include("simulation-foundation")
include("project-model")
include("project-compiler")
include("codegen")
include("ftc-mocks")
include("ftc-hardware")
include("frc-runtime")
include("frc-hardware")
include("simulator")
include("simulator-runtime-windows")
include("simulator-runtime-linux")
include("simulator-runtime-macos")
include("ares-bom")
