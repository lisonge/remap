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
// hidden_api/build.gradle.kts
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
    remapApi(project(":hidden_api"))
}
```

Apply the Remap plugin to every Android module whose compiled code references
hidden API stubs. The plugin only transforms classes from the current module;
it does not transform dependency modules. Declare exactly one hidden API module
with `remapApi(project(...))`. The plugin supplies this dependency to
`compileOnly`; it is not packaged into the application at runtime. The dependency
is non-transitive, so keep all remap stubs in that configured module. Non-Android
dependency modules are not transformed and must not reference the stubs.

The annotation processor writes all type and method mappings to a deterministic
index in the stub module's compile output. For each Android variant, the plugin
resolves only the configured module's matching, non-transitive compile artifact
and reads the index at its fixed path. It does not scan the rest of the variant's
compile classpath or load class metadata.

The index filename carries its format version. Its contents are deterministic,
BOM-free UTF-8 TSV records with `\n` line endings and no file header.

## Access hidden types

```java
// hidden_api/src/main/java/android/app/AppOpsManagerHidden.java
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
// hidden_api/src/main/java/android/os/IBinderHidden.java
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
// hidden_api/src/main/java/android/content/IPackageManager.java
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

## Thanks

- [HiddenApiRefinePlugin](https://github.com/RikkaApps/HiddenApiRefinePlugin)
