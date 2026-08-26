package li.songe.remap

import java.net.URI
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RemapProcessorTest {

    @Test
    fun `class can remap to class`() {
        val result = compile("class", "class", "class Nested {}")

        assertTrue(result.success, result.errors)
        assertEquals(
            mapOf(
                "test/Source" to "test/Target",
                "test/Source\$Nested" to "test/Target\$Nested",
            ),
            result.index?.typeMappings,
        )
        assertContentEquals(
            listOf(
                "T\ttest/Source\ttest/Target",
                "T\ttest/Source\$Nested\ttest/Target\$Nested",
            ).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8),
            assertNotNull(result.indexBytes),
        )
        assertTrue(result.metadataClasses.isEmpty(), result.metadataClasses.joinToString())
    }

    @Test
    fun `interface can remap to interface`() {
        val result = compile("interface", "interface")

        assertTrue(result.success, result.errors)
    }

    @Test
    fun `class cannot remap to interface`() {
        val result = compile("class", "interface")

        assertFalse(result.success)
        assertContains(result.errors, KIND_MISMATCH_ERROR)
    }

    @Test
    fun `interface cannot remap to class`() {
        val result = compile("interface", "class")

        assertFalse(result.success)
        assertContains(result.errors, KIND_MISMATCH_ERROR)
    }

    @Test
    fun `method mappings are written to the aggregate index`() {
        val result = compile(
            "class",
            "class",
            "@RemapMethod(\"targetMethod\") void sourceMethod() {}",
        )

        assertTrue(result.success, result.errors)
        assertEquals(
            mapOf("test/Source" to mapOf("sourceMethod" to "targetMethod")),
            result.index?.methodMappings,
        )
    }

    private fun compile(sourceKind: String, targetKind: String, sourceBody: String = ""): CompilationResult {
        val compiler = ToolProvider.getSystemJavaCompiler()
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val outputDirectory = createTempDirectory("remap-processor-test").toFile()
        try {
            compiler.getStandardFileManager(diagnostics, null, null).use { fileManager ->
                fileManager.setLocation(StandardLocation.CLASS_OUTPUT, listOf(outputDirectory))
                val source = SourceFile(
                    "test.Source",
                    """
                        package test;

                        import li.songe.remap.RemapMethod;
                        import li.songe.remap.RemapType;

                        $targetKind Target {}

                        @RemapType(Target.class)
                        $sourceKind Source { $sourceBody }
                    """.trimIndent(),
                )
                val task = compiler.getTask(null, fileManager, diagnostics, null, null, listOf(source))
                task.setProcessors(listOf(RemapProcessor()))
                val success = task.call()
                val errors = diagnostics.diagnostics
                    .filter { it.kind == Diagnostic.Kind.ERROR }
                    .joinToString("\n") { it.getMessage(null) }
                val indexFile = outputDirectory.resolve(REMAP_INDEX_PATH)
                val indexBytes = indexFile.takeIf { it.isFile }?.readBytes()
                val index = indexBytes?.let {
                    parseRemapIndex(it.toString(Charsets.UTF_8), indexFile.absolutePath)
                }
                val metadataClasses = outputDirectory.walkTopDown()
                    .filter { it.isFile && it.name.endsWith("\$R114514.class") }
                    .map { it.relativeTo(outputDirectory).invariantSeparatorsPath }
                    .toList()
                return CompilationResult(success, errors, index, indexBytes, metadataClasses)
            }
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    private data class CompilationResult(
        val success: Boolean,
        val errors: String,
        val index: RemapIndex?,
        val indexBytes: ByteArray?,
        val metadataClasses: List<String>,
    )

    private class SourceFile(
        className: String,
        private val source: String,
    ) : SimpleJavaFileObject(
        URI.create("string:///${className.replace('.', '/')}.java"),
        JavaFileObject.Kind.SOURCE,
    ) {
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
    }

    private companion object {
        const val KIND_MISMATCH_ERROR =
            "the type Source annotated by RemapType must have the same kind (class or interface) as Target"
    }
}
