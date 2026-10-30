#!/bin/sh
# 场景：逆向分析（REA · Reverse Engineer Anything）
# 装好分析引擎与桥接后：设置 → MCP → 添加 → 推荐区 → 点选「REA 逆向工程」→ 保存，
# 即可直接对 AI 说「帮我分析这个 App/APK/JS 包是怎么实现的」。
# 说明：仅用于分析你拥有或获授权分析的软件（正当用途）。
# 支持：任意发行版（apk/apt/dnf/yum/pacman）自动检测后按需安装；Node 不足时先试仓库再装官方预编译包
#（musl 系统不适用官方包，仅走仓库）。
scenario_name_rea() { echo "逆向分析（REA：分析 App / APK / JS 包是怎么实现的）"; }

# 受版本约束的组件（与 MCP 预设 RecommendedReaConfig 保持一致；升级需同步两处）
REA_AGENTS_VERSION="6.5.0"
REA_NODE_MIN="22.19.0"
REA_JADX_JAR_NAME="jadx-headless-mcp-0.7.1-all.jar"
REA_JADX_SHA256="6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75"
REA_DIR="/opt/rea"

# 下载助手：统一走 lib/env-common.sh 的 env_download（curl / wget 双兼容）。
rea_download() {
    env_download "$1" "$2"
}

# Node ≥ 22.19（REA engines 要求 ^22.19.0）
rea_node_ok() {
    env_node_ok "$REA_NODE_MIN"
}

# 完整 JDK ≥17（JADX 需要 javac，且含 jdk.compiler 模块）
rea_javac_ok() {
    v=$(javac -version 2>&1 | sed 's/^javac //')
    [ -z "$v" ] && return 1
    env_ver_ge "$v" "17.0.0"
}

# 已安装的 rea-agents 是否为 pin 版本
rea_agents_installed() {
    root=$(npm root -g 2>/dev/null)
    [ -n "$root" ] && [ -f "$root/rea-agents/package.json" ] || return 1
    v=$(node -e "try{process.stdout.write(require('$root/rea-agents/package.json').version)}catch(e){}" 2>/dev/null)
    [ "$v" = "$REA_AGENTS_VERSION" ]
}

install_scenario_rea() {
    # 幂等：标记、引擎、pin 版本组件齐全直接跳过
    if [ -f "$REA_DIR/.installed" ] && [ -f "$REA_DIR/$REA_JADX_JAR_NAME" ] && rea_agents_installed; then
        plog "REA 逆向分析环境已就绪，跳过"
        echo "${C_GREEN}✓ 逆向分析环境已就绪${C_RESET}（设置 → MCP → 添加 → 推荐区 选「REA 逆向工程」）"
        return 0
    fi

    # 1) procps：REA 的进程监督要求 procps 兼容的 ps（busybox ps 不兼容，缺失会导致分析被拒）
    echo "检查基础组件（procps）..."
    if ! ps --version 2>/dev/null | grep -qi procps; then
        pkg_add procps >/dev/null 2>&1 || plog "procps 安装失败（继续，若 ps 已兼容则无碍）"
    fi

    # 2) Node ≥ 22.19（REA 运行要求）：先试发行版仓库，不够就装官方预编译包。
    #    各发行版仓库版本差异大（Debian 的 nodejs 停在 20.x），统一走 env_ensure_node 探测后按需装。
    if ! rea_node_ok; then
        echo "检测到 Node 版本不满足（需 ≥${REA_NODE_MIN}，当前 $(node -v 2>/dev/null || echo 未安装)），自动安装..."
        env_ensure_node "$REA_NODE_MIN" >/dev/null 2>&1
    fi
    if ! rea_node_ok; then
        echo "${C_RED}Node.js 版本仍不足（需 ≥${REA_NODE_MIN}，当前 $(node -v 2>/dev/null || echo 未安装)）。${C_RESET}"
        echo "  · 已尝试：发行版仓库、nodejs.org / npmmirror 官方二进制包；"
        echo "  · 可手动升级（如 nvm 或下载官方包解压到 /usr/local）后重跑本场景。"
        plog "REA 安装中止：Node 版本不足（$(node -v 2>/dev/null || echo none)）"
        return 1
    fi
    plog "Node 检查通过：$(node -v)"

    # 3) 完整 JDK ≥17（JADX 引擎需要；缺失时 APK 分析不可用，JS/包分析仍可用）。
    #    包名按发行版探测：Debian trixie 只有 21/25 无 17，Alpine 为 openjdkNN-jdk。
    if ! rea_javac_ok; then
        echo "安装 JDK（APK 反编译需要）..."
        case "$PMGR" in
            apk) pkg_add openjdk21-jdk openjdk17-jdk >/dev/null 2>&1 ;;
            apt) pkg_add "$(apt_java_headless)" >/dev/null 2>&1 ;;
            dnf|yum) pkg_add java-21-openjdk-headless java-17-openjdk-headless >/dev/null 2>&1 ;;
            pacman) pkg_add jdk21-openjdk jdk17-openjdk >/dev/null 2>&1 ;;
        esac
    fi
    if ! rea_javac_ok; then
        echo "${C_YELLOW}未检测到完整 JDK（javac ≥17）。APK 分析暂不可用（可稍后装好「Java 开发」场景再重跑本场景）；JS/Electron 分析不受影响。${C_RESET}"
        plog "REA 警告：javac 缺失或版本不足，APK 分析将不可用"
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

    # 5) REA 本体（npm 全局安装，pin 版本；官方源失败回退国内镜像）
    if ! rea_agents_installed; then
        echo "安装 REA 组件（npm，首次下载约几十 MB）..."
        npm install -g "rea-agents@$REA_AGENTS_VERSION" --loglevel=error >/dev/null 2>&1 \
            || npm install -g "rea-agents@$REA_AGENTS_VERSION" --registry=https://registry.npmmirror.com --loglevel=error >/dev/null 2>&1
    fi
    if ! rea_agents_installed; then
        echo "${C_RED}REA 组件安装失败（npm 网络原因）。可稍后重跑本场景重试。${C_RESET}"
        plog "REA 安装失败：npm 安装 rea-agents 失败"
        return 1
    fi
    plog "REA 组件安装完成：rea-agents@$REA_AGENTS_VERSION"

    # 6) 完成标记与引导（完整 JDK 缺失时提示 APK 分析待补）
    date '+%Y-%m-%d %H:%M:%S' > "$REA_DIR/.installed"
    echo ""
    echo "${C_GREEN}✓ 逆向分析环境安装完成${C_RESET}"
    if ! rea_javac_ok; then
        echo "  ${C_YELLOW}注意：缺完整 JDK，APK 分析暂不可用；装好「Java 开发」场景后重跑本场景即可补上。${C_RESET}"
    fi
    echo "  下一步：设置 → MCP → 添加 → 推荐区 → 点选「REA 逆向工程」→ 保存"
    echo "  然后直接对 AI 说：帮我分析一下 ~/workspace/xxx.apk 是怎么实现的"
    echo "  ${C_DIM}（仅用于分析你拥有或获授权分析的软件）${C_RESET}"
    plog "REA 场景安装完成"
    return 0
}
