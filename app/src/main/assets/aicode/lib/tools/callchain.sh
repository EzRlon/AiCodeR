#!/bin/sh
# 调用链工具：callers=谁调用它 / callees=它调用了哪些项目内符号
# 用法（在项目根执行）: sh ~/.aicode/lib/tools/callchain.sh callers 符号名 [搜索目录]
#       sh ~/.aicode/lib/tools/callchain.sh callees 符号名
# 依赖: tags 索引（--fields=+Kn 重建）+ ast-grep + readtags
CMD="$1"; SYM="$2"
if [ -z "$SYM" ]; then
  echo "用法: callchain.sh callers|callees 符号名 [搜索目录]"
  exit 1
fi
if [ ! -f tags ]; then
  echo "无索引，先重建: ctags -R --languages=Kotlin,Java --exclude=build --fields=+Kn -o tags ."
  exit 1
fi

case "$CMD" in
callers)
  echo "## 定义位置"
  readtags -e "$SYM" | awk -F'\t' '{print $1"  ->  "$2}' | head -5
  echo
  echo "## 调用点（直接/成员/安全调用三种形态，已去重）"
  DIR="${3:-.}"
  {
    ast-grep run -p "$SYM"'($$$A)' -l kotlin "$DIR" 2>/dev/null
    ast-grep run -p '$OBJ.'"$SYM"'($$$A)' -l kotlin "$DIR" 2>/dev/null
    ast-grep run -p '$OBJ?.'"$SYM"'($$$A)' -l kotlin "$DIR" 2>/dev/null
  } | sort -u
  ;;

callees)
  TAG=$(awk -F'\t' -v s="$SYM" '$1==s' tags | head -1)
  if [ -z "$TAG" ]; then echo "未找到符号: $SYM"; exit 1; fi
  FILE=$(echo "$TAG" | cut -f2)
  LINE=$(echo "$TAG" | grep -o 'line:[0-9]*' | cut -d: -f2)
  if [ -z "$LINE" ]; then echo "索引无行号，请用 --fields=+Kn 重建 tags"; exit 1; fi
  echo "## $SYM  @  $FILE:$LINE"
  # 从定义行截取函数体（花括号配平，表达式函数体最多取 60 行）
  awk -v s="$LINE" '
    NR < s { next }
    NR == s { print; n1 = gsub(/\{/, "x"); n2 = gsub(/\}/, "x"); depth += n1 - n2; if (n1 == 0) braceless = 1; next }
    braceless { print; if (NR >= s + 60) exit; next }
    {
      print
      n1 = gsub(/\{/, "x"); n2 = gsub(/\}/, "x")
      depth += n1 - n2
      if (depth <= 0 && NR > s) exit
    }' "$FILE" > /tmp/_cc_body.txt
  # 体内出现的 标识符( 视为调用候选，与 tags 符号名求交集，只留项目内真实符号
  cut -f1 tags | sort -u > /tmp/_cc_syms.txt
  grep -oE '[A-Za-z_][A-Za-z0-9_]*\(' /tmp/_cc_body.txt | tr -d '(' | sort -u > /tmp/_cc_calls.txt
  echo "## 调用的项目内符号（callees）"
  comm -12 /tmp/_cc_calls.txt /tmp/_cc_syms.txt | grep -v "^$SYM\$"
  rm -f /tmp/_cc_body.txt /tmp/_cc_syms.txt /tmp/_cc_calls.txt
  ;;

*)
  echo "未知命令: $CMD（支持 callers / callees）"
  exit 1
  ;;
esac
