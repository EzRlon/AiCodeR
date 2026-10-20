#!/bin/sh
# 场景：模型顾问团（multi-llm）——把六家大模型网页版反代成 MCP 工具，
# 装好后 AI 可直接咨询 ChatGPT / Claude / Gemini / Grok / Qwen / DeepSeek。
# 服务端源码由 App 启动时释放到 ~/.aicode/multi-llm/（本脚本只装运行时）。
# 链路（musl 容器实测）：系统 chromium + Xvfb/x11vnc（server 自动拉起）+ pip --target 隔离依赖 +
# playwright manylinux 轮子手工解压（musl 无官方轮子）+ driver/node 软链到系统 node。
# 登录：首次使用需经 VNC 登录各站点（App 的 VNC 小窗或 VNC Viewer 连 127.0.0.1:5901），
# 浏览器 profile 持久化在 /root/chrome_mcp_multi_llm，登录一次长期有效。
# 支持：Alpine 镜像全自动（aarch64/x86_64）；其它发行版尽力而为。
scenario_name_multillm() { echo "模型顾问团（multi-llm：AI 可咨询 ChatGPT/Claude 等六个模型）"; }

MULTI_LLM_HOME="$HOME/.aicode/multi-llm"
MULTI_LLM_SITE="$HOME/.aicode/multi-llm-venv/site"
MULTI_LLM_MARKER="$HOME/.aicode/multi-llm-venv/.installed"
PLAYWRIGHT_VERSION="1.63.0"
MULTI_PIP_INDEX="https://pypi.tuna.tsinghua.edu.cn/simple"
# 与实测可用的依赖组合保持一致（对应服务端代码所适配的 mcp SDK 2.3.0）
MULTI_LLM_PINS="pyee>=13,<14 greenlet>=3.1.1 mcp==2.3.0 mcp-types==2.3.0 pydantic==2.14.0 pydantic-settings==2.15.0 anyio==4.15.1 httpx2==2.13.1 httpcore2==2.13.1 tenacity==9.2.1 python-dotenv==1.2.4"

# pip 安装助手：国内镜像优先，失败回退官方源
multillm_pip_install() {
    pip3 install --no-cache-dir -i "$MULTI_PIP_INDEX" "$@" >/dev/null 2>&1 \
        || pip3 install --no-cache-dir "$@" >/dev/null 2>&1
}

install_scenario_multillm() {
    # 0) 服务端源码必须已由 App 提取（重启 App 即重新释放）
    if [ ! -f "$MULTI_LLM_HOME/server.py" ]; then
        echo "${C_RED}未找到 multi-llm 服务端源码（$MULTI_LLM_HOME/server.py）。请重启 App 后重试。${C_RESET}"
        plog "multi-llm 安装中止：服务端源码缺失"
        return 1
    fi

    # 幂等：标记存在且核心依赖可导入即视为已装（venv 目录被清等异常会自动重装）
    if [ -f "$MULTI_LLM_MARKER" ] && \
       PYTHONPATH="$MULTI_LLM_SITE" python3 -c "import mcp, playwright, pydantic, greenlet" >/dev/null 2>&1; then
        plog "multi-llm 已就绪，跳过"
        echo "${C_GREEN}✓ 模型顾问团已就绪${C_RESET}（设置 → MCP → 列表中的 multi-llm）"
        return 0
    fi

    # 1) Python ≥3.12（依赖按 3.12 实测组合 pin）
    pyver=$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])' 2>/dev/null)
    pymajor=$(echo "$pyver" | cut -d. -f1)
    pyminor=$(echo "$pyver" | cut -d. -f2)
    if ! [ "${pymajor:-0}" -ge 3 ] 2>/dev/null || ! [ "${pyminor:-0}" -ge 12 ] 2>/dev/null; then
        echo "Python 版本不满足（需 ≥3.12，当前 ${pyver:-未安装}），尝试安装..."
        case "$PMGR" in
            apk) pkg_add python3 py3-pip >/dev/null 2>&1 ;;
        esac
        pyver=$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])' 2>/dev/null)
        pymajor=$(echo "$pyver" | cut -d. -f1)
        pyminor=$(echo "$pyver" | cut -d. -f2)
    fi
    if ! [ "${pymajor:-0}" -ge 3 ] 2>/dev/null || ! [ "${pyminor:-0}" -ge 12 ] 2>/dev/null; then
        echo "${C_RED}Python 版本不足（需 ≥3.12，当前 ${pyver:-未安装}）。本场景目前仅保证内置 Alpine 镜像全自动。${C_RESET}"
        plog "multi-llm 安装中止：Python 版本不足（${pyver:-none}）"
        return 1
    fi
    if ! command -v pip3 >/dev/null 2>&1; then
        case "$PMGR" in
            apk) pkg_add py3-pip >/dev/null 2>&1 ;;
        esac
    fi
    if ! command -v pip3 >/dev/null 2>&1; then
        echo "${C_RED}未检测到 pip3，无法安装 Python 依赖。${C_RESET}"
        plog "multi-llm 安装中止：pip3 缺失"
        return 1
    fi

    # 2) 系统依赖：chromium（浏览器本体）+ Xvfb/x11vnc（无头显示与 VNC 登录）。
    #    按缺补装而非整批 add：已装环境下 apk add 也会异常退出（PRoot 下实测 exit 2 无明细），
    #    误判会阻断后续步骤；装后再验证二进制存在，才算真正成功。
    missing=""
    command -v chromium >/dev/null 2>&1 || missing="$missing chromium"
    command -v Xvfb >/dev/null 2>&1 || missing="$missing xvfb"
    command -v x11vnc >/dev/null 2>&1 || missing="$missing x11vnc"
    if [ -n "$missing" ]; then
        echo "安装系统依赖（chromium / Xvfb / x11vnc，首次约百余 MB）..."
        case "$PMGR" in
            apk)  pkg_add $missing >/dev/null 2>&1 ;;
            apt)  pkg_add $missing >/dev/null 2>&1 ;;
            *)    echo "${C_YELLOW}当前发行版暂未适配自动安装（仅保证 Alpine 镜像），请手动安装 chromium/xvfb/x11vnc 后重试。${C_RESET}" ;;
        esac
    fi
    if ! command -v chromium >/dev/null 2>&1 || ! command -v Xvfb >/dev/null 2>&1 || ! command -v x11vnc >/dev/null 2>&1; then
        echo "${C_RED}系统依赖安装失败（chromium/Xvfb/x11vnc 缺失），请检查网络后重试。${C_RESET}"
        plog "multi-llm 安装失败：系统依赖缺失"
        return 1
    fi

    # 3) playwright 轮子：musl 无官方轮子，下载 manylinux 轮子后手工解压（aarch64/x86_64）
    echo "下载 playwright 引擎（v$PLAYWRIGHT_VERSION，约 35MB）..."
    case "$(uname -m)" in
        aarch64) PW_PLATFORM="manylinux2014_aarch64" ;;
        x86_64)  PW_PLATFORM="manylinux2014_x86_64" ;;
        *) echo "${C_RED}不支持的架构：$(uname -m)。${C_RESET}"; plog "multi-llm 安装失败：架构不支持"; return 1 ;;
    esac
    PW_DIR="$HOME/.aicode/multi-llm-venv/wheel"
    mkdir -p "$PW_DIR" "$MULTI_LLM_SITE"
    rm -f "$PW_DIR"/playwright-*.whl
    pip3 download --no-deps --platform "$PW_PLATFORM" --only-binary=:all: --python-version 3.12 \
        -i "$MULTI_PIP_INDEX" "playwright==$PLAYWRIGHT_VERSION" -d "$PW_DIR" >/dev/null 2>&1 \
        || pip3 download --no-deps --platform "$PW_PLATFORM" --only-binary=:all: --python-version 3.12 \
            "playwright==$PLAYWRIGHT_VERSION" -d "$PW_DIR" >/dev/null 2>&1
    PW_WHL=$(ls "$PW_DIR"/playwright-*.whl 2>/dev/null | head -1)
    if [ -z "$PW_WHL" ]; then
        echo "${C_RED}playwright 轮子下载失败（网络原因），可稍后重跑本场景重试。${C_RESET}"
        plog "multi-llm 安装失败：playwright 轮子下载失败"
        return 1
    fi
    python3 -m zipfile -e "$PW_WHL" "$MULTI_LLM_SITE/" >/dev/null 2>&1 || {
        echo "${C_RED}playwright 轮子解压失败。${C_RESET}"; plog "multi-llm 安装失败：轮子解压失败"; return 1
    }

    # 4) playwright 驱动的 node 为 glibc 二进制（musl 报 fcntl64 符号错误）：软链到系统 node
    DRIVER_DIR="$MULTI_LLM_SITE/playwright/driver"
    rm -f "$DRIVER_DIR/node"
    ln -sf /usr/bin/node "$DRIVER_DIR/node" 2>/dev/null
    chmod +x "$DRIVER_DIR/node" 2>/dev/null

    # 5) Python 依赖（pip --target 隔离安装，不污染系统站点；镜像优先回退官方）
    echo "安装 Python 依赖..."
    # shellcheck disable=SC2086
    multillm_pip_install --upgrade --target "$MULTI_LLM_SITE" $MULTI_LLM_PINS || {
        echo "${C_RED}Python 依赖安装失败（网络原因），可稍后重跑本场景重试。${C_RESET}"
        plog "multi-llm 安装失败：pip 依赖安装失败"
        return 1
    }

    # 6) 自检 + 完成标记 + 引导
    if ! PYTHONPATH="$MULTI_LLM_SITE" python3 -c "import mcp, playwright, pydantic, greenlet" >/dev/null 2>&1; then
        echo "${C_RED}依赖自检未通过，可稍后重跑本场景重试。${C_RESET}"
        plog "multi-llm 安装失败：导入自检失败"
        return 1
    fi
    date '+%Y-%m-%d %H:%M:%S' > "$MULTI_LLM_MARKER"
    echo ""
    echo "${C_GREEN}✓ 模型顾问团安装完成${C_RESET}"
    echo "  下一步：设置 → MCP 服务器 → 列表中的 multi-llm 打开开关"
    echo "  首次使用需要登录各模型网站：打开 VNC 小窗（或 VNC Viewer 连 127.0.0.1:5901），"
    echo "  在弹出的浏览器里登录后即可长期使用（登录一次，profile 持久保存）。"
    echo "  然后直接对 AI 说：帮我问问 ChatGPT，……"
    plog "multi-llm 场景安装完成"
    return 0
}
