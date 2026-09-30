package li.songe.remap

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.FileType
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import org.gradle.work.FileChange
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import java.io.File

@DisableCachingByDefault(because = "Concrete tasks declare outputs and opt into caching")
abstract class BaseRemapClassesTask : DefaultTask() {
    @get:Classpath @get:Incremental abstract val classes: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val indexFile: RegularFileProperty
    @get:LocalState abstract val stateDirectory: DirectoryProperty

    init {
        stateDirectory.convention(project.layout.buildDirectory.dir("intermediates/remap/$name/classes-state"))
    }

    protected fun transformClasses(
        changes: InputChanges, jars: List<File>, directories: List<File>, output: File, directoryOutput: Boolean,
    ) {
        val started = System.nanoTime()
        val changed = if (changes.isIncremental) collectSourceChanges(changes.getFileChanges(classes)) else null
        val stats = RemapClassCache(stateDirectory.get().asFile).update(
            jars, directories, parseRemapIndex(indexFile.get().asFile.readText(Charsets.UTF_8)),
            output, changed, directoryOutput,
        )
        logger.info("Remap{}: {} total={} ms", if (directoryOutput) " directory" else "", stats,
            (System.nanoTime() - started) / 1_000_000)
    }
}

internal fun collectSourceChanges(changes: Iterable<FileChange>): Map<String, Boolean> = changes
    .filter { it.fileType != FileType.DIRECTORY }
    .map { it.file }.distinct()
    // Classpath normalization can report both ADDED and REMOVED for the same
    // physical path (observed after Kotlin outputs are restored from build cache).
    // Their order is not meaningful; transform the file if it currently exists.
    .associate { it.absolutePath to it.isFile }

