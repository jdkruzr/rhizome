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

// rhizome-sqlite (P2) joins when the SQLite adapter lands.
include(":rhizome-core")
include(":rhizome-http")
