package li.songe.remap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RemapIndexTest {
    @Test
    fun `index encoding is deterministic and round trips`() {
        val builder = RemapIndexBuilder().apply {
            putMethod("test/Source", "z", "targetZ")
            putType("test/Source", "test/Target")
            putMethod("test/Source", "a", "targetA")
            putMethod("test/Other", "a", "otherTargetA")
        }

        val encoded = builder.build().encode()

        assertEquals(
            """
                T	test/Source	test/Target
                M	test/Other	a	otherTargetA
                M	test/Source	a	targetA
                M	test/Source	z	targetZ
            """.trimIndent() + "\n",
            encoded,
        )
        assertEquals(builder.build(), parseRemapIndex(encoded))
    }

    @Test
    fun `built index uses hash maps without preserving insertion order`() {
        val index = RemapIndexBuilder().apply {
            putType("test/Source", "test/Target")
            putMethod("test/Source", "sourceMethod", "targetMethod")
        }.build()

        assertIs<HashMap<*, *>>(index.typeMappings)
        assertIs<HashMap<*, *>>(index.methodMappings)
        index.methodMappings.values.forEach { assertIs<HashMap<*, *>>(it) }
    }

    @Test
    fun `empty index is an empty file`() {
        val index = RemapIndex(emptyMap(), emptyMap())

        assertEquals("", index.encode())
        assertEquals(index, parseRemapIndex(""))
    }

    @Test
    fun `identical mappings merge and conflicting mappings fail`() {
        val builder = RemapIndexBuilder()
        builder.putType("test/Source", "test/Target")
        builder.putType("test/Source", "test/Target")

        assertFailsWith<IllegalArgumentException> {
            builder.putType("test/Source", "test/OtherTarget")
        }
    }

    @Test
    fun `invalid class and method names fail while parsing`() {
        assertFailsWith<IllegalArgumentException> {
            parseRemapIndex("T\ttest/Source\t[\n")
        }
        assertFailsWith<IllegalArgumentException> {
            parseRemapIndex("M\ttest/Source\tsourceMethod\t<init>\n")
        }
        assertFailsWith<IllegalArgumentException> {
            parseRemapIndex("M\ttest/Source\tsourceMethod\t${"a".repeat(65_536)}\n")
        }
    }
}
