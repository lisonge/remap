package li.songe.remap

import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.*

class RemapDirectoryTest {
    @TempDir lateinit var directory: File

    private fun bytes(name: String, fieldType: String = "java/lang/String") = classBytes(name) {
        visitField(Opcodes.ACC_PUBLIC, "value", "L$fieldType;", null, null).visitEnd()
    }

    @Test
    fun `directory updates retain untouched files and remove renamed classes and resources`() {
        val input = File(directory, "input").apply { mkdirs() }
        val state = File(directory, "state")
        val output = File(directory, "output")
        val source = File(input, "Source.class").apply { writeBytes(bytes("fixture/Hidden")) }
        val raw = File(input, "Raw.class").apply { writeBytes(bytes("fixture/Raw")) }
        val resource = File(input, "META-INF/resource").apply { parentFile.mkdirs(); writeText("resource") }
        val index = RemapIndex(mapOf("fixture/Hidden" to "fixture/Target"), emptyMap())
        fun update(changes: Map<String, Boolean>?, mapping: RemapIndex = index) =
            RemapClassCache(state).update(emptyList(), listOf(input), mapping, output, changes, directoryOutput = true)
        update(null)
        assertTrue(File(output, "fixture/Target.class").isFile)
        val rawOutput = File(output, "fixture/Raw.class")
        assertContentEquals(raw.readBytes(), rawOutput.readBytes())
        val fixedTime = FileTime.fromMillis(1_000_000)
        Files.setLastModifiedTime(rawOutput.toPath(), fixedTime)
        source.writeBytes(bytes("fixture/Renamed", "fixture/Hidden"))
        resource.delete()
        val stats = update(mapOf(source.absolutePath to true, resource.absolutePath to false))
        assertEquals(1, stats.scanned)
        assertFalse(File(output, "fixture/Target.class").exists())
        assertFalse(File(output, "META-INF/resource").exists())
        assertTrue(File(output, "fixture/Renamed.class").isFile)
        assertEquals(fixedTime, Files.getLastModifiedTime(rawOutput.toPath()))
        // An interrupted/missing local manifest must rebuild, even when Gradle reports no changes.
        state.deleteRecursively()
        File(output, "stale.class").writeBytes(byteArrayOf())
        update(emptyMap())
        assertFalse(File(output, "stale.class").exists())
        update(null, RemapIndex(mapOf("fixture/Renamed" to "fixture/Other"), emptyMap()))
        assertFalse(File(output, "fixture/Renamed.class").exists())
        assertTrue(File(output, "fixture/Other.class").isFile)
    }

    @Test
    fun `changed and removed input jars reconcile directory entries and reject escaping resources`() {
        val jar = File(directory, "input.jar")
        val output = File(directory, "output")
        val state = File(directory, "state")
        fun write(vararg names: String) = writeTestJar(jar, *names.map { it to it.toByteArray() }.toTypedArray())
        fun update(jars: List<File>, changes: Map<String, Boolean>?) =
            RemapClassCache(state).update(jars, emptyList(), RemapIndex(emptyMap(), emptyMap()), output, changes, true)
        write("a", "b")
        update(listOf(jar), null)
        write("b", "c")
        update(listOf(jar), mapOf(jar.absolutePath to true))
        assertEquals(setOf("b", "c"), output.list()!!.toSet())
        jar.delete()
        update(emptyList(), mapOf(jar.absolutePath to false))
        assertTrue(output.list()!!.isEmpty())
        write("../escaped")
        assertFailsWith<IllegalArgumentException> { update(listOf(jar), null) }
        assertFalse(File(directory, "escaped").exists())
    }
}
