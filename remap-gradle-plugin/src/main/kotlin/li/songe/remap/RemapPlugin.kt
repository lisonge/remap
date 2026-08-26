package li.songe.remap

import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

@Suppress("unused", "UnstableApiUsage")
class RemapPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        if (!project.plugins.hasPlugin("com.android.base")) {
            throw GradleException("li.songe.remap plugin must be applied after com.android.application or com.android.library")
        }
        val remapApi = project.createRemapApiConfiguration()
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)

        components.onVariants(components.selector().all()) { variant ->
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
                    project.layout.buildDirectory.file(
                        "intermediates/remap/${variant.name}/index-v1.txt",
                    ),
                )
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
}
