package li.songe.remap

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemapStubTest {

    @Test
    fun `factory has no serializable instance state`() {
        assertTrue(RemapFactory::class.java.declaredFields.all { Modifier.isStatic(it.modifiers) })
    }

    @Test
    fun `value fails when evaluated`() {
        val error = assertFailsWith<AssertionError> {
            RemapStub.value<Any>()
        }

        assertEquals("Remap stub was evaluated before bytecode remapping", error.message)
    }

    @Test
    fun `interface fields have no constant values`() {
        val fieldValues = readClass(RemapStubFixture::class.java).fieldValues()

        assertEquals(
            setOf(
                "BYTE_VALUE",
                "SHORT_VALUE",
                "INT_VALUE",
                "LONG_VALUE",
                "FLOAT_VALUE",
                "DOUBLE_VALUE",
                "CHAR_VALUE",
                "BOOLEAN_VALUE",
                "STRING_VALUE",
                "OBJECT_VALUE",
                "ARRAY_VALUE",
            ),
            fieldValues.keys,
        )
        fieldValues.values.forEach(::assertNull)
    }

    @Test
    fun `consumer field owners are remapped`() {
        val fixtureName = Type.getInternalName(RemapStubFixture::class.java)
        val targetName = "android/os/IBinder"
        val originalClass = readClass(RemapStubConsumer::class.java)

        assertEquals(
            listOf(
                FieldAccess(Opcodes.GETSTATIC, fixtureName, "INT_VALUE", "I"),
                FieldAccess(Opcodes.GETSTATIC, fixtureName, "STRING_VALUE", "Ljava/lang/String;"),
            ),
            originalClass.fieldAccesses(),
        )

        val index = RemapIndex(
            typeMappings = mapOf(fixtureName to targetName),
            methodMappings = emptyMap(),
        )
        val writer = ClassWriter(0)
        originalClass.accept(ClassRemapper(writer, RemapRemapper(index)), 0)

        assertEquals(
            listOf(
                FieldAccess(Opcodes.GETSTATIC, targetName, "INT_VALUE", "I"),
                FieldAccess(Opcodes.GETSTATIC, targetName, "STRING_VALUE", "Ljava/lang/String;"),
            ),
            ClassReader(writer.toByteArray()).fieldAccesses(),
        )
    }

    @Test
    fun `type and method remapping use the aggregate index`() {
        val sourceName = "test/Source"
        val missingName = "test/Missing"
        val remapper = RemapRemapper(
            RemapIndex(
                typeMappings = mapOf(sourceName to "test/Target"),
                methodMappings = mapOf(
                    sourceName to mapOf("sourceMethod" to "targetMethod"),
                ),
            ),
        )

        assertEquals("test/Target", remapper.map(sourceName))
        assertEquals("targetMethod", remapper.mapMethodName(sourceName, "sourceMethod", "()V"))
        assertEquals(missingName, remapper.map(missingName))
        assertEquals("missingMethod", remapper.mapMethodName(missingName, "missingMethod", "()V"))
    }

    private fun readClass(type: Class<*>): ClassReader {
        val resourceName = type.name.replace('.', '/') + ".class"
        val bytes = requireNotNull(type.classLoader.getResourceAsStream(resourceName)) {
            "Missing class resource $resourceName"
        }.use { it.readBytes() }
        return ClassReader(bytes)
    }

    private fun ClassReader.fieldValues(): Map<String, Any?> {
        val values = linkedMapOf<String, Any?>()
        accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                values[name] = value
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return values
    }

    private fun ClassReader.fieldAccesses(): List<FieldAccess> {
        val accesses = mutableListOf<FieldAccess>()
        accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        accesses += FieldAccess(opcode, owner, name, descriptor)
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return accesses
    }

    private data class FieldAccess(
        val opcode: Int,
        val owner: String,
        val name: String,
        val descriptor: String,
    )
}
