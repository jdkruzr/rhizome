plugins {
    kotlin("jvm") version "2.2.0"
}

repositories {
    mavenCentral()
}

// Kotlin 2.2.x caps at JVM target 22; pin Java to match so the two compile tasks agree on JDK 25.
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
    implementation(project(":rhizome-core"))
    // Runtime JSON only (JsonObject/Json) — no @Serializable codegen here, so no serialization plugin.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // The library's own SqliteHandle binding for tests/JVM examples (cached via SQLDelight's driver).
    testImplementation("org.xerial:sqlite-jdbc:3.45.2.0")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}
