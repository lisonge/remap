package li.songe.remap

import javax.lang.model.SourceVersion

const val REMAP_INDEX_PATH = "META-INF/remap/index-v1.txt"

private const val TYPE_ENTRY = "T"
private const val METHOD_ENTRY = "M"
private const val MAX_MODIFIED_UTF8_BYTES = 65_535

data class RemapIndex(
    val typeMappings: Map<String, String>,
    val methodMappings: Map<String, Map<String, String>>,
)

class RemapIndexBuilder {
    private val typeMappings = hashMapOf<String, String>()
    private val methodMappings = hashMapOf<String, MutableMap<String, String>>()

    fun putType(source: String, target: String) {
        putMapping(typeMappings, source, target) {
            "Conflicting remap type for $source: $it and $target"
        }
    }

    fun putMethod(owner: String, source: String, target: String) {
        val ownerMappings = methodMappings.getOrPut(owner) { hashMapOf() }
        putMapping(ownerMappings, source, target) {
            "Conflicting remap method for $owner.$source: $it and $target"
        }
    }

    fun add(index: RemapIndex) {
        index.typeMappings.forEach(::putType)
        index.methodMappings.forEach { (owner, mappings) ->
            mappings.forEach { (source, target) ->
                putMethod(owner, source, target)
            }
        }
    }

    fun build(): RemapIndex {
        return RemapIndex(
            HashMap(typeMappings),
            methodMappings.mapValuesTo(HashMap()) { (_, mappings) -> HashMap(mappings) },
        )
    }

    private fun <K> putMapping(
        mappings: MutableMap<K, String>,
        key: K,
        target: String,
        conflictMessage: (String) -> String,
    ) {
        val previous = mappings[key]
        require(previous == null || previous == target) {
            conflictMessage(requireNotNull(previous))
        }
        mappings[key] = target
    }
}

fun RemapIndex.encode(): String {
    val lines = mutableListOf<String>()
    typeMappings.toSortedMap().forEach { (source, target) ->
        lines += "$TYPE_ENTRY\t$source\t$target"
    }
    methodMappings.toSortedMap().forEach { (owner, mappings) ->
        mappings.toSortedMap().forEach { (source, target) ->
            lines += "$METHOD_ENTRY\t$owner\t$source\t$target"
        }
    }
    return lines.joinToString(
        separator = "\n",
        postfix = if (lines.isEmpty()) "" else "\n",
    )
}

fun parseRemapIndex(text: String, source: String = REMAP_INDEX_PATH): RemapIndex {
    val builder = RemapIndexBuilder()
    text.lineSequence().forEachIndexed { index, line ->
        if (line.isBlank()) return@forEachIndexed
        val columns = line.split('\t')
        when (columns.firstOrNull()) {
            TYPE_ENTRY -> {
                require(
                    columns.size == 3 &&
                        columns.drop(1).all(::isInternalClassName),
                ) {
                    "Invalid remap type entry in $source:${index + 1}"
                }
                builder.putType(columns[1], columns[2])
            }

            METHOD_ENTRY -> {
                require(
                    columns.size == 4 &&
                        isInternalClassName(columns[1]) &&
                        columns.drop(2).all(::isJavaMethodName),
                ) {
                    "Invalid remap method entry in $source:${index + 1}"
                }
                builder.putMethod(columns[1], columns[2], columns[3])
            }

            else -> error("Unknown remap index entry in $source:${index + 1}")
        }
    }
    return builder.build()
}

private fun isInternalClassName(name: String): Boolean {
    return name.modifiedUtf8Length() <= MAX_MODIFIED_UTF8_BYTES &&
        name.split('/').all(::isJavaIdentifier)
}

private fun isJavaMethodName(name: String): Boolean = isJavaIdentifier(name)

private fun isJavaIdentifier(name: String): Boolean {
    return SourceVersion.isIdentifier(name) &&
        !SourceVersion.isKeyword(name) &&
        name.modifiedUtf8Length() <= MAX_MODIFIED_UTF8_BYTES
}

private fun String.modifiedUtf8Length(): Int {
    var length = 0
    forEach { char ->
        length += when (char.code) {
            0 -> 2
            in 1..0x7F -> 1
            in 0x80..0x7FF -> 2
            else -> 3
        }
        if (length > MAX_MODIFIED_UTF8_BYTES) return length
    }
    return length
}
