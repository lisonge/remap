package li.songe.remap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemapLookupTest {
    @Test
    fun `android-only mappings skip unrelated names without querying maps`() {
        val types = CountingMap(mapOf(
            "android/os/Hidden" to "android/os/Target",
            "com/android/internal/Hidden" to "com/android/internal/Target",
        ))
        val methods = CountingMap(mapOf(
            "android/os/Hidden" to mapOf("alias" to "actual"),
            "com/android/internal/Hidden" to mapOf("alias" to "actual"),
        ))
        val index = RemapIndex(types, methods)
        assertTrue(index.onlyAndroidMappings)
        val remapper = RemapRemapper(index)
        listOf("", "x", "com/", "com/example/Type", "java/lang/String", "kotlin/Unit").forEach {
            assertEquals(it, remapper.map(it))
            assertEquals("alias", remapper.mapMethodName(it, "alias", "()V"))
        }
        assertEquals(0, types.lookups)
        assertEquals(0, methods.lookups)
        types.keys.forEach {
            assertEquals(it.replace("Hidden", "Target"), remapper.map(it))
            assertEquals("actual", remapper.mapMethodName(it, "alias", "()V"))
        }
        // Candidate names are not necessarily Android names; map lookup stays exact.
        listOf("a", "com/acme/Type", "androidx/Type").forEach {
            assertEquals(it, remapper.map(it))
            assertEquals("alias", remapper.mapMethodName(it, "alias", "()V"))
        }
        assertEquals(5, types.lookups)
        assertEquals(5, methods.lookups)
    }

    @Test
    fun `custom type or method owner disables filtering independently`() {
        val android = RemapIndex(
            mapOf("android/os/Hidden" to "custom/Target"),
            mapOf("com/android/internal/Hidden" to mapOf("alias" to "actual")),
        )
        assertTrue(android.onlyAndroidMappings) // Only source names matter.
        val customType = android.copy(typeMappings = android.typeMappings + ("example/Hidden" to "example/Target"))
        assertFalse(customType.onlyAndroidMappings)
        assertEquals("example/Target", RemapRemapper(customType).map("example/Hidden"))
        val customMethod = android.copy(methodMappings = android.methodMappings + ("example/Owner" to mapOf("alias" to "actual")))
        assertFalse(customMethod.onlyAndroidMappings)
        assertEquals("actual", RemapRemapper(customMethod).mapMethodName("example/Owner", "alias", "()V"))
    }

    @Test
    fun `parsed and merged indexes compute filtering eligibility immediately`() {
        val android = parseRemapIndex("T\tandroid/os/Hidden\tandroid/os/Target\n")
        assertTrue(android.onlyAndroidMappings)
        val mixed = RemapIndexBuilder().apply {
            add(android)
            putMethod("example/Owner", "alias", "actual")
        }.build()
        assertFalse(mixed.onlyAndroidMappings)
        assertFalse(parseRemapIndex(mixed.encode()).onlyAndroidMappings)
        assertTrue(parseRemapIndex("").onlyAndroidMappings)
    }

    private class CountingMap<V>(entries: Map<String, V>) : Map<String, V> by entries {
        private val valuesByName = entries
        var lookups = 0
            private set

        override fun get(key: String): V? {
            lookups++
            return valuesByName[key]
        }
    }
}
