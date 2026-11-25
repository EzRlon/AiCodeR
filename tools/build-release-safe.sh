#!/bin/sh
# 用法: build-release-safe.sh
# 容器内 release 两阶段打包：防「Kotlin daemon + Gradle daemon + R8 三者叠加把
# 手机容器内存压穿、宿主 App 被挤崩」（2026-10-31 实测剩 470MB，CursorWindow 崩溃）。
#
# 原理：单条 assembleArmsoloRelease 会让 Kotlin daemon（RSS ~2.6G）与 Gradle daemon
#（RSS ~3.9G）同时驻留，R8 阶段三者叠加。两阶段法在 Kotlin 编译结束后停掉全部 daemon
# 释放 ~2.6G，让 R8 独享内存；Kotlin 增量结果已落盘，第二阶段只做打包，代价约 +1 分钟。
#
# 复用 tools/build-with-lock.sh 的构建锁：双会话并行同变体会把 KSP 产物写成坏快照。
# 产物复制到仓库根 aicoder-armsolo-release.apk（.gitignore 已覆盖 *.apk 时不会误提交）。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

APK_IN="app/build/outputs/apk/armsolo/release/app-armsolo-release.apk"
APK_OUT="$ROOT/aicoder-armsolo-release.apk"

# ── 阶段 1：只编 release Kotlin（KSP/Hilt/Room 生成也在这一步） ──
echo "=== [0.5/3] 迁移与 schema 下限检查 ==="
if ! python3 scripts/check_migrations.py; then
    echo "迁移检查失败，停止构建"
    exit 1
fi
echo "=== [1/3] compileArmsoloReleaseKotlin ==="
sh tools/build-with-lock.sh compileArmsoloReleaseKotlin

# ── 阶段 2：停全部 daemon，释放 Kotlin 编译期的驻留内存 ──
echo "=== [2/3] 停 daemon 释放内存 ==="
sh gradlew --stop
sleep 2
free -m | head -2

# ── 阶段 3：R8/打包独享内存；Kotlin 编译结果 up-to-date，此步主要是 R8 + merge ──
echo "=== [3/3] assembleArmsoloRelease ==="
sh tools/build-with-lock.sh assembleArmsoloRelease

sh gradlew --stop

if [ -f "$APK_IN" ]; then
    cp "$APK_IN" "$APK_OUT"
    echo "DONE: $APK_OUT"
    ls -la "$APK_OUT"
else
    echo "FAILED: 未找到 $APK_IN"
    exit 1
fi
