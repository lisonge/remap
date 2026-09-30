plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

dependencies {
    implementation(project(":remap-shared"))
    compileOnly(libs.agp.api)
    implementation(libs.asm.commons)

    testImplementation(project(":remap-annotation"))
    testImplementation(libs.agp.api)
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

val fixtureProcessor by configurations.creating
dependencies {
    fixtureProcessor(project(":remap-processor"))
}

gradlePlugin {
    plugins {
        create(rootProject.name) {
            id = project.group.toString()
            displayName = "Remap Gradle Plugin"
            description = "A plugin for Remap Api"
            implementationClass = "$id.RemapPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}

tasks.register<Test>("integrationTest") {
    description = "Builds Android and KMP fixtures; requires -PandroidSdk or ANDROID_HOME."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    dependsOn(fixtureProcessor)
    inputs.dir("src/test/fixtures/kmp")
    inputs.files(fixtureProcessor).withPropertyName("fixtureProcessor").withNormalizer(ClasspathNormalizer::class)
    systemProperty("remap.fixtureProcessor", fixtureProcessor.asPath)
    systemProperty("remap.agpVersion", providers.gradleProperty("testAgpVersion").getOrElse(libs.versions.agp.get()))
    systemProperty("remap.kotlinVersion", providers.gradleProperty("testKotlinVersion").getOrElse(libs.versions.kotlin.get()))
    systemProperty("remap.gradleVersion", providers.gradleProperty("testGradleVersion").getOrElse(""))
    systemProperty("remap.androidSdk", providers.gradleProperty("androidSdk")
        .orElse(providers.environmentVariable("ANDROID_HOME"))
        .orElse(providers.environmentVariable("ANDROID_SDK_ROOT")).getOrElse(""))
    maxHeapSize = "1g"
    testLogging { showStandardStreams = true }
}
