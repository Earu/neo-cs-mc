plugins {
    id("net.neoforged.moddev.legacyforge")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
    maven("https://maven.minecraftforge.net/")
    maven("https://repo.spongepowered.org/repository/maven-public/")
    maven("https://thedarkcolour.github.io/KotlinForForge/") {
        name = "KotlinForForge"
        content { includeGroup("thedarkcolour") }
    }
}

val commonProject = project(":common")

legacyForge {
    // NeoForged's 1.20.1 release keeps Forge coordinates and APIs.
    version = libs.versions.forge.get()

    runs {
        create("client") {
            client()
        }
        create("server") {
            server()
        }
    }

    mods {
        create("chatsounds") {
            sourceSet(sourceSets.main.get())
        }
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
    archivesName = "chatsounds-forge"
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/mods.toml") {
        expand("version" to project.version)
    }
}

// MultiLoader pattern: common's sources compile directly into this module's jar.
sourceSets.main {
    kotlin.srcDir(commonProject.file("src/main/kotlin"))
    java.srcDir(commonProject.file("src/main/java"))
    resources.srcDir(commonProject.file("src/main/resources"))
}

dependencies {
    implementation(libs.kff.forge)
}
