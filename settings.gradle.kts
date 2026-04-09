pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "remap"
include(":remap-annotation")
include(":remap-processor")
include(":remap-gradle-plugin")
include(":remap-shared")
