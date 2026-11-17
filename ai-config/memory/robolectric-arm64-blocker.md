---
name: robolectric-arm64-blocker
description: Robolectric 在 aarch64 musl 容器内不可行（conscrypt 无 ARM native），已验证；替代=纯 JUnit 逻辑测试+真机 adb
---
# Robolectric 在本容器（aarch64 + musl）内不可行（已验证，勿再尝试）

## 现象
`gradle testDebugUnitTest` 跑 Robolectric 测试时 3 个测试全部 `java.lang.UnsatisfiedLinkError: no conscrypt_openjdk_jni-linux-aarch_64 in java.library.path`。

## 根因（已逐层验证）
1. Robolectric 4.14.1 启动时经 `AndroidTestEnvironment.setUpApplicationState` → `org.conscrypt.OpenSSLProvider` 强制加载 native 库。
2. classpath 中的 `conscrypt-openjdk-uber-2.5.2.jar` 经解包核实**只含** `META-INF/native/libconscrypt_openjdk_jni-linux-x86_64.so`，无任何 ARM 构建。
3. Maven Central 上 `org.conscrypt:conscrypt-openjdk-jni` 的 `linux-aarch_64` classifier 不存在（2026-10-11 wget 实测 404）。
4. 即使有，该 .so 为 glibc 链接，musl 容器还需 gcompat 兼容层——官方缺失是硬性阻断，无修补价值。

## 结论
- 容器内**不要**再用 Robolectric / androidx.test 做 JVM 单元测试。
- 纯 JUnit 4（`testDebugUnitTest`）无 native 依赖，容器内可靠，秒级运行。

## 已落地的替代模式（2026-10-11 验证）
- 业务逻辑抽纯类（零 Android 依赖），UI 只做薄层——纯 JUnit 4（`testDebugUnitTest`）容器内秒级可靠运行。
- UI/集成测试走**真机 adb** 路线：手机开无线调试 → 容器 `/root/android/sdk/platform-tools/adb connect` → Espresso 仪器测试或 uiautomator 截图冒烟。
- 注意：将来接 Espresso 仪器测试需补 androidx.test 依赖。