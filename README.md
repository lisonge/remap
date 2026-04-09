# remap

[![Maven Central](https://img.shields.io/maven-central/v/li.songe.remap/remap-annotation.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/li.songe.remap/remap-annotation)
[![License](http://img.shields.io/:License-Apache-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)

A Gradle plugin that uses ASM bytecode transformation to enable compile-time access to Android hidden APIs.

- **RemapType**: Remap a type to another type for access hidden types.
- **RemapMethod**: Remap a method to another method for access hidden overload conflict methods.

## Usage

```toml
# gradle/libs.versions.toml
[versions]
loc = "<version>" # https://github.com/lisonge/remap/releases

[libraries]
remap-processor = { module = "li.songe.remap:remap-processor", version.ref = "remap" }
remap-annotation = { module = "li.songe.remap:remap-annotation", version.ref = "remap" }

[plugins]
remap = { id = "li.songe.remap", version.ref = "remap" }
```

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.loc) apply false
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
    compileOnly(project(":hidden_api"))
}
```

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

```kotlin
// app/src/main/kotlin/example/app/Example.kt
package example.app

import android.content.IPackageManager
import android.content.PackageInfo
import android.content.PackageInfoList

fun test(manger: IPackageManager, flags: Long, userId: Int): List<PackageInfo> {
    return (if (AndroidTarget.CINNAMON_BUN) { // android17+
        manger.getInstalledPackagesV17(flags, userId)
    } else if (AndroidTarget.TIRAMISU) { // android13 - android16
        manger.getInstalledPackages(flags, userId)
    } else { // android8 - android12L
        manger.getInstalledPackages(flags.toInt(), userId)
    }).list
}
```

## Thanks

- [HiddenApiRefinePlugin](https://github.com/RikkaApps/HiddenApiRefinePlugin)
