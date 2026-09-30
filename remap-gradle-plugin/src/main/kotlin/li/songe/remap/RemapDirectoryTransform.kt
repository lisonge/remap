package li.songe.remap

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.tasks.TaskProvider
import java.lang.reflect.Method

/** AGP's public transform only returns a JAR. Keep its directory bridge isolated here. */
internal class RemapDirectoryTransform private constructor(
    private val artifacts: ScopedArtifacts,
    private val method: Method,
) {
    fun wire(task: TaskProvider<RemapDirectoryClassesTask>) {
        method.invoke(
            artifacts.use(task), ScopedArtifact.CLASSES,
            { t: RemapDirectoryClassesTask -> t.inputJars },
            { t: RemapDirectoryClassesTask -> t.inputDirectories },
            { t: RemapDirectoryClassesTask -> t.outputJars },
            { t: RemapDirectoryClassesTask -> t.outputDirectory },
        )
    }

    companion object {
        fun find(artifacts: ScopedArtifacts): RemapDirectoryTransform? {
            // Android Studio/AGP analytics can wrap the public interface.
            var delegate = artifacts
            repeat(8) {
                val method = delegate.javaClass.methods.asSequence()
                    .filter { it.name == "use" && it.parameterTypes.contentEquals(arrayOf(TaskProvider::class.java)) }
                    .flatMap { it.returnType.methods.asSequence() }
                    .firstOrNull {
                        it.name.startsWith("toTransform\$") && it.parameterCount == 5 &&
                            it.parameterTypes[0] == ScopedArtifact::class.java &&
                            it.parameterTypes.drop(1).all { type -> type == Function1::class.java }
                    }
                if (method != null) return RemapDirectoryTransform(delegate, method)
                val getter = delegate.javaClass.methods.firstOrNull { it.name == "getDelegate" && it.parameterCount == 0 }
                    ?: return null
                delegate = getter.invoke(delegate) as? ScopedArtifacts ?: return null
            }
            return null
        }
    }
}
