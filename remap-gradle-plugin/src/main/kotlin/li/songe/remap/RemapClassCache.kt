package li.songe.remap

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class RemapClassStats(
    val totalClasses: Int,
    val scanned: Int,
    val candidates: Int,
    val rewritten: Int,
    val cachedClasses: Int,
    val scanNanos: Long,
    val asmNanos: Long,
    val outputNanos: Long,
) {
    override fun toString(): String = "classes=$totalClasses, scanned=$scanned, candidates=$candidates, " +
        "rewritten=$rewritten, cached=$cachedClasses; scan=${scanNanos / 1_000_000} ms, " +
        "asm=${asmNanos / 1_000_000} ms, output=${outputNanos / 1_000_000} ms;"
}

/** Raw entries reference compiler outputs; only genuinely remapped bytes are cached. */
internal class RemapClassCache(private val directory: File) {
    private data class Entry(
        val name: String,
        val jarEntry: String?,
        val hash: String,
        val size: Long,
        val remapped: Boolean,
    ) {
        val cacheName get() = "$hash.class"
    }
    private data class Source(val path: String, val entry: Entry)
    private val manifest = File(directory, "manifest-v3")

    private fun readState(): MutableMap<String, List<Entry>>? = try {
        if (!manifest.isFile) null else DataInputStream(manifest.inputStream().buffered()).use { input ->
            require(input.readInt() == 3)
            val result = mutableMapOf<String, List<Entry>>()
            repeat(input.readInt()) {
                val path = input.readUTF()
                result[path] = List(input.readInt()) {
                    Entry(input.readUTF(), input.readUTF().ifEmpty { null }, input.readUTF(), input.readLong(), input.readBoolean())
                }
            }
            if (result.values.asSequence().flatten().filter { it.remapped }.all {
                File(directory, it.cacheName).let { f -> f.isFile && f.length() == it.size }
            }) result else null
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    // Null changes requests a full rebuild, including after a mapping/index change.
    fun update(jars: List<File>, directories: List<File>, index: RemapIndex, output: File,
               changes: Map<String, Boolean>?, directoryOutput: Boolean = false): RemapClassStats {
        val jarPaths = jars.map { it.absolutePath }.toSet()
        val roots = directories.map { it.toPath() }
        // Classpath normalization ignores absolute roots, unlike local manifest keys.
        val previous = if (changes != null) readState()?.takeIf { cached ->
            cached.keys.all { path ->
                path in changes || path in jarPaths || roots.any { File(path).toPath().startsWith(it) }
            }
        } else null
        val oldEntries = if (directoryOutput && previous != null)
            previous.values.asSequence().flatten().associateBy { it.name } else null
        val state = previous ?: mutableMapOf()
        if (previous == null) {
            check(!directory.exists() || directory.deleteRecursively()) { "Cannot reset Remap state: $directory" }
        }
        directory.mkdirs()
        // Failure or interruption must force a fresh rebuild on the next invocation.
        check(!manifest.exists() || manifest.delete()) { "Cannot invalidate Remap state: $manifest" }
        val updates = if (previous == null) buildMap {
            jars.forEach { put(it.absolutePath, true) }
            directories.forEach { root -> root.walkTopDown().filter { it.isFile }.forEach { put(it.absolutePath, true) } }
        } else changes!!
        val converter = Converter(index)
        updateSources(state, updates, jarPaths, directories, converter)
        val entries = collectEntries(state)
        val outputStart = System.nanoTime()
        writeOutput(entries, output, directoryOutput, oldEntries)
        val outputNanos = System.nanoTime() - outputStart
        val cachedClasses = saveState(state)
        return RemapClassStats(entries.keys.count { it.endsWith(".class") }, converter.scanned,
            converter.candidates, converter.rewritten, cachedClasses, converter.scanNanos, converter.asmNanos, outputNanos)
    }

    private fun updateSources(
        state: MutableMap<String, List<Entry>>, updates: Map<String, Boolean>, jarPaths: Set<String>,
        directories: List<File>, converter: Converter,
    ) {
        updates.forEach { (path, exists) ->
            state.remove(path)
            if (exists) {
                val file = File(path)
                state[path] = if (path in jarPaths) ZipFile(file).use { zip ->
                    zip.entries().asSequence().filterNot { it.isDirectory }.map { entry ->
                        converter.convert(entry.name, zip.getInputStream(entry).use { it.readBytes() }, entry.name)
                    }.toList()
                } else directories.filter { file.toPath().startsWith(it.toPath()) }.map { root ->
                    converter.convert(file.relativeTo(root).invariantSeparatorsPath, file.readBytes(), null)
                }
            }
        }
    }

    private fun collectEntries(state: Map<String, List<Entry>>): Map<String, Source> {
        val entries = sortedMapOf<String, Source>()
        state.forEach { (path, values) -> values.forEach { entry ->
            val old = entries.putIfAbsent(entry.name, Source(path, entry))
            require(old == null || old.entry.hash == entry.hash) {
                "Conflicting project class/resource in Remap inputs: ${entry.name}"
            }
        } }
        return entries
    }

    private fun writeOutput(
        entries: Map<String, Source>, output: File, directoryOutput: Boolean, oldEntries: Map<String, Entry>?,
    ) {
        val openJars = mutableMapOf<String, ZipFile>()
        fun open(source: Source) = with(source) {
            when {
                entry.remapped -> File(directory, entry.cacheName).inputStream()
                entry.jarEntry != null -> {
                    val jar = openJars.getOrPut(path) { ZipFile(path) }
                    jar.getInputStream(requireNotNull(jar.getEntry(entry.jarEntry)))
                }
                else -> File(path).inputStream()
            }
        }
        val copyBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
        fun copy(source: Source, target: java.io.OutputStream) {
            open(source).use { stream ->
                var size = stream.read(copyBuffer)
                while (size >= 0) {
                    target.write(copyBuffer, 0, size)
                    size = stream.read(copyBuffer)
                }
            }
        }
        try {
            if (directoryOutput) writeDirectory(entries, output, oldEntries, ::copy)
            else writeJar(entries, output, ::copy)
        } finally {
            openJars.values.forEach { it.close() }
        }
    }

    private fun writeDirectory(
        entries: Map<String, Source>, output: File, oldEntries: Map<String, Entry>?,
        copy: (Source, java.io.OutputStream) -> Unit,
    ) {
        val root = output.toPath().toAbsolutePath().normalize()
        fun target(name: String): File {
            val path = root.resolve(name).normalize()
            require(path.startsWith(root) && path != root && '\\' !in name &&
                name.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                "Invalid Remap output entry: $name"
            }
            return path.toFile()
        }
        // Validate every name before changing the output tree (including JAR resources).
        entries.keys.forEach(::target)
        if (oldEntries == null) {
            check(!output.exists() || output.deleteRecursively()) { "Cannot reset Remap output: $output" }
        } else {
            (oldEntries.keys - entries.keys).forEach { name ->
                val file = target(name)
                check(!file.exists() || file.delete()) { "Cannot delete stale Remap output: $file" }
                var parent = file.parentFile
                while (parent.toPath() != root && parent.list()?.isEmpty() == true) {
                    check(parent.delete())
                    parent = parent.parentFile
                }
            }
        }
        output.mkdirs()
        entries.values.forEach { source ->
            // Leave identical outputs untouched, preserving downstream file snapshots.
            if (oldEntries == null || oldEntries[source.entry.name]?.hash != source.entry.hash) {
                val file = target(source.entry.name)
                file.parentFile.mkdirs()
                file.outputStream().use { copy(source, it) }
            }
        }
    }

    private fun writeJar(entries: Map<String, Source>, output: File, copy: (Source, java.io.OutputStream) -> Unit) {
        output.parentFile.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            entries.values.forEach { source ->
                zip.putNextEntry(ZipEntry(source.entry.name).apply { time = 0 })
                copy(source, zip)
                zip.closeEntry()
            }
        }
    }

    private fun saveState(state: Map<String, List<Entry>>): Int {
        val live = state.values.asSequence().flatten().filter { it.remapped }.mapTo(hashSetOf()) { it.cacheName }
        directory.listFiles()!!.filter { it.extension == "class" && it.name !in live }.forEach { check(it.delete()) }
        DataOutputStream(manifest.outputStream().buffered()).use { data ->
            data.writeInt(3)
            data.writeInt(state.size)
            state.forEach { (path, values) ->
                data.writeUTF(path)
                data.writeInt(values.size)
                values.forEach {
                    data.writeUTF(it.name)
                    data.writeUTF(it.jarEntry.orEmpty())
                    data.writeUTF(it.hash)
                    data.writeLong(it.size)
                    data.writeBoolean(it.remapped)
                }
            }
        }
        return live.size
    }

    private inner class Converter(index: RemapIndex) {
        val filter = RemapClassFilter(index)
        val remapper = TrackingRemapper(index)
        val digest = MessageDigest.getInstance("SHA-256")
        var scanned = 0
        var candidates = 0
        var rewritten = 0
        var scanNanos = 0L
        var asmNanos = 0L
        fun convert(name: String, bytes: ByteArray, jarEntry: String?): Entry {
            var outputName = name
            var outputBytes = bytes
            var remapped = false
            if (name.endsWith(".class")) {
                scanned++
                val scanStart = System.nanoTime()
                val reader = ClassReader(bytes)
                outputName = reader.className + ".class"
                val candidate = filter.mayRemap(bytes, reader)
                scanNanos += System.nanoTime() - scanStart
                if (candidate) {
                    candidates++
                    val asmStart = System.nanoTime()
                    remapper.reset()
                    val writer = ClassWriter(0)
                    reader.accept(ClassRemapper(writer, remapper), 0)
                    outputName = remapper.mapType(reader.className) + ".class"
                    if (remapper.changed) {
                        val converted = writer.toByteArray()
                        if (!converted.contentEquals(bytes)) {
                            outputBytes = converted
                            remapped = true
                            rewritten++
                        }
                    }
                    asmNanos += System.nanoTime() - asmStart
                }
            }
            val hash = HexFormat.of().formatHex(digest.digest(outputBytes))
            val entry = Entry(outputName, jarEntry, hash, outputBytes.size.toLong(), remapped)
            if (remapped) {
                // Digest filenames cannot escape the state directory, even with arbitrary ZIP names.
                val file = File(directory, entry.cacheName)
                if (!file.isFile) file.writeBytes(outputBytes)
            }
            return entry
        }
    }
}
