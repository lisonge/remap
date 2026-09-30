plugins {
    id("li.songe.remap")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}
kotlin {
    android {
        namespace = "fixture.shared"
        compileSdk = 36
        minSdk = 23
        localDependencySelection { selectBuildTypeFrom.set(listOf("release")) }
    }
    jvm()
    jvmToolchain(21)
}
dependencies { remapApi(project(":hidden-api")) }

// Configuration-time assertions also run when Gradle calculates the task graph.
afterEvaluate {
    check(configurations.getByName("androidMainCompileOnly").allDependencies.any { it.name == "hidden-api" })
    listOf("commonMainCompileOnly", "jvmMainCompileOnly").forEach { name ->
        check(configurations.getByName(name).allDependencies.none { it.name == "hidden-api" })
    }
    configurations.filter { it.name.endsWith("RuntimeClasspath", ignoreCase = true) }.forEach {
        check(it.allDependencies.none { dependency -> dependency.name == "hidden-api" })
    }
}
