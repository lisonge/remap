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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemapProcessorTest {

    @Test
    fun `class can remap to class`() {
        val result = compile("class", "class")

        assertTrue(result.success, result.errors)
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

    private fun compile(sourceKind: String, targetKind: String): CompilationResult {
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

                        import li.songe.remap.RemapType;

                        $targetKind Target {}

                        @RemapType(Target.class)
                        $sourceKind Source {}
                    """.trimIndent(),
                )
                val task = compiler.getTask(null, fileManager, diagnostics, null, null, listOf(source))
                task.setProcessors(listOf(RemapProcessor()))
                val success = task.call()
                val errors = diagnostics.diagnostics
                    .filter { it.kind == Diagnostic.Kind.ERROR }
                    .joinToString("\n") { it.getMessage(null) }
                return CompilationResult(success, errors)
            }
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    private data class CompilationResult(
        val success: Boolean,
        val errors: String,
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
