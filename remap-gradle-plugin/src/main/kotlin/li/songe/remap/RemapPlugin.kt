package li.songe.remap

import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

@Suppress("unused")
class RemapPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        if (!project.plugins.hasPlugin("com.android.base")) {
            throw GradleException("li.songe.remap plugin must be applied after com.android.application or com.android.library")
        }
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        val isLibrary = components is LibraryAndroidComponentsExtension

        components.onVariants(components.selector().all()) { variant ->
            variant.instrumentation.transformClassesWith(
                RemapFactory::class.java,
                if (isLibrary) InstrumentationScope.PROJECT else InstrumentationScope.ALL,
            ) { }
        }
    }
}
