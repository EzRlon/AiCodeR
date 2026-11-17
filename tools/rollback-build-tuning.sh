#!/bin/sh
# 用法: rollback-build-tuning.sh <daemon|in-process|jetifier-on|jetifier-off|status>
# 编译提速配置类优化的一键回退/切换：只改 gradle.properties 两行，下次构建即生效。
# 背景（build-speed-baseline.md）：daemon 策略增量编译比 in-process 快约 23%；
# jetifier 实测对本项目依赖字节级零改动（已关闭，唯一代价是首次 transform 重跑）。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROP="$ROOT/gradle.properties"

set_prop() {
    # $1=属性名 $2=新值：整行替换；行不存在则追加
    if grep -q "^$1=" "$PROP"; then
        sed -i "s|^$1=.*|$1=$2|" "$PROP"
    else
        echo "$1=$2" >> "$PROP"
    fi
}

case "$1" in
    daemon)      set_prop "kotlin.compiler.execution.strategy" "daemon";      echo "已切换 Kotlin 编译策略 → daemon" ;;
    in-process)  set_prop "kotlin.compiler.execution.strategy" "in-process";  echo "已切换 Kotlin 编译策略 → in-process" ;;
    jetifier-on) set_prop "android.enableJetifier" "true";                    echo "已开启 Jetifier" ;;
    jetifier-off) set_prop "android.enableJetifier" "false";                  echo "已关闭 Jetifier" ;;
    status)
        echo "── 当前编译调优配置 ──"
        grep -n "kotlin.compiler.execution.strategy\|android.enableJetifier\|kotlin.build.report.output" "$PROP" || true
        exit 0 ;;
    *) echo "用法: $0 <daemon|in-process|jetifier-on|jetifier-off|status>"; exit 1 ;;
esac
