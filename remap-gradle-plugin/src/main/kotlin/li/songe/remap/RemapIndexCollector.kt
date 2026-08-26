package li.songe.remap

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal object RemapIndexCollector {
    private const val MAX_TOTAL_INDEX_BYTES = 8 * 1024 * 1024

    fun collect(files: Iterable<File>): RemapIndex {
        val builder = RemapIndexBuilder()
        val budget = IndexBudget()
        val artifacts = files.sortedBy(File::getAbsolutePath)
        var indexFound = false
        artifacts.forEach { file ->
            indexFound = when {
                file.isDirectory -> collectDirectory(file, builder, budget)
                file.isFile && file.extension.lowercase() in setOf("jar", "zip", "aar") -> {
                    collectArchive(file, builder, budget)
                }
                else -> false
            } || indexFound
        }
        require(indexFound) {
            "Remap index $REMAP_INDEX_PATH was not found in ${
                artifacts.joinToString { it.absolutePath }
            }"
        }
        return builder.build()
    }

    private fun collectDirectory(
        directory: File,
        builder: RemapIndexBuilder,
        budget: IndexBudget,
    ): Boolean {
        val indexFile = directory.resolve(REMAP_INDEX_PATH)
        if (!indexFile.isFile) return false
        val source = indexFile.absolutePath
        val text = indexFile.inputStream().use { budget.readIndexText(it, source) }
        builder.add(parseRemapIndex(text, source))
        return true
    }

    private fun collectArchive(
        archive: File,
        builder: RemapIndexBuilder,
        budget: IndexBudget,
    ): Boolean {
        var indexFound = false
        ZipFile(archive).use { zip ->
            val archiveSource = archive.absolutePath
            zip.singleEntryOrNull(REMAP_INDEX_PATH, archiveSource)?.let { entry ->
                val source = "$archiveSource!/$REMAP_INDEX_PATH"
                val text = zip.getInputStream(entry).use { budget.readIndexText(it, source) }
                builder.add(parseRemapIndex(text, source))
                indexFound = true
            }
            if (archive.extension.equals("aar", ignoreCase = true)) {
                zip.singleEntryOrNull("classes.jar", archiveSource)?.let { entry ->
                    zip.getInputStream(entry).use { input ->
                        ZipInputStream(input).use { classes ->
                            var nestedIndexFound = false
                            while (true) {
                                val classEntry = classes.nextEntry ?: break
                                if (classEntry.name == REMAP_INDEX_PATH) {
                                    val source = "$archiveSource!/classes.jar!/$REMAP_INDEX_PATH"
                                    require(!nestedIndexFound) {
                                        "Duplicate archive entry $source"
                                    }
                                    val text = budget.readIndexText(classes, source)
                                    builder.add(
                                        parseRemapIndex(text, source),
                                    )
                                    nestedIndexFound = true
                                    indexFound = true
                                }
                            }
                        }
                    }
                }
            }
        }
        return indexFound
    }

    private fun ZipFile.singleEntryOrNull(name: String, source: String): ZipEntry? {
        var result: ZipEntry? = null
        entries().asSequence().filter { it.name == name }.forEach { entry ->
            require(result == null) {
                "Duplicate archive entry $source!/$name"
            }
            result = entry
        }
        return result
    }

    private class IndexBudget {
        private var remainingBytes = MAX_TOTAL_INDEX_BYTES

        fun readIndexText(input: InputStream, source: String): String {
            val bytes = input.readNBytes(remainingBytes + 1)
            require(bytes.size <= remainingBytes) {
                "Remap indexes exceed $MAX_TOTAL_INDEX_BYTES bytes at $source"
            }
            remainingBytes -= bytes.size
            return bytes.toString(Charsets.UTF_8)
        }
    }
}
