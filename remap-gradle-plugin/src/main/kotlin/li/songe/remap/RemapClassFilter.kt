package li.songe.remap

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.Remapper
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** False positives are intentional; only a definite absence may skip ASM traversal. */
internal class RemapClassFilter(index: RemapIndex) {
    private val hints = (index.typeMappings.keys + index.methodMappings.keys)
        // Generic signatures spell Outer<T>.Inner instead of Outer$Inner. The
        // outer prefix is always present, including for deeper nesting.
        .map { it.substringBefore('$') }.distinct().map { name ->
            val bytes = ByteArrayOutputStream().also { out ->
                DataOutputStream(out).use { it.writeUTF(name) }
            }.toByteArray()
            // Class files use modified UTF-8, including surrogate pairs. Latin-1
            // preserves each encoded byte for String.indexOf without decoding it.
            String(bytes, 2, bytes.size - 2, Charsets.ISO_8859_1)
        }

    fun mayRemap(bytes: ByteArray, reader: ClassReader): Boolean {
        if (hints.isEmpty()) return false
        val pool = String(bytes, 0, reader.header, Charsets.ISO_8859_1)
        return hints.any { pool.contains(it) }
    }
}

/** Distinguishes real mapping changes from harmless constant-pool hints. */
internal class TrackingRemapper(index: RemapIndex) : Remapper(Opcodes.ASM9) {
    private val delegate = RemapRemapper(index)
    var changed = false
        private set

    fun reset() { changed = false }

    override fun map(name: String): String = delegate.map(name).also { if (it != name) changed = true }

    override fun mapMethodName(owner: String, name: String, descriptor: String): String =
        delegate.mapMethodName(owner, name, descriptor).also { if (it != name) changed = true }

    override fun mapInnerClassName(name: String, ownerName: String?, innerName: String): String =
        delegate.mapInnerClassName(name, ownerName, innerName).also { if (it != innerName) changed = true }
}
