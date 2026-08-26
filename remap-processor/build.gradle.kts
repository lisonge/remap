plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

dependencies {
    implementation(project(":remap-annotation"))
    implementation(project(":remap-shared"))

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
