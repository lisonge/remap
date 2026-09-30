package li.songe.remap

import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.Remapper

class RemapRemapper(private val index: RemapIndex) : Remapper(Opcodes.ASM9) {

    override fun map(name: String): String {
        if (canSkipLookup(name)) return name
        return index.typeMappings[name] ?: name
    }

    override fun mapMethodName(owner: String, name: String, descriptor: String): String {
        if (canSkipLookup(owner)) return name
        return index.methodMappings[owner]?.get(name) ?: name
    }

    // Android-only indexes can reject most owners without hashing the whole name.
    // This is a coarse filter: false positives still use the exact map lookup.
    private fun canSkipLookup(name: String): Boolean = index.onlyAndroidMappings &&
        (name.isEmpty() || (name[0] != 'a' && (name.length <= 4 || name[4] != 'a')))

    override fun mapInnerClassName(name: String, ownerName: String?, innerName: String): String {
        val result = super.mapInnerClassName(name, ownerName, innerName)
        // fix: class A { class $B { }}
        // https://github.com/RikkaApps/HiddenApiRefinePlugin/pull/22
        // https://github.com/Kotlin/kotlinx.serialization/issues/2285
        // https://gitlab.ow2.org/asm/asm/-/work_items/317999
        if (innerName.startsWith("$") && !result.startsWith("$")) {
            return "$$result"
        }
        return result
    }
}
