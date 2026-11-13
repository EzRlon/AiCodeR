#!/bin/sh
# 用法: run-detekt.sh <文件或目录...>
# 只扫本次改动文件（不做全量基线，存量噪音天然不进报告）
# 输出: 发现清单(stdout/stderr, "e: 文件:行:列 消息 [规则]"); 无发现时无输出
#
# jar 解析顺序：
#   1. 环境变量 DETEKT_JAR（CI 现场下载到临时目录时用这个）
#   2. 同目录 detekt-cli.jar（本地约定，已 gitignore）
#   3. 同目录任意 detekt-cli-*.jar（版本化文件名兜底）
# 本地没有 jar 时从 Maven Central 拉取固定版本到临时目录（不落仓库）。
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="${DETEKT_JAR:-}"
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  if [ -f "$DIR/detekt-cli.jar" ]; then
    JAR="$DIR/detekt-cli.jar"
  else
    JAR="$(ls "$DIR"/detekt-cli-*.jar 2>/dev/null | head -n 1)"
  fi
fi
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  JAR="${TMPDIR:-/tmp}/detekt-cli-1.23.8-all.jar"
  if [ ! -f "$JAR" ]; then
    echo "detekt jar 未找到，从 Maven Central 下载到 $JAR ..." >&2
    curl -fsSL -o "$JAR" \
      https://repo1.maven.org/maven2/io/gitlab/arturbosch/detekt/detekt-cli/1.23.8/detekt-cli-1.23.8-all.jar \
      || { echo "下载 detekt jar 失败" >&2; exit 2; }
  fi
fi

# detekt 的 --input 只接受一个参数（多路径用逗号分隔），不能直接展开 "$@"。
INPUT="$(printf '%s,' "$@" | sed 's/,$//')"
exec java -Xmx2g -jar "$JAR" \
  --input "$INPUT" \
  --config "$DIR/detekt.yml" \
  --excludes "**/build/**"
