pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.library") version providers.gradleProperty("agpVersion").get()
        id("com.android.application") version providers.gradleProperty("agpVersion").get()
        id("com.android.kotlin.multiplatform.library") version providers.gradleProperty("agpVersion").get()
        id("org.jetbrains.kotlin.multiplatform") version providers.gradleProperty("kotlinVersion").get()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "remap-kmp-fixture"
include(":hidden-api", ":shared", ":android-library", ":app")
