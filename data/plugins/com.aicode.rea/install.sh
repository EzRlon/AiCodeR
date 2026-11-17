#!/bin/sh
# 分析台插件安装脚本。
#
# 分析引擎（Node 版本、JADX jar、rea-agents）由容器的通用环境工具链安装；
# 插件本身只负责发起安装，不再由主程序内置「rea 场景」这条特权通道。
# 幂等：场景自带 marker，重复执行直接跳过。
set -e

if [ -f "$HOME/.aicode/provision.sh" ]; then
    sh "$HOME/.aicode/provision.sh" --install rea
else
    echo "未找到容器环境工具链（~/.aicode/provision.sh），请先在终端执行一次环境准备" >&2
    exit 1
fi
