plugins {
    alias(libs.plugins.loom)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://api.modrinth.com/maven") {
        name = "Modrinth"
        content { includeGroup("maven.modrinth") }
    }
}

val commonProject = project(":common")

dependencies {
    minecraft("com.mojang:minecraft:${libs.versions.minecraft.get()}")
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)
    modImplementation(libs.flk)

    // Dev-only: Dynamic Surroundings (+ its Architectury/cloth-config dependencies) in
    // runClient to exercise the reverb bridge.
    modLocalRuntime("maven.modrinth:dynamicsurroundingsfabric:0.3.3")
    // DS jar-in-jars nashorn, but Loom drops nested jars from remapped deps in dev;
    // without a JS engine DS's DI chain crashes at the title screen.
    runtimeOnly("org.openjdk.nashorn:nashorn-core:15.4")
}

loom {
    mixin {
        // Refmap-free mixin remapping: tiny-remapper rewrites targets at remapJar time.
        useLegacyMixinAp = false
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

base {
    archivesName = "chatsounds-fabric"
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

// MultiLoader pattern: common's sources compile directly into this module's jar.
sourceSets.main {
    kotlin.srcDir(commonProject.file("src/main/kotlin"))
    java.srcDir(commonProject.file("src/main/java"))
    resources.srcDir(commonProject.file("src/main/resources"))
}
