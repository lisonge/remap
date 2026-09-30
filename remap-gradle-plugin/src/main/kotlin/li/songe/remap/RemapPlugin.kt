package li.songe.remap

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.api.variant.KotlinMultiplatformAndroidVariant
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.Variant
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

@Suppress("unused", "UnstableApiUsage")
class RemapPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val remapApi = project.createRemapApiConfiguration()
        var configured = false
        fun configure(compileOnlyName: String, components: AndroidComponentsExtension<*, *, *>) {
            check(!configured) { "Remap supports only one Android plugin per project" }
            configured = true
            project.connectRemapApi(remapApi, compileOnlyName)
            components.onVariants(components.selector().all()) { variant ->
                configureVariant(project, variant, remapApi)
            }
        }
        listOf("com.android.application", "com.android.library").forEach { pluginId ->
            project.pluginManager.withPlugin(pluginId) {
                configure("compileOnly", project.extensions.getByType(AndroidComponentsExtension::class.java))
            }
        }
        project.pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
            configure(
                "androidMainCompileOnly",
                project.extensions.getByType(KotlinMultiplatformAndroidComponentsExtension::class.java),
            )
        }
        project.afterEvaluate {
            if (!configured) {
                throw GradleException(
                    "li.songe.remap requires com.android.application, com.android.library, " +
                        "or com.android.kotlin.multiplatform.library",
                )
            }
        }
    }

    private fun configureVariant(project: Project, variant: Variant, remapApi: Configuration) {
        val indexClasspath = project.createRemapIndexClasspath(
            variant.computeTaskName("remap", "IndexClasspath"),
            variant.compileConfiguration,
            remapApi,
        )
        val indexTask = project.tasks.register(
            variant.computeTaskName("generate", "RemapIndex"),
            GenerateRemapIndexTask::class.java,
        ) { task ->
            task.artifacts.from(indexClasspath)
            task.outputFile.set(
                project.layout.buildDirectory.file("intermediates/remap/${variant.name}/index-v1.txt"),
            )
        }
        if (variant is KotlinMultiplatformAndroidVariant) {
            // AGP 9.2.1 and 9.4.1 expose KMP instrumentation without scheduling its ASM task.
            val artifacts = variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            val directoryTransform = RemapDirectoryTransform.find(artifacts)
            if (directoryTransform != null) {
                val transform = project.tasks.register(
                    variant.computeTaskName("transform", "RemapClasses"),
                    RemapDirectoryClassesTask::class.java,
                ) { task -> task.indexFile.set(indexTask.flatMap { it.outputFile }) }
                directoryTransform.wire(transform)
                return
            }
            project.logger.warn("Remap: AGP directory transform unavailable; using the compatible JAR transform for ${variant.name}")
            val transform = project.tasks.register(
                variant.computeTaskName("transform", "RemapClasses"),
                RemapClassesTask::class.java,
            ) { task ->
                task.indexFile.set(indexTask.flatMap { it.outputFile })
            }
            artifacts.use(transform).toTransform(
                ScopedArtifact.CLASSES,
                RemapClassesTask::inputJars,
                RemapClassesTask::inputDirectories,
                RemapClassesTask::outputJar,
            )
            return
        }
        variant.instrumentation.transformClassesWith(
            RemapFactory::class.java,
            InstrumentationScope.PROJECT,
        ) { parameters ->
            parameters.indexContent.set(
                indexTask.flatMap { task ->
                    task.outputFile.map { it.asFile.readText(Charsets.UTF_8) }
                },
            )
        }
    }
}
