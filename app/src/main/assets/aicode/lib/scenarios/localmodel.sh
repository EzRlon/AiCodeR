#!/bin/sh
# 场景：本地模型（llama.cpp 离线推理）
# 在容器里装 llama.cpp server（OpenAI 兼容 /v1 接口），App 把它当一个本地 provider 接入：
# 设置 → 模型 → 添加自定义 provider，baseURL http://127.0.0.1:8080/v1，apiKey 留空。
# 边界（性能功耗纪律）：只装引擎、不启动任何服务，无常驻进程；模型下载需用户确认，默认不开始。
# 适用：Alpine 容器（llama.cpp 包在 Alpine v3.24+ 的 community 仓库；旧版自动挂 v3.24
# community 跨版本源补装，已实测 3.21 可装可跑）。Ollama 为可选降级（glibc 二进制 +
# gcompat，体积 1GB+），失败黄字警告继续、不阻塞主路径。
scenario_name_localmodel() { echo "本地模型（llama.cpp 离线推理）"; }

LOCALMODEL_ROOT="$HOME/.aicode/local-llm"
LOCALMODEL_MODEL_DIR="$LOCALMODEL_ROOT/models"
# 引导用示例模型：Qwen2.5-0.5B-Instruct Q4_K_M（约 470MB，中英文皆可，小任务够用）
LOCALMODEL_EXAMPLE_REPO="Qwen/Qwen2.5-0.5B-Instruct-GGUF"
LOCALMODEL_EXAMPLE_FILE="qwen2.5-0.5b-instruct-q4_k_m.gguf"
LOCALMODEL_EXAMPLE_MIN_BYTES=400000000
LOCALMODEL_OLLAMA_BIN="$LOCALMODEL_ROOT/ollama/bin/ollama"

localmodel_server_ok() {
    command -v llama-server >/dev/null 2>&1 && llama-server --version >/dev/null 2>&1
}

localmodel_ollama_ok() {
    [ -x "$LOCALMODEL_OLLAMA_BIN" ] && "$LOCALMODEL_OLLAMA_BIN" --version >/dev/null 2>&1
}

# 下载助手：curl / wget 双兼容（busybox wget 只认 -T），带断点续传
localmodel_fetch() {
    if command -v curl >/dev/null 2>&1; then
        curl -fL -C - --connect-timeout 20 --max-time 7200 -o "$2" "$1" 2>/dev/null
    elif command -v wget >/dev/null 2>&1; then
        wget -c -q -T 30 -O "$2" "$1" 2>/dev/null
    else
        return 1
    fi
}

# 旧版 Alpine（≤3.23）仓库没有 llama.cpp：需要挂 v3.24 community 跨版本源补装。
# 候选源：优先当前已配镜像的主机（保持用户网络环境可达），官方 dl-cdn 兜底。
# 用 http 与 setup_apk_mirror 同理：apk 对索引与包独立签名校验，传输层不影响完整性。
localmodel_apk_repos() {
    host=$(sed -n 's#https\?://\([^/]*\)/.*#\1#p' /etc/apk/repositories 2>/dev/null | head -1)
    {
        [ -n "$host" ] && echo "http://$host/alpine/v3.24/community"
        echo "http://dl-cdn.alpinelinux.org/alpine/v3.24/community"
    } | awk '!seen[$0]++'
}

# 可选降级分支：Ollama 官方 tgz 装到 ~/.aicode/local-llm/ollama/（glibc 二进制，apk 下需 gcompat）。
# 体积大（1GB+），经用户确认才下载；失败仅黄字警告，不阻塞。
localmodel_install_ollama() {
    if localmodel_ollama_ok; then
        plog "localmodel: Ollama 已就绪，跳过"
        return 0
    fi
    case "$(uname -m)" in
        x86_64) arch=amd64 ;;
        *)      arch=arm64 ;;
    esac
    name="ollama-linux-$arch.tgz"
    dest="$LOCALMODEL_ROOT/$name.tmp"
    mkdir -p "$LOCALMODEL_ROOT"
    if command -v apk >/dev/null 2>&1; then
        pkg_add gcompat >/dev/null 2>&1 || plog "localmodel: gcompat 安装失败（Ollama 可能无法运行）"
    fi
    echo "下载 Ollama（$arch，体积 1GB+，视网速可能较久）..."
    ok=""
    for base in \
        "https://github.com/ollama/ollama/releases/latest/download" \
        "https://ghproxy.net/https://github.com/ollama/ollama/releases/latest/download" \
        "https://gh-proxy.com/https://github.com/ollama/ollama/releases/latest/download"
    do
        echo "  尝试来源：$(echo "$base" | cut -d/ -f3)"
        localmodel_fetch "$base/$name" "$dest" || { rm -f "$dest"; continue; }
        # 官方 tgz 顶层即 bin/ollama，解到专用子目录，使二进制落在校验函数认的路径上
        mkdir -p "$LOCALMODEL_ROOT/ollama"
        tar -xzf "$dest" -C "$LOCALMODEL_ROOT/ollama" 2>/dev/null && ok=1
        rm -f "$dest"
        [ -n "$ok" ] && break
    done
    if localmodel_ollama_ok; then
        plog "localmodel: Ollama 安装完成（$($LOCALMODEL_OLLAMA_BIN --version 2>/dev/null | head -1)）"
        return 0
    fi
    echo "${C_YELLOW}Ollama 安装失败（可选降级，已跳过）：可稍后重跑本场景重试。${C_RESET}"
    plog "localmodel 警告：Ollama 安装失败（降级，不阻塞）"
    return 0
}

# 可选引导：下载示例小模型（约 470MB，hf-mirror 优先、官方源兜底，断点续传）。
# 不强制当场下载：默认跳过，装好引擎即可，模型路径由用户/AI 后续填。
localmodel_offer_model() {
    printf "是否现在下载示例模型（Qwen2.5-0.5B-Instruct Q4_K_M，约 470MB，默认跳过）？[y/N]: "
    read ans
    case "$ans" in
        y|Y) ;;
        *) return 0 ;;
    esac
    mkdir -p "$LOCALMODEL_MODEL_DIR"
    dest="$LOCALMODEL_MODEL_DIR/$LOCALMODEL_EXAMPLE_FILE"
    # 完整性预检：已下完的文件直接返回，避免重跑时断点续传收到 416 被当失败而误删完整模型
    presz=$(wc -c < "$dest" 2>/dev/null || echo 0)
    if [ "$presz" -ge "$LOCALMODEL_EXAMPLE_MIN_BYTES" ] 2>/dev/null; then
        plog "localmodel: 示例模型已存在（$presz 字节），跳过下载"
        return 0
    fi
    for base in \
        "https://hf-mirror.com/$LOCALMODEL_EXAMPLE_REPO/resolve/main" \
        "https://huggingface.co/$LOCALMODEL_EXAMPLE_REPO/resolve/main"
    do
        echo "  尝试来源：$(echo "$base" | cut -d/ -f3)"
        localmodel_fetch "$base/$LOCALMODEL_EXAMPLE_FILE" "$dest" || { rm -f "$dest"; continue; }
        sz=$(wc -c < "$dest" 2>/dev/null || echo 0)
        [ "$sz" -ge "$LOCALMODEL_EXAMPLE_MIN_BYTES" ] 2>/dev/null && { plog "localmodel: 示例模型下载完成（$sz 字节）"; return 0; }
        rm -f "$dest"
    done
    echo "${C_YELLOW}示例模型下载失败（可选步骤，不阻塞）：可稍后重跑本场景重试，或自行下载 gguf 后记住路径。${C_RESET}"
    plog "localmodel 警告：示例模型下载失败"
    return 0
}

install_scenario_localmodel() {
    # 幂等：llama.cpp server 可用即跳过
    if localmodel_server_ok; then
        plog "本地模型引擎已就绪，跳过"
        echo "${C_GREEN}✓ 本地模型引擎已就绪${C_RESET}（启动示例：llama-server -m <模型.gguf> --host 127.0.0.1 --port 8080）"
        return 0
    fi

    # 1) 主路径：apk 源装 llama.cpp（musl 原生，含 llama-server；旧版 Alpine 自动挂 v3.24 跨版本源）
    if command -v apk >/dev/null 2>&1; then
        echo "安装 llama.cpp（含 llama-server，musl 原生）..."
        pkg_add llama.cpp llama-server >/dev/null 2>&1
        if ! localmodel_server_ok; then
            for repo in $(localmodel_apk_repos); do
                echo "  当前仓库无此包，尝试跨版本源：$(echo "$repo" | cut -d/ -f3)"
                apk update >/dev/null 2>&1
                apk add --no-cache llama.cpp llama-server --repository "$repo" >/dev/null 2>&1
                localmodel_server_ok && break
            done
        fi
    fi
    if localmodel_server_ok; then
        plog "localmodel: llama-server 就绪（$(llama-server --version 2>/dev/null | head -1)）"
    else
        echo "${C_RED}llama.cpp 安装失败。${C_RESET}"
        echo "  本场景主路径仅支持 Alpine 容器（llama.cpp 包在 Alpine 仓库）。"
        plog "localmodel 安装失败：llama-server 不可用"
    fi

    # 2) Ollama 可选降级：llama.cpp 装失败（或非 Alpine 容器）时询问，默认不装
    if ! localmodel_server_ok; then
        printf "是否尝试安装 Ollama 作为降级（glibc 官方包，体积 1GB+，默认不装）？[y/N]: "
        read ans
        case "$ans" in
            y|Y) localmodel_install_ollama ;;
        esac
    fi

    engine=""
    if localmodel_server_ok; then
        engine="llama.cpp"
    elif localmodel_ollama_ok; then
        engine="ollama"
    fi
    if [ -z "$engine" ]; then
        echo "${C_RED}本地模型场景安装失败：llama.cpp 与 Ollama 均不可用。可检查网络后重跑本场景。${C_RESET}"
        return 1
    fi

    # 3) 完成标记 + 模型引导（可选，默认跳过）
    mkdir -p "$LOCALMODEL_ROOT"
    date '+%Y-%m-%d %H:%M:%S' > "$LOCALMODEL_ROOT/.installed"
    localmodel_offer_model

    # 4) 下一步引导
    echo ""
    echo "${C_GREEN}✓ 本地模型场景安装完成（引擎：$engine）${C_RESET}"
    if [ "$engine" = "llama.cpp" ]; then
        example=$LOCALMODEL_MODEL_DIR/$LOCALMODEL_EXAMPLE_FILE
        [ -f "$example" ] || example="<你的模型.gguf 路径>"
        echo "  启动服务（OpenAI 兼容 /v1）："
        echo "    llama-server -m $example --host 127.0.0.1 --port 8080"
        echo "  接入 App：设置 → 模型 → 添加自定义 provider"
        echo "    baseURL 填 http://127.0.0.1:8080/v1，apiKey 留空"
    else
        echo "  启动服务：$LOCALMODEL_OLLAMA_BIN serve"
        echo "  接入 App：设置 → 模型 → 添加自定义 provider"
        echo "    baseURL 填 http://127.0.0.1:11434/v1，apiKey 留空"
    fi
    echo "  ${C_DIM}（离线兜底、小任务零 token 成本；推理速度取决于设备性能，模型需另行下载）${C_RESET}"
    plog "localmodel 场景安装完成（引擎：$engine）"
    return 0
}
