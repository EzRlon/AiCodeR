#!/usr/bin/env bash
# 人在环复现回路：复制本文件，改下面的步骤，然后运行。
# AI 跑脚本，用户在终端里照提示操作。
#
# 用法：bash hitl-loop.template.sh
#
# 两个辅助函数：
#   step "说明"         → 显示操作说明，等用户按回车
#   capture 变量名 "问题" → 显示问题，把回答存进变量
#
# 结束时把抓到的值打印成 KEY=VALUE，供 AI 解析。
#
# capture 会把值打回终端供 AI 读取，所以用它记观察结果；
# 登录这类操作留给用户做的，用 step。
set -euo pipefail

step() {
  printf '\n>>> %s\n' "$1"
  read -r -p " [完成后按回车] " _
}

capture() {
  local var="$1" question="$2" answer
  printf '\n>>> %s\n' "$question"
  read -r -p " > " answer
  printf -v "$var" '%s' "$answer"
}

# --- 在这里改 -----------------------------------------------------------

step "在设备上打开 App，进入设置页。"
capture ERRORED "点一下「导出日志」按钮。有报错吗？（y/n）"
capture ERROR_MSG "把报错信息贴过来（没有就填 none）："

# --- 改到这里 -----------------------------------------------------------

printf '\n--- 抓到的值 ---\n'
printf 'ERRORED=%s\n' "$ERRORED"
printf 'ERROR_MSG=%s\n' "$ERROR_MSG"
