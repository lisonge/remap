package li.songe.remap

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal fun classBytes(name: String = "fixture/Caller", body: ClassWriter.() -> Unit = {}): ByteArray =
    ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        body()
        visitEnd()
    }.toByteArray()

internal fun writeTestJar(file: File, vararg entries: Pair<String, ByteArray>) {
    file.parentFile.mkdirs()
    ZipOutputStream(file.outputStream()).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}

internal fun remapClasses(
    jars: List<File>, directories: List<File>, index: RemapIndex, output: File, directoryOutput: Boolean = false,
) {
    val state = Files.createTempDirectory(output.parentFile.toPath(), "remap-state-").toFile()
    try {
        RemapClassCache(state).update(jars, directories, index, output, null, directoryOutput)
    } finally {
        state.deleteRecursively()
    }
}

internal fun assertSameClassOutputs(expected: File, actual: File, directoryOutput: Boolean) {
    if (!directoryOutput) {
        // Includes deterministic JAR entry order, timestamps and compression.
        assertContentEquals(expected.readBytes(), actual.readBytes())
        return
    }
    fun entries(root: File) = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }
    val expectedEntries = entries(expected)
    val actualEntries = entries(actual)
    assertEquals(expectedEntries.keys, actualEntries.keys)
    expectedEntries.forEach { (name, bytes) -> assertContentEquals(bytes, actualEntries.getValue(name), name) }
}
