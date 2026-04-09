package li.songe.remap

import com.android.build.api.instrumentation.ClassContext
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.Remapper

class RemapRemapper(val context: ClassContext) : Remapper(Opcodes.ASM9) {

    override fun map(name: String): String {
        val data = context.loadClassData(getMetaClassName(name))
        return parseTypeName(data?.classAnnotations) ?: name
    }

    override fun mapMethodName(owner: String, name: String, descriptor: String): String {
        val data = context.loadClassData(getMetaClassName(owner))
        return parseMethodName(data?.classAnnotations, name) ?: name
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
