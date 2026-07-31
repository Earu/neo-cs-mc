plugins {
    id("net.neoforged.moddev.legacyforge")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

legacyForge {
    // Vanilla-only mode for 1.20.1: Mojang-mapped Minecraft via MCP data, no loader.
    mcpVersion = libs.versions.mcp.get()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // MC 1.20.1 is a Java 17 game; the whole branch emits 17 bytecode.
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    // Provided at runtime by KFF on Forge (and fabric-language-kotlin on Fabric) — never shaded.
    compileOnly(libs.kotlinx.coroutines)
    compileOnly(libs.kotlinx.serialization.json)

    compileOnly(libs.mixin)
    compileOnly(libs.mixinextras.common)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

tasks.test {
    useJUnitPlatform()
}
