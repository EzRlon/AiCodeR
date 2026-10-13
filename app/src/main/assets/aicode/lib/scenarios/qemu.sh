#!/bin/sh
# 场景：QEMU 虚拟机（qemu-system-* + qemu-img）
# 与 PRoot 互补：PRoot 只模拟用户态，QEMU 可跑带内核的完整虚拟机（系统级模拟）。
# Android 手机 CPU 性能有限，建议跑轻量系统（Alpine / Debian netboot、小内核镜像）。
scenario_name_qemu() { echo "QEMU 虚拟机（qemu-system-* / qemu-img）"; }
install_scenario_qemu() {
    if command -v qemu-system-x86_64 >/dev/null 2>&1 || command -v qemu-system-aarch64 >/dev/null 2>&1; then
        plog "QEMU 已就绪，跳过"
        return 0
    fi
    pkgs=$(qemu_pkgs)
    if [ -z "$pkgs" ]; then
        plog "QEMU：包管理器 ${PMGR:-未识别} 无映射，跳过"
        return 0
    fi
    echo "安装 QEMU（$pkgs；体积较大，首次安装需较长时间）..."
    if pkg_add $pkgs; then
        plog "QEMU 安装完成"
        return 0
    else
        echo "${C_RED}QEMU 安装失败${C_RESET}"
        plog "QEMU 安装失败"
        return 1
    fi
}
# 系统模拟器：x86_64 与 aarch64 两个目标机架构各有独立包；qemu-img 管虚拟磁盘镜像。
qemu_pkgs() {
    case "$PMGR" in
        apk) echo "qemu-system-x86_64 qemu-system-aarch64 qemu-img" ;;
        apt) echo "qemu-system-x86 qemu-system-arm qemu-utils" ;;
        *)   echo "" ;;
    esac
}
