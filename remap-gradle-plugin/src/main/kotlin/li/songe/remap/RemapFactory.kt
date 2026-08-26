package li.songe.remap

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.commons.ClassRemapper
import java.util.LinkedHashMap

abstract class RemapFactory : AsmClassVisitorFactory<RemapParameters> {
    override fun isInstrumentable(classData: ClassData) = true

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor = object : ClassRemapper(
        instrumentationContext.apiVersion.get(),
        nextClassVisitor,
        RemapRemapper(loadIndex(parameters.get().indexContent.get()))
    ) {}

    private companion object {
        private const val MAX_CACHED_INDEXES = 16
        private const val MAX_CACHED_CONTENT_CHARS = 8 * 1024 * 1024

        private val indexCache = LinkedHashMap<String, RemapIndex>(MAX_CACHED_INDEXES, 0.75f, true)
        private var cachedContentChars = 0

        fun loadIndex(content: String): RemapIndex {
            return synchronized(indexCache) {
                indexCache[content]?.let { return@synchronized it }
                val index = parseRemapIndex(content)
                indexCache[content] = index
                cachedContentChars += content.length
                val iterator = indexCache.entries.iterator()
                while (
                    iterator.hasNext() &&
                    (indexCache.size > MAX_CACHED_INDEXES || cachedContentChars > MAX_CACHED_CONTENT_CHARS)
                ) {
                    cachedContentChars -= iterator.next().key.length
                    iterator.remove()
                }
                index
            }
        }
    }
}
