#!/bin/sh
# 场景：通用开发（基础工具 + Node.js + Python 3 + 图形/VNC 组件）
scenario_name_common() { echo "通用开发（基础工具 + Node.js + Python 3 + VNC）"; }
install_scenario_common() {
    install_light_runtimes "node python" || return $?
    install_vnc_stack
}