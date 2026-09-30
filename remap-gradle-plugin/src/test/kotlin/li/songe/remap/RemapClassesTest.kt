package li.songe.remap

import org.junit.jupiter.api.io.TempDir
import org.gradle.api.file.FileType
import org.gradle.work.ChangeType
import org.gradle.work.FileChange
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RemapClassesTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `classpath remove and add events at the same path use the final filesystem state`() {
        val present = File(directory, "Present.class").apply { writeBytes(classBytes("fixture/Present")) }
        val removed = File(directory, "Removed.class")
        fun event(source: File, change: ChangeType) = object : FileChange {
            override fun getFile() = source
            override fun getChangeType() = change
            override fun getFileType() = FileType.FILE
            override fun getNormalizedPath() = source.name
        }
        val events = listOf(event(present, ChangeType.ADDED), event(present, ChangeType.REMOVED), event(removed, ChangeType.REMOVED))
        val expected = mapOf(present.absolutePath to true, removed.absolutePath to false)
        assertEquals(expected, collectSourceChanges(events))
        assertEquals(expected, collectSourceChanges(events.reversed()))
    }

    @Test
    fun `merges jar and directory classes reproducibly and preserves Kotlin module resources`() {
        val classes = File(directory, "classes").apply { mkdirs() }
        val source = File(classes, "Source.class").apply { writeBytes(classBytes("fixture/Source")) }
        val jar = File(directory, "input.jar")
        writeTestJar(jar, "fixture/Source.class" to source.readBytes(),
            "META-INF/fixture.kotlin_module" to byteArrayOf(1, 2, 3))
        val output = File(directory, "output.jar")
        val index = RemapIndex(mapOf("fixture/Source" to "fixture/Target"), emptyMap())
        remapClasses(listOf(jar), listOf(classes), index, output)
        val first = output.readBytes()
        remapClasses(listOf(jar), listOf(classes), index, output)
        assertContentEquals(first, output.readBytes())
        ZipFile(output).use {
            assertEquals(2, it.size())
            assertEquals("fixture/Target", ClassReader(it.getInputStream(it.getEntry("fixture/Target.class"))).className)
            assertContentEquals(byteArrayOf(1, 2, 3), it.getInputStream(it.getEntry("META-INF/fixture.kotlin_module")).readBytes())
        }
        // A new invocation must not leave classes from previous inputs behind.
        remapClasses(emptyList(), emptyList(), index, output)
        ZipFile(output).use { assertFalse(it.entries().hasMoreElements()) }
    }

    @Test
    fun `conflicting merged resources fail instead of silently overwriting`() {
        val first = File(directory, "first").apply { mkdirs() }
        val second = File(directory, "second").apply { mkdirs() }
        File(first, "resource.txt").writeText("first")
        File(second, "resource.txt").writeText("second")
        assertFailsWith<IllegalArgumentException> {
            remapClasses(emptyList(), listOf(first, second), RemapIndex(emptyMap(), emptyMap()), File(directory, "out.jar"))
        }
    }

    @Test
    fun `incremental output matches full rebuild after changes removal mapping changes and lost state`() {
        listOf(false, true).forEach { directoryOutput ->
            val scenario = File(directory, if (directoryOutput) "directory" else "jar").apply { mkdirs() }
            val classes = File(scenario, "classes").apply { mkdirs() }
            val first = File(classes, "First.class").apply { writeBytes(classBytes("fixture/First")) }
            val second = File(classes, "Second.class").apply { writeBytes(classBytes("fixture/Second")) }
            val state = File(scenario, "state")
            val cache = RemapClassCache(state)
            val output = File(scenario, "incremental.jar")
            val full = File(scenario, "full.jar")
            var index = RemapIndex(mapOf("fixture/First" to "fixture/Mapped"), emptyMap())
            fun update(changes: Map<String, Boolean>?, expectedCount: Int) {
                assertEquals(expectedCount, cache.update(emptyList(), listOf(classes), index, output, changes, directoryOutput).scanned)
                remapClasses(emptyList(), listOf(classes), index, full, directoryOutput)
                assertSameClassOutputs(full, output, directoryOutput)
            }
            update(null, 2)
            // A changed internal class name must remove its previous mapped ZIP entry.
            first.writeBytes(classBytes("fixture/Renamed"))
            update(mapOf(first.absolutePath to true), 1)
            second.delete()
            update(mapOf(second.absolutePath to false), 0)
            index = RemapIndex(mapOf("fixture/Renamed" to "fixture/NewMapping"), emptyMap())
            update(null, 1)
            state.deleteRecursively()
            update(emptyMap(), 1)
            File(state, "manifest-v3").writeBytes(byteArrayOf(0))
            update(emptyMap(), 1)
            state.listFiles()!!.single { it.extension == "class" }.delete()
            update(emptyMap(), 1)
            first.delete()
            update(mapOf(first.absolutePath to false), 0)
        }
    }

    @Test
    fun `failed incremental update invalidates state before retry`() {
        val classes = File(directory, "classes").apply { mkdirs() }
        val first = File(classes, "First.class").apply { writeBytes(classBytes("fixture/First")) }
        val second = File(classes, "Second.class").apply { writeBytes(classBytes("fixture/Second")) }
        val cache = RemapClassCache(File(directory, "state"))
        val output = File(directory, "out.jar")
        val index = RemapIndex(emptyMap(), emptyMap())
        cache.update(emptyList(), listOf(classes), index, output, null)
        second.writeBytes(ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/First", null, "java/lang/Number", null)
            visitEnd()
        }.toByteArray())
        assertFailsWith<IllegalArgumentException> {
            cache.update(emptyList(), listOf(classes), index, output, mapOf(second.absolutePath to true))
        }
        second.writeBytes(classBytes("fixture/Second"))
        // Even if Gradle supplies only one changed source, an interrupted cache is fully rebuilt.
        assertEquals(2, cache.update(emptyList(), listOf(classes), index, output, mapOf(second.absolutePath to true)).scanned)
        ZipFile(output).use { zip ->
            assertContentEquals(first.readBytes(), zip.getInputStream(zip.getEntry("fixture/First.class")).readBytes())
        }
    }

    @Test
    fun `relocated classpath roots discard absolute source identities`() {
        val old = File(directory, "old").apply { mkdirs() }
        File(old, "Source.class").writeBytes(classBytes("fixture/Source"))
        val cache = RemapClassCache(File(directory, "state"))
        val output = File(directory, "out.jar")
        val index = RemapIndex(emptyMap(), emptyMap())
        cache.update(emptyList(), listOf(old), index, output, null)
        val moved = File(directory, "moved")
        old.copyRecursively(moved)
        assertEquals(1, cache.update(emptyList(), listOf(moved), index, output, emptyMap()).scanned)
        val source = File(moved, "Source.class").apply { writeBytes(classBytes("fixture/Renamed")) }
        cache.update(emptyList(), listOf(moved), index, output, mapOf(source.absolutePath to true))
        ZipFile(output).use { assertEquals(listOf("fixture/Renamed.class"), it.entries().asSequence().map { entry -> entry.name }.toList()) }
    }

    @Test
    fun `changed jars replace all previous contributions and retain identical duplicate classes`() {
        val classes = File(directory, "classes").apply { mkdirs() }
        File(classes, "Keep.class").writeBytes(classBytes("fixture/Keep"))
        val jar = File(directory, "input.jar")
        fun writeJar(vararg names: String) = writeTestJar(jar,
            *names.map { "$it.class" to classBytes(it) }.toTypedArray())
        val cache = RemapClassCache(File(directory, "state"))
        val output = File(directory, "out.jar")
        val index = RemapIndex(emptyMap(), emptyMap())
        writeJar("fixture/Keep", "fixture/Remove")
        cache.update(listOf(jar), listOf(classes), index, output, null)
        writeJar("fixture/Added")
        assertEquals(1, cache.update(listOf(jar), listOf(classes), index, output, mapOf(jar.absolutePath to true)).scanned)
        ZipFile(output).use { zip ->
            assertEquals(setOf("fixture/Keep.class", "fixture/Added.class"), zip.entries().asSequence().map { it.name }.toSet())
        }
        jar.delete()
        assertEquals(0, cache.update(emptyList(), listOf(classes), index, output, mapOf(jar.absolutePath to false)).scanned)
        ZipFile(output).use { assertEquals(1, it.size()) }
    }

}
