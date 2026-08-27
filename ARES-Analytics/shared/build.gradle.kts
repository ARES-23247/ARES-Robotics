plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    val aresVersion = rootProject.extra["aresVersion"] as String
    api(platform("org.aresfirst.ares:ares-bom:$aresVersion"))
    api("org.aresfirst.ares:core")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}
