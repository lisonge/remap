import java.io.File

plugins { id("com.android.library") }
android {
    namespace = "fixture.stubs"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
val processorFiles = files(providers.gradleProperty("processorClasspath").get().split(File.pathSeparator))
dependencies {
    compileOnly(processorFiles)
    annotationProcessor(processorFiles)
}

