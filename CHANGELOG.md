# What's Changed

<!--
维护规则：只记录上一个已发布版本之后的修改，不累积历史版本记录。
更新前核对最近的发布 tag 与当前代码（含未提交修改）；删除已在该版本发布的条目。
当前基线：v0.1.5。下一次发布后更新基线，并重新整理本文件。
-->

- refactor: share transformation task execution and test fixtures, separate cache update/output/state handling, and simplify annotation processor type grouping and binary-name resolution
- perf: incrementally transform KMP classes into a separate directory using AGP's internal directory bridge, preserving downstream D8 incrementality; fall back to the public JAR transform if the bridge is unavailable
- perf: screen changed classes and cache only bytecode actually remapped; handle additions, deletions, Kotlin build-cache restoration, mapping changes, and local-state loss
- perf: precompute Android-only mapping eligibility with each index and skip unrelated type/owner map lookups
- feat: support Android targets in Kotlin/Compose Multiplatform via `com.android.kotlin.multiplatform.library`
- fix: transform KMP project classes through Scoped Artifacts because AGP 9.2.1 and 9.4.1 do not run its KMP ASM instrumentation task
- fix: wire `remapApi` lazily to the platform compile-only configuration and support either plugin application order
- test: add Android/KMP multi-module integration coverage with configuration-cache and incremental builds
- build: use SNAPSHOT versions outside CI and add a release tag/version verification task
- docs: document KMP usage and module boundaries, and rename the hidden API example module to `hidden-api`
