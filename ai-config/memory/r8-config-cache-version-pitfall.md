---
name: r8-config-cache-version-pitfall
description: 坑（已验证）：Gradle 配置缓存冻结 git 版本号输出，出正式包必须 --no-configuration-cache
---
# 坑：配置缓存冻结 git 版本号（已验证 2026-10-25）

app/build.gradle.kts 用 `providers.exec { git rev-list --count / git describe }` 在配置期算 versionCode/versionName。Gradle 配置缓存（本仓库默认开启）会把这些 ValueSource 的输出连同配置一起缓存复用：仓库提交数变了、命令行没变，缓存照样命中旧值。

后果：`assembleArmsoloRelease` 产物静默携带旧 versionCode/versionName（实例：138 提交时产物仍是 129、versionName 指向早上旧提交），不报错、极难察觉。

## 规矩

- 出正式包一律：`./gradlew assembleArmsoloRelease --no-configuration-cache`。
- 出包后必须核对 `app/build/outputs/apk/armsolo/release/output-metadata.json` 的 versionCode 是否等于 `git rev-list --count HEAD` + 1、versionName 是否指向当前 HEAD。

## 关联

- 发版台账：ai-config/notes/release-review-disposition-2026-10-25.md
- 交接约定的 versionCode 口径：gitCommitCount()+1