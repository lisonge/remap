package li.songe.remap

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer

internal const val REMAP_API_CONFIGURATION = "remapApi"
internal const val ANDROID_CLASSES_JAR_TYPE = "android-classes-jar"

internal fun Project.createRemapApiConfiguration(): Configuration {
    var selectedDependency: ProjectDependency? = null
    val remapApi = configurations.create(REMAP_API_CONFIGURATION) { configuration ->
        configuration.description = "Hidden API stubs and their Remap index"
        configuration.isCanBeConsumed = false
        configuration.isCanBeResolved = false
    }
    remapApi.dependencies.whenObjectAdded { dependency ->
        require(
            dependency is ProjectDependency &&
                selectedDependency == null &&
                remapApi.dependencies.size == 1,
        ) {
            "$REMAP_API_CONFIGURATION must declare exactly one project dependency"
        }
        dependency.isTransitive = false
        selectedDependency = dependency
    }
    remapApi.dependencies.whenObjectRemoved { dependency ->
        require(dependency !== selectedDependency) {
            "$REMAP_API_CONFIGURATION project dependency must not be removed or replaced"
        }
    }
    configurations.getByName("compileOnly").extendsFrom(remapApi)
    return remapApi
}

internal fun Project.createRemapIndexClasspath(
    name: String,
    variantClasspath: Configuration,
    remapApi: Configuration,
): Configuration {
    val remapDependency = remapApi.requireSingleProjectDependency()
    return configurations.create(name) { configuration ->
        configuration.description = "Compile artifact containing the configured Remap index"
        configuration.isCanBeConsumed = false
        configuration.isCanBeResolved = true
        configuration.isTransitive = false
        configuration.attributes.copyFrom(variantClasspath.attributes)
        configuration.attributes.attribute(
            ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
            ANDROID_CLASSES_JAR_TYPE,
        )
        configuration.dependencies.add(remapDependency.copy())
    }
}

private fun Configuration.requireSingleProjectDependency(): ProjectDependency {
    val dependency = dependencies.singleOrNull()
    require(dependency is ProjectDependency) {
        "$REMAP_API_CONFIGURATION must declare exactly one project dependency"
    }
    return dependency
}

internal fun AttributeContainer.copyFrom(source: AttributeContainer) {
    source.keySet().forEach { key -> copyAttribute(source, key) }
}

@Suppress("UNCHECKED_CAST")
private fun AttributeContainer.copyAttribute(source: AttributeContainer, key: Attribute<*>) {
    key as Attribute<Any>
    source.getAttribute(key)?.let { value -> attribute(key, value) }
}
