plugins {
    id("li.songe.remap")
    id("com.android.library")
}
android {
    namespace = "fixture.legacy"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
dependencies { remapApi(project(":hidden-api")) }
