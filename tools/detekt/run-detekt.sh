#!/bin/sh
# 用法: run-detekt.sh <文件或目录...>
# 只扫本次改动文件（不做全量基线，存量噪音天然不进报告）
# 输出: 发现清单(stdout/stderr, "e: 文件:行:列 消息 [规则]"); 无发现时无输出
# 依赖: 同目录 detekt-cli.jar (华为云镜像 dev/detekt/detekt-cli 2.0.0-alpha.6；其 --report/--create-baseline 落盘有缺陷，故只用控制台输出)
DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -Xmx2g -jar "$DIR/detekt-cli.jar" \
  --input "$@" \
  --config "$DIR/detekt.yml" \
  --excludes "**/build/**"
