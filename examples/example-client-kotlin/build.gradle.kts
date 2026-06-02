plugins {
    kotlin("jvm") version "2.2.0"
    application
}

repositories {
    mavenCentral()
}

// Kotlin 2.2.x caps at JVM target 22; pin Java to match (this box is JDK 25 — see the memory note).
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
    implementation(project(":rhizome-sqlite"))
    implementation(project(":rhizome-http"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // The example is a real runnable JVM app, so its SqliteHandle binding ships in main (not test).
    implementation("org.xerial:sqlite-jdbc:3.45.2.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.0")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass = "io.rhizome.example.MainKt"
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}
