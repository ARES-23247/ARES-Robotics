import com.vanniktech.maven.publish.JavaPlatform

plugins {
    `java-platform`
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    configure(JavaPlatform())
}

description = "Bill of materials that keeps all ARES Robotics library artifacts on one tested version."

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api(project(":telemetry-schema"))
        api(project(":project-schema"))
        api(project(":simulation-foundation"))
        api(project(":project-model"))
        api(project(":project-compiler"))
        api(project(":core"))
        api(project(":codegen"))
        api(project(":ftc-mocks"))
        api(project(":ftc-hardware"))
        api(project(":frc-runtime"))
        api(project(":frc-hardware"))
        api(project(":xrp-hardware"))
        api(project(":simulator"))
        api(project(":simulator-runtime-windows"))
        api(project(":simulator-runtime-linux"))
        api(project(":simulator-runtime-macos"))
    }
}
