# remap

[![Maven Central](https://img.shields.io/maven-central/v/li.songe.remap/remap-annotation.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/li.songe.remap/remap-annotation)
[![License](http://img.shields.io/:License-Apache-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)

A Gradle plugin that uses ASM bytecode transformation to enable compile-time access to Android hidden APIs.

- **RemapType**: Remap a type to another type for access hidden types.
- **RemapMethod**: Remap a method to another method for access hidden overload conflict methods.
- **RemapStub**: Declare non-inlineable values for fields in compile-time API stubs.

## Usage

```toml
# gradle/libs.versions.toml
[versions]
remap = "<version>" # https://github.com/lisonge/remap/releases

[libraries]
remap-processor = { module = "li.songe.remap:remap-processor", version.ref = "remap" }
remap-annotation = { module = "li.songe.remap:remap-annotation", version.ref = "remap" }

[plugins]
remap = { id = "li.songe.remap", version.ref = "remap" }
```

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.remap) apply false
}
```

```kotlin
// hidden-api/build.gradle.kts
dependencies {
    compileOnly(libs.remap.annotation)
    annotationProcessor(libs.remap.processor)
}
```

```kotlin
// app/build.gradle.kts
plugins {
    alias(libs.plugins.remap)
}

dependencies {
    remapApi(project(":hidden-api"))
}
```

## Kotlin / Compose Multiplatform

Remap supports the Android target provided by
`com.android.kotlin.multiplatform.library`. Apply it to the KMP/CMP module and
keep `remapApi` in the module's top-level `dependencies` block:

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("li.songe.remap")
}

kotlin {
    android {
        namespace = "example.shared"
        compileSdk = 36
    }
    jvm()
}

dependencies {
    remapApi(project(":hidden-api"))
}
```

The plugin connects `remapApi` to `androidMainCompileOnly`. Use the stubs in
`src/androidMain`; they are not supplied to `commonMain`, JVM, or native targets.
Only the Android target's project classes are transformed. Compose is not
required. Plugin declaration order does not matter.

Keep the stubs and annotation processor in a separate `:hidden-api` module as
shown above. When that Android library has build types or product flavors,
configure `kotlin.android.localDependencySelection` as needed; Remap uses the
Android variant's attributes to resolve the index dependency.

Host/device test classes are not separately instrumented by Remap.

The KMP path uses the project-scoped Android classes artifact transformation.
AGP 9.2.1 and 9.4.1 expose the instrumentation API on KMP variants but do not
execute its ASM task for their main classes. Ordinary Android modules continue
to use the instrumentation API. Both paths use the same Remap mappings.

KMP transformations screen added or changed classes before running ASM remapping.
Unrelated classes and resources retain their original bytes. When AGP's internal
directory transform interface is available, Remap publishes a separate classes
directory and updates only changed entries, preserving downstream D8 incrementality.
Compiler outputs are never modified. This compatibility bridge is tested with
AGP 9.2.1 and 9.4.1; if the interface is unavailable, Remap logs a warning and
falls back to the public single-JAR transform (with coarser downstream dex work).
Incremental D8 execution also depends on AGP's dex/desugaring configuration;
directory output removes Remap's single-JAR bottleneck where AGP supports it.

Changed input JARs are rescanned in full and expanded into the output directory.
Mapping changes or missing local state trigger a full rescan. Only actually
rewritten classes get an additional local cache file. The disposable cache lives
in `build/intermediates/remap/<task-name>/classes-state/`, with a `manifest-v3`
and content-addressed `.class` files. Gradle `--info` logs class counts and
screening, ASM, and output times (`output`). Unchanged builds skip the task; both
output paths support Gradle's build cache and configuration cache.

## Module boundaries

Apply the Remap plugin to every Android module whose compiled code references
hidden API stubs. The plugin only transforms classes from the current module;
it does not transform dependency modules. Declare exactly one hidden API module
with `remapApi(project(...))`. The plugin supplies this dependency to
`compileOnly`; it is not packaged into the application at runtime. The dependency
is non-transitive, so keep all remap stubs in that configured module. Non-Android
targets are not transformed and must not reference the stubs.

Remap rewrites JVM bytecode and generic signatures, not Kotlin Metadata. Keep
mapped stub types out of APIs intended for consumers without Remap. Consumers
that reference such signatures or inline bodies involving stubs also need
Remap and the same stub dependency.

The annotation processor writes all type and method mappings to a deterministic
index in the stub module's compile output. For each Android variant, the plugin
resolves only the configured module's matching, non-transitive compile artifact
and reads the index at its fixed path. It does not scan the rest of the variant's
compile classpath or load class metadata.

The index filename carries its format version. Its contents are deterministic,
BOM-free UTF-8 TSV records with `\n` line endings and no file header.

## Access hidden types

```java
// hidden-api/src/main/java/android/app/AppOpsManagerHidden.java
package android.app;

import android.os.Build;
import li.songe.remap.RemapType;

@RemapType(AppOpsManager.class)
public class AppOpsManagerHidden {
    public static int OP_POST_NOTIFICATION;
    public static String opToName(int op) {
        throw new RuntimeException();
    }
    public void clearHistory() {
        throw new RuntimeException();
    }
}
```

AppOpsManagerHidden is a hidden type, it will be remapped to AppOpsManager.

```kotlin
// app/src/main/kotlin/example/app/Example.kt
package example.app

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden

fun test(manger: AppOpsManager){
    manger is AppOpsManagerHidden // true
    AppOpsManagerHidden.opToName(AppOpsManagerHidden.OP_POST_NOTIFICATION) // "POST_NOTIFICATION"
    (manger as AppOpsManagerHidden).clearHistory() // will Clears all app ops history
}
```

## Access hidden interface fields

Interface fields are implicitly `static final` and require an initializer. Use
`RemapStub.value()` to prevent the compiler from inlining a placeholder value.

```java
// hidden-api/src/main/java/android/os/IBinderHidden.java
package android.os;

import li.songe.remap.RemapStub;
import li.songe.remap.RemapType;

@RemapType(IBinder.class)
public interface IBinderHidden {
    int SHELL_COMMAND_TRANSACTION = RemapStub.value();
}
```

`RemapStub.value()` always throws if evaluated. It must only be used in API
stubs supplied through `compileOnly`, whose field references are rewritten by
the Remap Gradle plugin before runtime.

## Access hidden overload conflict methods

```java
// hidden-api/src/main/java/android/content/IPackageManager.java
package android.content;

import android.os.IInterface;
import li.songe.remap.RemapMethod;

public interface IPackageManager extends IInterface {
    // android8 - android12L
    ParceledListSlice<PackageInfo> getInstalledPackages(int flags, int userId);

    // android13 - android16
    ParceledListSlice<PackageInfo> getInstalledPackages(long flags, int userId);

    // android17+
    // override conflict method, its return type is different from others
    @RemapMethod("getInstalledPackages")
    PackageInfoList getInstalledPackagesV17(long flags, int userId);
}
```

getInstalledPackagesV17 is a hidden overload conflict method, it will be remapped to getInstalledPackages.

you can check its signature changes at <https://diff.songe.li/i/IPackageManager.getInstalledPackages>

```kotlin
// app/src/main/kotlin/example/app/Example.kt
package example.app

import android.content.IPackageManager
import android.content.PackageInfo
import android.content.PackageInfoList
import android.os.Build

fun test(manger: IPackageManager, flags: Long, userId: Int): List<PackageInfo> {
    return (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) { // android17+
        manger.getInstalledPackagesV17(flags, userId)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // android13 - android16
        manger.getInstalledPackages(flags, userId)
    } else { // android8 - android12L
        manger.getInstalledPackages(flags.toInt(), userId)
    }).list
}
```

## Hidden APIs and R8

When Android calls your implementation of a hidden interface or superclass,
R8 needs to understand that runtime contract. Successful compilation or a
working Debug build does not prove that the callback will survive optimization.
Calling a system hidden API and implementing a callback invoked by the system
are different cases.

In multi-module and KMP applications, verify that the relevant hidden-api stubs
reach the **application module that runs R8**. A library's `compileOnly` or
`androidMainCompileOnly` dependency does not establish that the final R8 task
receives those declarations. For example, an application may need its own
`compileOnly(project(":hidden-api"))` dependency; verify the actual inputs for
your build variant and keep compilation stubs out of the APK.

Adding stubs is not always sufficient. Hidden members missing from a public SDK
type are not automatically merged into that type from a remapped `*Hidden`
declaration. Where the contract remains invisible, use targeted `@Keep` or
consumer keep rules. Libraries should ship the protection their consumers need.
Validate the optimized Release DEX for the required method names and complete
signatures, and test the affected system callback path as needed.

### Guidance for AI-assisted consumer projects

This repository provides a reusable
[android-hidden-api-r8 skill template](docs/skills/android-hidden-api-r8/SKILL.md)
with [failure cases](docs/skills/android-hidden-api-r8/references/failure-cases.md).
The template is documentation for consuming projects; it is not installed as a
Remap repository skill.

#### Install the skill in your project

From the **root of the project consuming Remap**, run:

```shell
npx skills add https://github.com/lisonge/remap/tree/main/docs/skills/android-hidden-api-r8
```

Follow the prompts to select your AI tool and install the skill and its
references in the current project. Keep the installation project-local by
leaving out `--global`.
See the [skills CLI documentation](https://github.com/vercel-labs/skills) for
other options.

#### Add the project trigger

Merge the following guidance into the consuming project's `AGENTS.md`, creating
the file if needed. Adapt it to the project's modules and build variants, and
preserve existing instructions. The link is relative to a root-level `AGENTS.md`.

```markdown
## Android Hidden APIs and R8

When changing hidden interface or superclass implementations, system callbacks,
remapping, or related module dependencies and R8 configuration, use
[android-hidden-api-r8](.agents/skills/android-hidden-api-r8/SKILL.md).
Also use it when investigating related failures in minified Release builds.
Check the final application's actual R8 inputs and optimized runtime contracts;
source-level override declarations, compilation success, and Debug behavior
are not sufficient evidence. Limit checks to affected paths unless a broader
audit is requested or the evidence warrants one.
```

After installation, ask the project's AI assistant to use
`$android-hidden-api-r8` for a relevant task. If the assistant does not discover
the skill, point it directly to `.agents/skills/android-hidden-api-r8/SKILL.md`.
Include the installed skill and project guidance in version control to share
them with contributors.

Alternatively, ask your project's AI assistant to perform the setup:

> Read https://github.com/lisonge/remap#hidden-apis-and-r8, install its android-hidden-api-r8
> skill using the documented npx skills command for this project, and merge
> the trigger guidance into this project's AGENTS.md. Adapt it to this project's
> structure and preserve existing rules. Keep the setup project-local.

## Development checks

Run unit tests with `./gradlew test`. The Android/KMP integration fixture uses
AGP 9.2.1, Kotlin 2.3.21, JDK 21, and Android SDK platform 36:

```shell
./gradlew :remap-gradle-plugin:integrationTest -PandroidSdk=/path/to/android-sdk
```

`ANDROID_HOME` or `ANDROID_SDK_ROOT` can supply the SDK path instead. The
integration test checks AAR bytecode, cross-module Kotlin calls and inline
bodies, JVM isolation, plugin order, configuration-cache reuse, build-cache
restoration, class additions/deletions, and incremental downstream dexing.
To check the newer toolchain, add `-PtestAgpVersion=9.4.1`,
`-PtestKotlinVersion=2.4.20`, and `-PtestGradleVersion=9.7.1`.

## Thanks

- [HiddenApiRefinePlugin](https://github.com/RikkaApps/HiddenApiRefinePlugin)
