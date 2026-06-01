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
