package li.songe.remap

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("integration")
class KmpIntegrationTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `KMP transforms Android output without leaking stubs to other targets`() {
        val sdk = System.getProperty("remap.androidSdk")
        require(sdk.isNotBlank() && File(sdk, "platforms/android-36/android.jar").isFile) {
            "integrationTest requires Android SDK platform 36: pass -PandroidSdk=<sdk directory>"
        }
        File("src/test/fixtures/kmp").copyRecursively(directory, overwrite = true)
        File(directory, "local.properties").writeText("sdk.dir=${sdk.replace('\\', '/')}\n")
        File(directory, "gradle.properties").writeText("org.gradle.jvmargs=-Xmx2048m\n")
        // Load Remap alongside AGP in the fixture's buildscript classloader. TestKit's
        // injected classloader would isolate Remap from its compile-only AGP dependency.
        val pluginClasspath = Properties().apply {
            KmpIntegrationTest::class.java.classLoader
                .getResourceAsStream("plugin-under-test-metadata.properties")!!.use { load(it) }
        }.getProperty("implementation-classpath")
        val arguments = listOf(
            ":shared:assemble", ":shared:jvmJar", ":android-library:assembleDebug", ":app:assembleDebug",
            "--configuration-cache", "--build-cache", "--stacktrace", "--console=plain", "--max-workers=2", "--info",
            "-PagpVersion=${System.getProperty("remap.agpVersion")}",
            "-PkotlinVersion=${System.getProperty("remap.kotlinVersion")}",
            "-PprocessorClasspath=${System.getProperty("remap.fixtureProcessor")}",
            "-PpluginClasspath=$pluginClasspath",
        )
        fun runner() = GradleRunner.create().withProjectDir(directory)
            .withArguments(arguments).forwardOutput()
            .apply { System.getProperty("remap.gradleVersion").takeIf { it.isNotBlank() }?.let { withGradleVersion(it) } }

        runner().build()
        val aar = File(directory, "shared/build/outputs/aar").walkTopDown().single { it.extension == "aar" }
        val classes = readAarClasses(aar)
        assertFalse(classes.containsKey("fixture/BundleHidden.class"))
        val bytes = classes.getValue("fixture/AndroidCallsKt.class")
        val references = references(bytes)
        assertTrue(references.any { "android/os/Bundle.getInt" in it }, references.toString())
        assertTrue(references.any { "android/os/Bundle.putAll(Landroid/os/Bundle;)V" in it }, references.toString())
        assertTrue(references.any { "android/os/Bundle.EMPTY" in it }, references.toString())
        assertFalse(references.any { "BundleHidden" in it || "readInt" in it }, references.toString())
        assertTrue(references.any { "echo(Landroid/os/Bundle;)Landroid/os/Bundle;" in it })
        assertTrue(references.any { "Ljava/util/List<Landroid/os/Bundle;>;" in it })

        val legacyAar = File(directory, "android-library/build/outputs/aar").walkTopDown().single { it.extension == "aar" }
        val legacyReferences = references(readAarClasses(legacyAar).getValue("fixture/LegacyCallsKt.class"))
        assertTrue(legacyReferences.any { "android/os/Bundle.getInt" in it })
        assertFalse(legacyReferences.any { "BundleHidden" in it || "readInt" in it })

        val appClass = File(directory, "app/build").walkTopDown().single {
            it.name == "AppCallsKt.class" && "transformDebugClassesWithAsm" in it.invariantSeparatorsPath
        }
        val appReferences = references(appClass.readBytes())
        assertFalse(appReferences.any { "BundleHidden" in it || "readInt" in it }, appReferences.toString())
        assertTrue(appReferences.any { "android/os/Bundle.getInt" in it }, "Cross-module inline body was not remapped")
        assertTrue(appReferences.any { "fixture/AndroidCallsKt.echo(Landroid/os/Bundle;)Landroid/os/Bundle;" in it })

        val jvmJar = File(directory, "shared/build/libs").listFiles()!!.single {
            it.extension == "jar" && "jvm" in it.name && "sources" !in it.name
        }
        ZipFile(jvmJar).use { zip ->
            assertTrue(zip.getEntry("fixture/CommonKt.class") != null)
            assertTrue(zip.getEntry("fixture/AndroidCallsKt.class") == null)
            assertTrue(zip.getEntry("fixture/BundleHidden.class") == null)
        }
        val repeated = runner().build()
        assertTrue(repeated.output.contains("Reusing configuration cache."))
        assertTrue(repeated.tasks.filter { "generate" in it.path && "RemapIndex" in it.path }.isNotEmpty())
        repeated.tasks.filter { "RemapIndex" in it.path }.forEach {
            assertEquals(TaskOutcome.UP_TO_DATE, it.outcome)
        }

        // Changing Android code must invalidate compilation/transformation with cache reuse.
        val source = File(directory, "shared/src/androidMain/kotlin/fixture/AndroidCalls.kt")
        source.appendText("\nfun added(bundle: android.os.Bundle) = (bundle as BundleHidden).readInt(\"new\")\n")
        val changed = runner().build()
        assertTrue(changed.output.contains("scanned=1, candidates=1, rewritten=1,"), changed.output)
        assertTrue(changed.output.contains("Remap directory:"), changed.output)
        val runtimeClasses = File(directory, "shared/build/intermediates/runtime_library_classes_dir")
        assertTrue(runtimeClasses.walkTopDown().any { it.name == "AndroidCallsKt.class" })
        assertFalse(runtimeClasses.walkTopDown().any { it.extension == "jar" })
        assertTrue(changed.output.lineSequence().any {
            "Running dexing transform incrementally" in it && "shared" in it
        }, changed.output)
        assertEquals(classes.keys, readAarClasses(aar).keys)
        assertTrue(references(readAarClasses(aar).getValue("fixture/AndroidCallsKt.class")).any { "added(" in it })

        val temporary = File(source.parentFile, "Temporary.kt")
        temporary.writeText("package fixture\nclass Temporary\n")
        runner().build()
        assertEquals(classes.keys + "fixture/Temporary.class", readAarClasses(aar).keys)
        temporary.delete()
        runner().build()
        assertEquals(classes.keys, readAarClasses(aar).keys)

        // Restore the task outputs from Gradle's cache, then modify a class again.
        // LocalState is not cached: the following incremental run must safely rebuild it.
        val transformed = File(directory, "shared/build/intermediates/classes")
            .walkTopDown().single { it.name == "dirs" && it.parentFile.name == "transformAndroidMainRemapClasses" }
        assertTrue(transformed.deleteRecursively())
        val restored = runner().build()
        assertEquals(TaskOutcome.FROM_CACHE, restored.task(":shared:transformAndroidMainRemapClasses")?.outcome)
        source.appendText("\nfun afterRestore(bundle: android.os.Bundle) = (bundle as BundleHidden).readInt(\"restore\")\n")
        val afterRestore = runner().build()
        assertTrue(afterRestore.output.contains("scanned=2, candidates=1, rewritten=1,"), afterRestore.output)
        assertEquals(classes.keys, readAarClasses(aar).keys)
        assertFalse(references(readAarClasses(aar).getValue("fixture/AndroidCallsKt.class")).any { "BundleHidden" in it })

        // KMP must also work when Remap is applied after the Android plugin.
        val buildFile = File(directory, "shared/build.gradle.kts")
        buildFile.writeText(buildFile.readText()
            .replace("    id(\"li.songe.remap\")\n", "")
            .replace("    id(\"com.android.kotlin.multiplatform.library\")", "    id(\"com.android.kotlin.multiplatform.library\")\n    id(\"li.songe.remap\")"))
        runner().build()
        assertFalse(references(readAarClasses(aar).getValue("fixture/AndroidCallsKt.class")).any { "BundleHidden" in it })
    }

    private fun readAarClasses(file: File): Map<String, ByteArray> = ZipFile(file).use { aar ->
        ZipInputStream(aar.getInputStream(aar.getEntry("classes.jar"))).use { jar ->
            buildMap {
                while (true) {
                    val entry = jar.nextEntry ?: break
                    if (entry.name.endsWith(".class")) put(entry.name, jar.readBytes())
                }
            }
        }
    }

    private fun references(bytes: ByteArray): List<String> = buildList {
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                add("$name$descriptor ${signature.orEmpty()}")
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitTypeInsn(opcode: Int, type: String) { add(type) }
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        add("$owner.$name$descriptor")
                    }
                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        add("$owner.$name:$descriptor")
                    }
                }
            }
        }, 0)
    }
}

