plugins {
    // Kotlin 2.2.x: its compiler runs on JDK 25 (2.0.21's bundled compiler ICEs parsing "25.0.3").
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

repositories {
    mavenCentral()
}

// Kotlin 2.0.x caps at JVM target 22; pin Java to match so the two compile tasks agree on JDK 25.
java {
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22
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
