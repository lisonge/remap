package li.songe.remap

import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.Remapper

class RemapRemapper(private val index: RemapIndex) : Remapper(Opcodes.ASM9) {

    override fun map(name: String): String {
        return index.typeMappings[name] ?: name
    }

    override fun mapMethodName(owner: String, name: String, descriptor: String): String {
        return index.methodMappings[owner]?.get(name) ?: name
    }

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
