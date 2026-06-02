pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "rhizome-client"

include(":rhizome-core")
include(":rhizome-sqlite")
include(":rhizome-http")

// The buildable demo client (examples/example-client-kotlin) is part of this Gradle build so it can
// depend on the library modules directly (no published artifact yet). Its sources live under
// examples/ per the repo layout; the projectDir relocation keeps them there.
include(":example-client-kotlin")
project(":example-client-kotlin").projectDir = file("../examples/example-client-kotlin")
