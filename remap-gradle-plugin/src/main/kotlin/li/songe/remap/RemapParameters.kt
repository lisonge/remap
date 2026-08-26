package li.songe.remap

import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

interface RemapParameters : InstrumentationParameters {
    @get:Input
    val indexContent: Property<String>
}
