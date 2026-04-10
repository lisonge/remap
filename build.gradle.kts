import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "li.songe.remap"
    version = "0.1.1"
}

subprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.plugin.KotlinBasePluginWrapper> {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
                languageVersion.set(KotlinVersion.KOTLIN_2_1)
                apiVersion.set(KotlinVersion.KOTLIN_2_1)
            }
        }
    }
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            coordinates(project.group.toString(), project.name, project.version.toString())
            if (properties.contains("signing.keyId")) {
                publishToMavenCentral()
                signAllPublications()
            }
            val repoUrl = "https://github.com/lisonge/remap"
            pom {
                name.set("Remap")
                description.set("Remap library")
                url.set(repoUrl)
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        name.set("lisonge")
                        email.set("i@songe.li")
                        url.set("https://github.com/lisonge")
                    }
                }
                scm {
                    url.set(repoUrl)
                }
            }
        }
    }
}
