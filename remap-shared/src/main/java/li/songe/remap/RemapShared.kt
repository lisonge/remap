package li.songe.remap

private const val META_SUFFIX = "\$Remap114514"

private const val TYPE_PREFIX = "T."

private const val METHOD_PREFIX = "M."

fun getMetaClassName(name: String): String {
    val actualName = if (name.contains('/')) name.replace('/', '.') else name
    return actualName + META_SUFFIX
}

fun buildTypeName(name: String): String = TYPE_PREFIX + name

fun parseTypeName(annotations: List<String>?): String? {
    annotations?.forEach { annotation ->
        if (annotation.startsWith(TYPE_PREFIX)) {
            return annotation.substring(TYPE_PREFIX.length).replace('.', '/')
        }
    }
    return null
}

fun buildMethodName(fromMethodName: String, toMethodName: String): String {
    return "$METHOD_PREFIX$fromMethodName.$toMethodName"
}

fun parseMethodName(annotations: List<String>?, name: String): String? {
    annotations?.forEach { annotation ->
        if (annotation.startsWith(METHOD_PREFIX) &&
            annotation.startsWith(name, METHOD_PREFIX.length) &&
            annotation[METHOD_PREFIX.length + name.length] == '.'
        ) {
            return annotation.substring(METHOD_PREFIX.length + name.length + 1)
        }
    }
    return null
}

fun toDescriptor(name: String): String = "L" + name.replace('.', '/') + ";"
