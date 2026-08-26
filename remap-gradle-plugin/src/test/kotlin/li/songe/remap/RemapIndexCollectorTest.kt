package li.songe.remap

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemapIndexCollectorTest {
    @Test
    fun `indexes in directory jar and aar are merged`() {
        val temporary = createTempDirectory("remap-index").toFile()
        try {
            val directory = temporary.resolve("classes").also { it.mkdirs() }
            directory.resolve(REMAP_INDEX_PATH).apply {
                parentFile.mkdirs()
                writeText(
                    RemapIndex(mapOf("test/Source" to "test/Target"), emptyMap()).encode(),
                    Charsets.UTF_8,
                )
            }
            val jar = temporary.resolve("methods.jar")
            writeZip(
                jar,
                REMAP_INDEX_PATH to RemapIndex(
                    emptyMap(),
                    mapOf("test/Source" to mapOf("sourceMethod" to "targetMethod")),
                ).encode().toByteArray(Charsets.UTF_8),
            )
            val nestedJar = zipBytes(
                REMAP_INDEX_PATH to RemapIndex(
                    mapOf("test/OtherSource" to "test/OtherTarget"),
                    emptyMap(),
                ).encode().toByteArray(Charsets.UTF_8),
            )
            val aar = temporary.resolve("types.aar")
            writeZip(aar, "classes.jar" to nestedJar)

            assertEquals(
                RemapIndex(
                    mapOf(
                        "test/OtherSource" to "test/OtherTarget",
                        "test/Source" to "test/Target",
                    ),
                    mapOf("test/Source" to mapOf("sourceMethod" to "targetMethod")),
                ),
                RemapIndexCollector.collect(listOf(directory, jar, aar)),
            )
        } finally {
            temporary.deleteRecursively()
        }
    }

    @Test
    fun `conflicting artifact indexes fail`() {
        val first = createTempDirectory("remap-index-first").toFile()
        val second = createTempDirectory("remap-index-second").toFile()
        try {
            listOf(first to "test/Target", second to "test/OtherTarget").forEach { (directory, target) ->
                directory.resolve(REMAP_INDEX_PATH).apply {
                    parentFile.mkdirs()
                    writeText(
                        RemapIndex(mapOf("test/Source" to target), emptyMap()).encode(),
                        Charsets.UTF_8,
                    )
                }
            }

            assertFailsWith<IllegalArgumentException> {
                RemapIndexCollector.collect(listOf(first, second))
            }
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    @Test
    fun `missing artifact index fails`() {
        val temporary = createTempDirectory("remap-index-missing").toFile()
        try {
            val exception = assertFailsWith<IllegalArgumentException> {
                RemapIndexCollector.collect(listOf(temporary))
            }
            assertEquals(true, exception.message?.contains(REMAP_INDEX_PATH))
        } finally {
            temporary.deleteRecursively()
        }
    }

    private fun writeZip(file: File, vararg entries: Pair<String, ByteArray>) {
        file.outputStream().use { output ->
            ZipOutputStream(output).use { zip -> writeEntries(zip, entries) }
        }
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip -> writeEntries(zip, entries) }
            output.toByteArray()
        }
    }

    private fun writeEntries(zip: ZipOutputStream, entries: Array<out Pair<String, ByteArray>>) {
        entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }
}
