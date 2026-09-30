plugins {
    id("com.android.application")
    id("li.songe.remap")
}
android {
    namespace = "fixture.app"
    compileSdk = 36
    // API 24+ lets AGP dex project class directories without a desugaring classpath.
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies {
    implementation(project(":shared"))
    implementation(project(":android-library"))
    remapApi(project(":hidden-api"))
}
