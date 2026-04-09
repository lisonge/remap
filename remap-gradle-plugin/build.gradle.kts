plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

dependencies {
    implementation(project(":remap-shared"))
    compileOnly(libs.agp.api)
    implementation(libs.asm.commons)
}

gradlePlugin {
    plugins {
        create("remap") {
            id = project.group.toString()
            displayName = "Remap Gradle Plugin"
            description = "A plugin for Remap Api"
            implementationClass = "$id.RemapPlugin"
        }
    }
}
