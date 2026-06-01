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

// Modules join as their phases land. rhizome-sqlite (P2) + rhizome-http (P1) are added then.
include(":rhizome-core")
