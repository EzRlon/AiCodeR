#!/bin/sh
# 顾问团插件安装脚本。
#
# 职责只有两件：
# 1) 把插件包内的服务端源码释放到 ~/.aicode/multi-llm/（此前由 App 启动时从 assets 释放，
#    现在源码随插件走，主程序里不再有这份代码）；
# 2) 调用容器的通用环境工具链安装运行时（浏览器 / Xvfb / python 依赖），
#    重活仍然由 ~/.aicode/provision.sh 的 multillm 场景完成——它是容器的系统工具，
#    不属于本插件，插件只是调用方。
#
# 幂等：provision 场景自带 marker，重复执行会直接跳过；源码复制是覆盖式。
set -e

PLUGIN_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SRC="$PLUGIN_DIR/server"
DST="$HOME/.aicode/multi-llm"

if [ -f "$SRC/server.py" ]; then
    rm -rf "$DST"
    mkdir -p "$DST"
    cp -R "$SRC"/. "$DST"/
    echo "✓ 服务端源码已释放到 $DST"
else
    echo "插件包缺少服务端源码（$SRC/server.py），安装中止" >&2
    exit 1
fi

if [ -f "$HOME/.aicode/provision.sh" ]; then
    sh "$HOME/.aicode/provision.sh" --install multillm
else
    echo "未找到容器环境工具链（~/.aicode/provision.sh），请先在终端执行一次环境准备" >&2
    exit 1
fi
