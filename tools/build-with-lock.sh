#!/bin/sh
# 用法: build-with-lock.sh <gradle 任务与参数...>
# 防双会话并行构建（双会话并行同变体会把 KSP 产物写成坏快照，已验证坑）：
# 同一时间只允许一次构建，持锁期间其他调用直接拒绝并提示等待。
# 同时把每次构建的墙钟耗时追加到 ai-config/notes/build-times.csv（构建耗时曲线）。
# 已内置 musl 容器必需的编译环境前缀，调用方无需再带。
# 例子: sh tools/build-with-lock.sh :app:compileArmsoloReleaseKotlin
#       sh tools/build-with-lock.sh assembleArmsoloRelease --no-configuration-cache
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/.build-lock"

acquire_lock() {
    if mkdir "$LOCK" 2>/dev/null; then
        echo $$ > "$LOCK/pid"
        echo "$*" > "$LOCK/cmd"
        return 0
    fi
    pid=$(cat "$LOCK/pid" 2>/dev/null || true)
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        echo "构建锁被占用（pid=$pid：$(cat "$LOCK/cmd" 2>/dev/null || echo '?')）。"
        echo "请等该构建结束再跑；若确认是残留锁（该进程已死），删除 $LOCK 后重试。"
        return 1
    fi
    echo "发现残留锁（pid=${pid:-?} 已不存在），自动接管。"
    rm -rf "$LOCK"
    mkdir "$LOCK"
    echo $$ > "$LOCK/pid"
    echo "$*" > "$LOCK/cmd"
}

acquire_lock
trap 'rm -rf "$LOCK"' EXIT INT TERM

CSV="$ROOT/ai-config/notes/build-times.csv"
[ -f "$CSV" ] || echo "date,time,task,seconds,exit" > "$CSV"

cd "$ROOT"
start=$(date +%s)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ANDROID_HOME=/root/android/sdk \
LD_PRELOAD=/root/tmp/fix_remove.so ./gradlew "$@"
status=$?
end=$(date +%s)

echo "$(date +%F),$(date +%T),$*,$((end - start)),$status" >> "$CSV"
exit $status
