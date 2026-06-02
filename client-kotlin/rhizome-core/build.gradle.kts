plugins {
    // Kotlin 2.2.x: its compiler runs on JDK 25 (2.0.21's bundled compiler ICEs parsing "25.0.3").
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    `maven-publish`
}

group = "io.rhizome"
version = "0.8.0"

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
    // Consumers may be on an older Kotlin (ForestNote is 2.0.21). Compile with the 2.2.0 compiler
    // (it runs on JDK 25) but emit language/api 2.0 + depend on the 2.0.21 stdlib, so the published
    // metadata is readable by a 2.0.x compiler. Without this, FN's 2.0.21 compiler rejects the jar
    // ("binary version 2.2.0, expected 2.0.0").
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}
