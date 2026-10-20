---
name: build-mergejavares-tmpdir-pitfall
description: 构建阻断坑（已验证）：容器内 AGP mergeJavaRes 必败——ZFile close 删临时目录树被 PRoot 拒绝，与代码/Gradle版本/tmpdir位置均无关，需容器引擎层修复
---
# 构建阻断坑：mergeJavaRes 的 ZFile 临时目录删除必败（2026-10-18 验证）

## 现象
`assembleArmsoloDebug / assembleUniversalDebug`（任何会触发 `merge*JavaResource` 真实执行的任务）必败：
`java.io.IOException: Failed to delete '/tmp/tempdir_<id>'`（来自 `MergeOutputWriters$2.close` → apkzlib `ZFile.close()`）。
每次构建新建 350~1000+ 个 tempdir 并全部残留（/tmp 曾累积 3540 个）。

## 已排除（全部实测）
- 与代码无关：`git stash` 后干净 HEAD 同样失败。
- 与 Gradle 版本无关：8.11.1 与新下载的 8.14.3 同败。
- 与 tmpdir 位置无关：`-Dorg.gradle.jvmargs="-Djava.io.tmpdir=/root/gtmp"` 后失败点跟着移动（`/root/gtmp/tempdir_...`）。
  注意：`-Djava.io.tmpdir` 传 CLI 无效，必须走 `org.gradle.jvmargs` 才能到 build JVM。
- 与 daemon 残留无关：`--no-daemon`、`--stop` 后重启、容器重启（PRoot 重置）均复现。
- 文件系统本身正常：shell 对同一空目录 rmdir/rm -rf 全部成功；tempdir 内容为空（文件已删光，目录 rmdir 失败）。

## 根因定位（反编译 AGP 8.9.3 证据）
堆栈 `MergeOutputWriters$2.close` line 196 = `ZFile.close()`（apkzlib）。AGP `MergeJavaResourcesDelegate`
`new ZFileOptions()` + `setNoTimestamps` 后 `MergeOutputWriters.toZip`——**未设 useTemporaryFile**，
ZFile.openReadWrite 在 java.io.tmpdir 下建 `tempdir_<random>` 处理 zip，close 时删目录树。
**JVM 多线程进程在 PRoot 内 unlink 目录树必败；shell 单线程同路径成功**——PRoot 翻译层兼容性缺陷。
Gradle#25984 类似问题（Windows 竞态重试不足），但本环境是确定性必败，升级 Gradle 8.14.3（重试更强）仍败。

## 同根新表现（2026-10-19 验证）：Kotlin 增量编译删旧输出目录同样必败
- `:app:compileUniversalDebugKotlin` 在输出目录已存在时（增量编译需删旧输出）报
  `Could not delete 'app/build/tmp/kotlin-classes/universalDebug/com'`；shell rm -rf 同路径秒删成功。
- **绕行（已实测有效）**：每次编译前 `rm -rf ~/workspace/AiCode/app/build/tmp/kotlin-classes`，
  让 Kotlin 走全新输出路径（只写不删）。注意 Kotlin 编译报错会挡在 mergeJavaRes 之前，
  两者都修复前 assemble 依然必败（编译过了会继续死在 merge）。
- 该坑还意味着：任何「JVM 删非空目录树」的场景在本容器都可能复现同类失败，
  排查思路统一：报错是 delete 目录 → 先用 shell 手动删该路径再重试。

## 影响与出路（2026-10-19 修正）
- **修正**：本条 Oct-18 结论「必败、需引擎层修复」不完整——android-build-env 记忆里的 **LD_PRELOAD=/root/tmp/fix_remove.so** 就是解法（musl remove() 只调 unlink，补丁在 unlink 失败时转 rmdir；源码 /root/tmp/fix_remove.c）。带着它跑 `./gradlew assembleArmsoloDebug` 打包成功；不带它才复现本条必败现象。容器重置后 .so 丢失需按 fix_remove.c 重编。
- **长期 up-to-date 掩盖了它**：该任务只要输入指纹不变就不真跑；任何使 java_res 输入变化的改动都会触发必败。
- 编译链（Kotlin/KSP/Hilt/dex/资源合并）全部正常，失败仅在 zip 清理阶段。
- 出路：①LD_PRELOAD 修复（已验证，打包命令见 android-build-env 记忆）；②宿主侧/其他环境出包；③AGP 未来版本改用 NIO 带重试删除可能自愈。
- 检查命令：`ls -d /tmp/tempdir_* | wc -l`（堆积=历史失败痕迹）；`find /tmp -maxdepth 1 -name "tempdir_*" -exec rm -rf {} +` 可安全清理（勿用 rm 通配 /tmp/*）。