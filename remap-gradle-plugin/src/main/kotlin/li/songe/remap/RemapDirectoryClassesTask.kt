package li.songe.remap

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.work.InputChanges

@CacheableTask
abstract class RemapDirectoryClassesTask : BaseRemapClassesTask() {
    @get:Internal abstract val inputJars: ConfigurableFileCollection
    @get:Internal abstract val inputDirectories: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    // AGP expects both outputs. Input JARs are expanded into outputDirectory.
    @get:OutputDirectory abstract val outputJars: DirectoryProperty

    init { classes.from(inputJars, inputDirectories) }

    @TaskAction
    fun transform(changes: InputChanges) {
        outputJars.get().asFile.mkdirs()
        transformClasses(changes, inputJars.files.toList(), inputDirectories.files.toList(),
            outputDirectory.get().asFile, directoryOutput = true)
    }
}
