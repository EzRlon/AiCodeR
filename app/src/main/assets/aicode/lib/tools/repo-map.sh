#!/bin/sh
# 全仓结构速览：基于 ctags 索引，输出模块规模 + 核心类清单，供 AI 开工时快速掌握全貌
# 用法（在项目根执行）: sh ~/.aicode/lib/tools/repo-map.sh
# 重建索引: ctags -R --languages=Kotlin,Java --exclude=build --fields=+Kn -o tags .
if [ ! -f tags ]; then
  echo "无索引，先重建: ctags -R --languages=Kotlin,Java --exclude=build --fields=+Kn -o tags ."
  exit 1
fi

echo "# 全仓结构速览"
echo
echo "## 模块规模（src/main/java 下按目录：类型数 | 方法数）"
awk -F'\t' '
$2 ~ /\/src\/main\/java\// && $4 != "" {
  dir = $2
  sub(/.*\/src\/main\/java\/[^\/]+\/[^\/]+\//, "", dir)
  if (dir !~ /\//) dir = "(root级)"
  else sub(/\/[^\/]*$/, "", dir)
  if ($4 ~ /^(class|interface|object|enum|struct)/) cls[dir]++
  else if ($4 == "method") met[dir]++
}
END {
  for (d in cls) printf "%-42s 类型:%-5s 方法:%d\n", d, cls[d], met[d]
  for (d in met) if (!(d in cls)) printf "%-42s 类型:0     方法:%d\n", d, met[d]
}' tags | sort

echo
echo "## 核心 Top 30 类（按成员数，含所属文件）"
awk -F'\t' '
/\tclass:[^\t]*$/ && $4 == "method" {
  c = $0; sub(/.*\tclass:/, "", c)
  n = split(c, a, "."); name = a[n]
  cnt[name]++
  if (!(name in file)) file[name] = $2
}
END {
  for (k in cnt) printf "%5d  %-42s %s\n", cnt[k], k, file[k]
}' tags | sort -rn | head -30

echo
echo "## 钻查方式"
echo "- 列某类全部成员:  awk -F'\\t' '\$4==\"method\" && /\tclass:全限定类名\$/' tags | awk -F'\t' '{print \$1}'"
echo "- 找符号定义:      readtags -e 符号名"
echo "- 结构化搜索:      ast-grep run -p '模式' -l kotlin <源码目录>"
