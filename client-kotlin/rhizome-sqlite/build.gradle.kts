import java.io.File

plugins {
    kotlin("jvm") version "2.2.0"
    `maven-publish`
}

group = "io.rhizome"
version = "0.8.2"

repositories {
    mavenCentral()
}

// Target JVM 11 bytecode (major 55) so the jar dexes under consumers' Android toolchains (ForestNote
// is AGP 8.7 / minSdk 30 — JVM 22 bytecode would not dex). The compiler still runs on JDK 25.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

kotlin {
    // See rhizome-core: emit Kotlin 2.0-compatible artifacts so ForestNote's 2.0.21 compiler can
    // consume them, while still building with the 2.2.0 compiler on JDK 25.
    coreLibrariesVersion = "2.0.21"
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

dependencies {
    implementation(project(":rhizome-core"))
    // Runtime JSON only (JsonObject/Json) — no @Serializable codegen here, so no serialization plugin.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // The library's own SqliteHandle binding for tests/JVM examples (cached via SQLDelight's driver).
    testImplementation("org.xerial:sqlite-jdbc:3.45.2.0")
    testImplementation(project(":rhizome-http"))
}

tasks.test {
    useJUnit()
    // Opt-in cross-language harness; changing its binary must rerun the test.
    inputs.property("assetLab", providers.environmentVariable("RHIZOME_ASSET_TEST_SERVER").orElse(""))
    providers.environmentVariable("RHIZOME_ASSET_TEST_SERVER").orNull?.let { inputs.file(it) }
    inputs.property("assetBooks", providers.environmentVariable("RHIZOME_ASSET_TEST_BOOKS").orElse(""))
    providers.environmentVariable("RHIZOME_ASSET_TEST_BOOKS").orNull?.split(File.pathSeparator)?.forEach { inputs.file(it) }
    testLogging { events("passed", "skipped", "failed") }
}
