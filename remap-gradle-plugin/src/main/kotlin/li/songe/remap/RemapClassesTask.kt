package li.songe.remap

import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.work.InputChanges

@CacheableTask
abstract class RemapClassesTask : BaseRemapClassesTask() {
    @get:Internal abstract val inputJars: ListProperty<RegularFile>
    @get:Internal abstract val inputDirectories: ListProperty<Directory>
    @get:OutputFile abstract val outputJar: RegularFileProperty

    init {
        // Scoped Artifacts requires ListProperty inputs; InputChanges needs a FileCollection.
        classes.from(inputJars, inputDirectories)
    }

    @TaskAction
    fun transform(changes: InputChanges) = transformClasses(
        changes, inputJars.get().map { it.asFile }, inputDirectories.get().map { it.asFile },
        outputJar.get().asFile, directoryOutput = false,
    )
}
