buildscript {
    dependencies {
        classpath(files(providers.gradleProperty("pluginClasspath").get().split(java.io.File.pathSeparator)))
    }
}

plugins {
    id("com.android.library") apply false
    id("com.android.application") apply false
    id("com.android.kotlin.multiplatform.library") apply false
    id("org.jetbrains.kotlin.multiplatform") apply false
}
