plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin") version "3.5.2"
}

application {
    mainClass.set("com.ares.analytics.gateway.ApplicationKt")
}

dependencies {
    // Shared module
    implementation(project(":shared"))

    // Ktor server
    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-netty:3.5.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-server-auth:3.5.2")
    implementation("io.ktor:ktor-server-cors:3.5.2")
    implementation("io.ktor:ktor-server-status-pages:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("io.ktor:ktor-server-rate-limit:3.5.2")
    implementation("io.ktor:ktor-server-forwarded-header:3.5.2")
    implementation("io.ktor:ktor-server-request-validation:3.5.2")
    implementation("io.ktor:ktor-server-body-limit:3.5.2")

    // Ktor HTTP client (for GitHub API, Vertex AI)
    implementation("io.ktor:ktor-client-cio:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Google OIDC ID-token verification
    implementation("com.google.api-client:google-api-client:2.9.0")

    // Google Gen AI SDK (supports Gemini through Vertex AI / enterprise mode)
    implementation("com.google.genai:google-genai:1.68.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock-jvm:3.5.2")
    testImplementation("io.ktor:ktor-server-test-host:3.5.2")
    testImplementation("org.mockito:mockito-core:5.23.0")
}

ktor {
    docker {
        jreVersion.set(JavaVersion.VERSION_17)
        localImageName.set("ares-analytics-gateway")
    }
}

tasks.test {
    environment("DEV_MODE", "true")
}
