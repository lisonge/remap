package li.songe.remap

import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemapSparseTest {
    @TempDir lateinit var directory: File

    private fun expected(input: ByteArray, index: RemapIndex): ByteArray = ClassWriter(0).also {
        ClassReader(input).accept(ClassRemapper(it, RemapRemapper(index)), 0)
    }.toByteArray()

    @Test
    fun `screening preserves all supported mapping locations including generic inner names and modified UTF8`() {
        val hidden = "fixture/Hidden"
        val owner = "fixture/Api"
        val unicode = "fixture/\uD801\uDC00Hidden"
        val index = RemapIndex(mapOf(hidden to "fixture/Target", "fixture/Outer\$Inner" to "fixture/Target", unicode to "fixture/Target"),
            mapOf(owner to mapOf("read" to "get")))
        val cases = listOf(
            classBytes(hidden),
            classBytes { visitField(Opcodes.ACC_PUBLIC, "field", "[[L$hidden;", null, null).visitEnd() },
            // The binary name Outer$Inner appears only in the index, not in this class's constant pool.
            classBytes { visitField(Opcodes.ACC_PUBLIC, "field", "Ljava/util/List;", "Ljava/util/List<Lfixture/Outer<Ljava/lang/String;>.Inner;>;", null).visitEnd() },
            classBytes { visitAnnotation("L$hidden;", true).visitEnd() },
            classBytes { visitAnnotation("Lfixture/Annotation;", true).apply { visit("value", Type.getObjectType(hidden)); visitEnd() } },
            classBytes { visitField(Opcodes.ACC_PUBLIC, "field", "L$unicode;", null, null).visitEnd() },
            classBytes {
                visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call", "()V", null, arrayOf(hidden)).apply {
                    visitCode()
                    visitMethodInsn(Opcodes.INVOKESTATIC, owner, "read", "()V", false)
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                }
            },
            classBytes(owner) { visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "read", "()V", null, null).visitEnd() },
            classBytes {
                visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call", "()V", null, null).apply {
                    visitCode()
                    visitLdcInsn(Handle(Opcodes.H_INVOKESTATIC, owner, "read", "()V", false))
                    visitInsn(Opcodes.POP)
                    visitLdcInsn(Type.getMethodType("(L$hidden;)V"))
                    visitInsn(Opcodes.POP)
                    visitLdcInsn(ConstantDynamic("value", "L$hidden;", Handle(Opcodes.H_INVOKESTATIC, owner, "read", "()Ljava/lang/Object;", false)))
                    visitInsn(Opcodes.POP)
                    visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", Handle(Opcodes.H_INVOKESTATIC, owner, "read", "()Ljava/lang/Object;", false), Type.getObjectType(hidden))
                    visitInsn(Opcodes.POP)
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(1, 0)
                    visitEnd()
                }
            },
            classBytes { visitNestHost(hidden); visitPermittedSubclass(hidden); visitRecordComponent("item", "L$hidden;", null).visitEnd() },
            classBytes { visitInnerClass("fixture/Outer\$Inner", "fixture/Outer", "Inner", Opcodes.ACC_PUBLIC) },
        )
        val root = File(directory, "input").apply { mkdirs() }
        val source = File(root, "Source.class")
        val output = File(directory, "out.jar")
        cases.forEachIndexed { i, input ->
            source.writeBytes(input)
            val stats = RemapClassCache(File(directory, "state")).update(emptyList(), listOf(root), index, output, null)
            assertEquals(1, stats.candidates, "case $i")
            assertEquals(1, stats.rewritten, "case $i")
            ZipFile(output).use { zip ->
                assertContentEquals(expected(input, index), zip.getInputStream(zip.entries().nextElement()).readBytes(), "case $i")
            }
        }
    }

    @Test
    fun `only changed bytecode is cached and raw bytes survive false positive hints`() {
        val root = File(directory, "input").apply { mkdirs() }
        val mapped = File(root, "Mapped.class").apply { writeBytes(classBytes("fixture/Mapped") {
            visitField(Opcodes.ACC_PUBLIC, "item", "Lfixture/Hidden;", null, null).visitEnd()
        }) }
        val plain = File(root, "Plain.class").apply { writeBytes(classBytes("fixture/Plain")) }
        // An unused UTF8 constant would be discarded by a ClassWriter rebuild; it must survive unchanged.
        val hint = File(root, "Hint.class").apply { writeBytes(classBytes("fixture/Hint") {
            newUTF8("fixture/Hidden")
            visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "text", "Ljava/lang/String;", null, "fixture/Hidden").visitEnd()
        }) }
        val resource = File(root, "resource.txt").apply { writeText("fixture/Hidden") }
        val index = RemapIndex(mapOf("fixture/Hidden" to "fixture/Target"), emptyMap())
        val state = File(directory, "state")
        val cache = RemapClassCache(state)
        val output = File(directory, "out.jar")
        val first = cache.update(emptyList(), listOf(root), index, output, null)
        assertEquals(3, first.totalClasses)
        assertEquals(3, first.scanned)
        assertEquals(2, first.candidates)
        assertEquals(1, first.rewritten)
        assertEquals(1, first.cachedClasses)
        assertEquals(1, state.listFiles()!!.count { it.extension == "class" })
        assertTrue(state.listFiles()!!.none { it.extension == "zip" })
        ZipFile(output).use { zip ->
            listOf(plain, hint).forEach { file ->
                val name = ClassReader(file.readBytes()).className + ".class"
                assertContentEquals(file.readBytes(), zip.getInputStream(zip.getEntry(name)).readBytes())
            }
            assertContentEquals(resource.readBytes(), zip.getInputStream(zip.getEntry("resource.txt")).readBytes())
        }
        // Removing the final hidden API reference must stop using and delete the stale cached class.
        mapped.writeBytes(classBytes("fixture/Mapped"))
        val next = cache.update(emptyList(), listOf(root), index, output, mapOf(mapped.absolutePath to true))
        assertEquals(1, next.scanned)
        assertEquals(0, next.candidates)
        assertEquals(0, next.cachedClasses)
        assertTrue(state.listFiles()!!.none { it.extension == "class" })
        ZipFile(output).use { zip -> assertContentEquals(mapped.readBytes(), zip.getInputStream(zip.getEntry("fixture/Mapped.class")).readBytes()) }
        // A new mapping must rescreen previously raw sources.
        val changedIndex = RemapIndex(mapOf("fixture/Plain" to "fixture/Renamed"), emptyMap())
        val changed = cache.update(emptyList(), listOf(root), changedIndex, output, null)
        assertEquals(1, changed.cachedClasses)
        ZipFile(output).use { assertTrue(it.getEntry("fixture/Renamed.class") != null) }
    }

    @Test
    fun `jar entries stream from their original archive unless actually remapped`() {
        val jar = File(directory, "input.jar")
        val plain = classBytes("fixture/Plain")
        val hidden = classBytes("fixture/Hidden")
        writeTestJar(jar, "fixture/Plain.class" to plain, "fixture/Hidden.class" to hidden,
            "META-INF/example.kotlin_module" to byteArrayOf(1, 2))
        val index = RemapIndex(mapOf("fixture/Hidden" to "fixture/Target"), emptyMap())
        val cache = RemapClassCache(File(directory, "state"))
        val output = File(directory, "out.jar")
        assertEquals(1, cache.update(listOf(jar), emptyList(), index, output, null).cachedClasses)
        val first = output.readBytes()
        val second = cache.update(listOf(jar), emptyList(), index, output, emptyMap())
        assertEquals(0, second.scanned)
        assertEquals(1, second.cachedClasses)
        assertContentEquals(first, output.readBytes())
        ZipFile(output).use { zip ->
            assertContentEquals(plain, zip.getInputStream(zip.getEntry("fixture/Plain.class")).readBytes())
            assertContentEquals(expected(hidden, index), zip.getInputStream(zip.getEntry("fixture/Target.class")).readBytes())
        }
    }
}
