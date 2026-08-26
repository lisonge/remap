package li.songe.remap

import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemapConfigurationsTest {
    @Test
    fun `remap api supplies compile only and matching index artifact`() {
        val root = ProjectBuilder.builder().withName("root").build()
        val hiddenApi = ProjectBuilder.builder().withName("hidden-api").withParent(root).build()
        root.pluginManager.apply("java")
        val flavor = Attribute.of("test.flavor", String::class.java)
        val compileClasspath = root.configurations.getByName("compileClasspath")
        compileClasspath.attributes.attribute(flavor, "gkd")

        val remapApi = root.createRemapApiConfiguration()
        val dependency = root.dependencies.project(mapOf("path" to hiddenApi.path))
        remapApi.dependencies.add(dependency)
        val indexClasspath = root.createRemapIndexClasspath(
            "remapIndexClasspath",
            compileClasspath,
            remapApi,
        )

        assertFalse(remapApi.isCanBeResolved)
        assertFalse(remapApi.isCanBeConsumed)
        assertTrue(root.configurations.getByName("compileOnly").extendsFrom.contains(remapApi))
        assertTrue(root.configurations.getByName("compileOnly").allDependencies.contains(dependency))
        assertFalse((dependency as ProjectDependency).isTransitive)
        assertTrue(indexClasspath.isCanBeResolved)
        assertFalse(indexClasspath.isCanBeConsumed)
        assertFalse(indexClasspath.isTransitive)
        assertFalse(indexClasspath.extendsFrom.contains(remapApi))
        assertEquals("gkd", indexClasspath.attributes.getAttribute(flavor))
        assertEquals(
            ANDROID_CLASSES_JAR_TYPE,
            indexClasspath.attributes.getAttribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE),
        )
        assertEquals(
            ":hidden-api",
            (indexClasspath.allDependencies.single() as ProjectDependency).path,
        )
        assertFalse((indexClasspath.allDependencies.single() as ProjectDependency).isTransitive)
    }

    @Test
    fun `remap api requires exactly one project dependency`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        val remapApi = project.createRemapApiConfiguration()

        assertFailsWith<IllegalArgumentException> {
            project.createRemapIndexClasspath(
                "remapIndexClasspath",
                project.configurations.getByName("compileClasspath"),
                remapApi,
            )
        }
    }

    @Test
    fun `remap api rejects invalid and late dependencies`() {
        val invalidProject = ProjectBuilder.builder().build()
        invalidProject.pluginManager.apply("java")
        val invalidRemapApi = invalidProject.createRemapApiConfiguration()

        assertFailsWith<IllegalArgumentException> {
            invalidRemapApi.dependencies.add(invalidProject.dependencies.create("test:invalid:1"))
        }

        val validRoot = ProjectBuilder.builder().withName("valid-root").build()
        val validHiddenApi = ProjectBuilder.builder()
            .withName("hidden-api")
            .withParent(validRoot)
            .build()
        val validOtherApi = ProjectBuilder.builder()
            .withName("other-api")
            .withParent(validRoot)
            .build()
        validRoot.pluginManager.apply("java")
        val validRemapApi = validRoot.createRemapApiConfiguration()
        validRemapApi.dependencies.add(
            validRoot.dependencies.project(mapOf("path" to validHiddenApi.path)),
        )
        val selectedDependency = validRemapApi.dependencies.single()

        assertFailsWith<IllegalArgumentException> {
            validRemapApi.dependencies.remove(selectedDependency)
        }

        assertFailsWith<IllegalArgumentException> {
            validRemapApi.dependencies.add(
                validRoot.dependencies.project(mapOf("path" to validOtherApi.path)),
            )
        }
    }
}
