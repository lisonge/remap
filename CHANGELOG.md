# What's Changed

- perf: transform only classes from the Android module that applies the plugin
- perf: replace per-owner synthetic metadata classes and ClassContext lookups with a generated aggregate index
- perf: resolve and scan only the explicitly configured hidden API module instead of the full compile classpath
- feat: add `remapApi(project(...))` for declaring the compile-only hidden API module once
- fix: preserve safe Gradle incremental annotation processing and fail when the configured module has no index
- fix: validate and bound index inputs while using exact cache fingerprints
- docs: require the plugin in every Android module that references hidden API stubs
