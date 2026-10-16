#!/bin/sh
# 场景：逆向分析（REA · Reverse Engineer Anything）
# 装好分析引擎与桥接后：设置 → MCP → 添加 → 推荐区 → 点选「REA 逆向工程」→ 保存，
# 即可直接对 AI 说「帮我分析这个 App/APK/JS 包是怎么实现的」。
# 说明：仅用于分析你拥有或获授权分析的软件（正当用途）。
scenario_name_rea() { echo "逆向分析（REA：分析 App / APK / JS 包是怎么实现的）"; }

# 受版本约束的组件（与 MCP 预设 RecommendedReaConfig 保持一致；升级需同步两处）
REA_AGENTS_VERSION="6.5.0"
REA_JADX_JAR_NAME="jadx-headless-mcp-0.7.1-all.jar"
REA_JADX_SHA256="6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75"
REA_DIR="/opt/rea"

# 下载助手：curl / wget 双兼容（provision 环境通常有 curl；老环境可能只有 wget）。
rea_download() {
    if command -v curl >/dev/null 2>&1; then
        curl -fL --connect-timeout 20 --max-time 900 -o "$2" "$1" 2>/dev/null
    elif command -v wget >/dev/null 2>&1; then
        wget -q --timeout=30 --tries=2 -O "$2" "$1" 2>/dev/null
    else
        return 1
    fi
}

install_scenario_rea() {
    # 幂等：标记与核心产物齐全直接跳过
    if [ -f "$REA_DIR/.installed" ] && [ -f "$REA_DIR/$REA_JADX_JAR_NAME" ]; then
        plog "REA 逆向分析环境已就绪，跳过"
        echo "${C_GREEN}✓ 逆向分析环境已就绪${C_RESET}（设置 → MCP → 添加 → 推荐区 选「REA 逆向工程」）"
        return 0
    fi

    # 1) procps：REA 的进程监督要求 procps 兼容的 ps（busybox ps 不兼容，缺失会导致分析被拒）
    echo "检查基础组件（procps）..."
    if ! ps --version 2>/dev/null | grep -qi procps; then
        pkg_add procps >/dev/null 2>&1 || plog "procps 安装失败（继续，若 ps 已兼容则无碍）"
    fi

    # 2) Node ≥ 22（REA 运行要求）
    node_v=$(node -v 2>/dev/null || echo "")
    node_major=$(echo "$node_v" | sed 's/^v//' | cut -d. -f1)
    if [ -z "$node_major" ] || [ "$node_major" -lt 22 ] 2>/dev/null; then
        echo "Node 版本不满足（需 ≥22，当前 ${node_v:-未安装}），尝试安装..."
        case "$PMGR" in
            apk) pkg_add nodejs npm >/dev/null 2>&1 ;;
            apt) pkg_add nodejs npm >/dev/null 2>&1 ;;
        esac
        node_v=$(node -v 2>/dev/null || echo "")
        node_major=$(echo "$node_v" | sed 's/^v//' | cut -d. -f1)
    fi
    if [ -z "$node_major" ] || [ "$node_major" -lt 22 ] 2>/dev/null; then
        echo "${C_RED}Node.js 版本不足（需 ≥22，当前 ${node_v:-未安装}）。${C_RESET}"
        echo "请先安装/升级 Node.js（「环境安装」里的 Node.js 场景），再重跑本场景。"
        plog "REA 安装中止：Node 版本不足（${node_v:-none}）"
        return 1
    fi
    plog "Node 检查通过：$node_v"

    # 3) 完整 JDK ≥17（JADX 引擎需要 javac；缺失时 APK 分析不可用，JS/包分析仍可用）
    if ! command -v javac >/dev/null 2>&1; then
        echo "安装 JDK（APK 反编译需要）..."
        case "$PMGR" in
            apk) pkg_add openjdk17 >/dev/null 2>&1 ;;
            apt) pkg_add openjdk-17-jdk >/dev/null 2>&1 ;;
        esac
    fi
    if ! command -v javac >/dev/null 2>&1; then
        echo "${C_YELLOW}未检测到完整 JDK（javac）。APK 分析暂不可用（可稍后装好「Java 开发」场景再重跑本场景）；JS/Electron 分析不受影响。${C_RESET}"
        plog "REA 警告：javac 缺失，APK 分析将不可用"
    fi

    # 4) JADX 引擎（多源下载 + sha256 校验；与 REA 官方 pin 值一致）
    mkdir -p "$REA_DIR"
    jar_ok=""
    if [ -f "$REA_DIR/$REA_JADX_JAR_NAME" ]; then
        if echo "$REA_JADX_SHA256  $REA_DIR/$REA_JADX_JAR_NAME" | sha256sum -c - >/dev/null 2>&1; then
            jar_ok=1
        fi
    fi
    if [ -z "$jar_ok" ]; then
        echo "下载 APK 分析引擎（jadx-headless-mcp 0.7.1，约 43MB）..."
        for base in \
            "https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1" \
            "https://ghproxy.net/https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1" \
            "https://gh-proxy.com/https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1"
        do
            echo "  尝试来源：$(echo "$base" | cut -d/ -f3)"
            rea_download "$base/$REA_JADX_JAR_NAME" "$REA_DIR/$REA_JADX_JAR_NAME.tmp" || { rm -f "$REA_DIR/$REA_JADX_JAR_NAME.tmp"; continue; }
            if echo "$REA_JADX_SHA256  $REA_DIR/$REA_JADX_JAR_NAME.tmp" | sha256sum -c - >/dev/null 2>&1; then
                mv "$REA_DIR/$REA_JADX_JAR_NAME.tmp" "$REA_DIR/$REA_JADX_JAR_NAME"
                jar_ok=1
                break
            fi
            rm -f "$REA_DIR/$REA_JADX_JAR_NAME.tmp"
        done
        if [ -z "$jar_ok" ]; then
            echo "${C_RED}APK 分析引擎下载失败（网络原因）。可稍后重跑本场景重试。${C_RESET}"
            plog "REA 安装失败：jadx jar 下载/校验失败"
            return 1
        fi
        plog "jadx 引擎下载完成且 sha256 校验通过"
    fi

    # 5) REA 本体（npm 全局安装，版本 pin；官方源失败回退国内镜像）
    if [ ! -d "$(npm root -g 2>/dev/null)/rea-agents" ]; then
        echo "安装 REA 组件（npm，首次下载约几十 MB）..."
        npm install -g "rea-agents@$REA_AGENTS_VERSION" --loglevel=error >/dev/null 2>&1 \
            || npm install -g "rea-agents@$REA_AGENTS_VERSION" --registry=https://registry.npmmirror.com --loglevel=error >/dev/null 2>&1
    fi
    if [ ! -d "$(npm root -g 2>/dev/null)/rea-agents" ]; then
        echo "${C_RED}REA 组件安装失败（npm 网络原因）。可稍后重跑本场景重试。${C_RESET}"
        plog "REA 安装失败：npm 安装 rea-agents 失败"
        return 1
    fi
    plog "REA 组件安装完成：rea-agents@$REA_AGENTS_VERSION"

    # 6) 完成标记与引导
    date '+%Y-%m-%d %H:%M:%S' > "$REA_DIR/.installed"
    echo ""
    echo "${C_GREEN}✓ 逆向分析环境安装完成${C_RESET}"
    echo "  下一步：设置 → MCP → 添加 → 推荐区 → 点选「REA 逆向工程」→ 保存"
    echo "  然后直接对 AI 说：帮我分析一下 ~/workspace/xxx.apk 是怎么实现的"
    echo "  ${C_DIM}（仅用于分析你拥有或获授权分析的软件）${C_RESET}"
    plog "REA 场景安装完成"
    return 0
}
