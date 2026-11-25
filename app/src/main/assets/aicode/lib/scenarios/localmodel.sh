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
# 引导用示例模型：Qwen3.5-0.8B Q4_K_M（约 508MB，多语言+推理，llama.cpp b12627 原生支持 qwen35 架构）
LOCALMODEL_EXAMPLE_REPO="unsloth/Qwen3.5-0.8B-GGUF"
LOCALMODEL_EXAMPLE_FILE="Qwen3.5-0.8B-Q4_K_M.gguf"
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
    printf "是否现在下载示例模型（Qwen3.5-0.8B Q4_K_M，约 508MB，默认跳过）？[y/N]: "
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

# ── glibc 系（Debian/Ubuntu/Fedora 等）路径 ──
# llama.cpp 官方 release 提供预编译包（约 19MB，含全部依赖 .so），但其 glibc 基线随版本
# 漂移（实测 b12627 需 GLIBC_2.38，Debian 12=2.36 不兼容），故解压后必须实测 --version，
# 跑不起来即整包弃用；最终兜底是源码编译（本机工具链产物天然兼容，实测 -j6 约 5 分钟）。
LOCALMODEL_PREBUILT_TAG=b12627

localmodel_arch_tag() {
    case "$(uname -m)" in
        x86_64) echo x64 ;;
        aarch64|arm64) echo arm64 ;;
        *) return 1 ;;
    esac
}

localmodel_install_bin() {
    # $1 = 含 llama-server 与依赖 .so 的目录；装完必须过 command -v + --version 双校验
    _src="$1"
    _dst="$LOCALMODEL_ROOT/llama-cpp"
    rm -rf "$_dst"
    mkdir -p "$_dst" /usr/local/bin
    cp -a "$_src/." "$_dst/" || return 1
    chmod +x "$_dst/llama-server" 2>/dev/null
    ln -sf "$_dst/llama-server" /usr/local/bin/llama-server
    # 依赖 .so 与二进制同目录：写 ld.so 配置（不依赖产物 rpath，cp/symlink 后仍可靠）
    if [ -d /etc/ld.so.conf.d ] && command -v ldconfig >/dev/null 2>&1; then
        echo "$_dst" > /etc/ld.so.conf.d/zz-local-llama.conf
        ldconfig >/dev/null 2>&1
    fi
    command -v llama-server >/dev/null 2>&1 && llama-server --version >/dev/null 2>&1
}

localmodel_install_prebuilt() {
    _arch=$(localmodel_arch_tag) || return 1
    name="llama-$LOCALMODEL_PREBUILT_TAG-bin-ubuntu-$_arch.tar.gz"
    dest="$LOCALMODEL_ROOT/$name.tmp"
    ext="$LOCALMODEL_ROOT/prebuilt"
    mkdir -p "$LOCALMODEL_ROOT" "$ext"
    echo "下载 llama.cpp 官方预编译包（$name，约 19MB，弱网较久）..."
    ok=""
    for base in \
        "https://github.com/ggml-org/llama.cpp/releases/download/$LOCALMODEL_PREBUILT_TAG" \
        "https://ghproxy.net/https://github.com/ggml-org/llama.cpp/releases/download/$LOCALMODEL_PREBUILT_TAG" \
        "https://gh-proxy.com/https://github.com/ggml-org/llama.cpp/releases/download/$LOCALMODEL_PREBUILT_TAG"
    do
        echo "  尝试来源：$(echo "$base" | cut -d/ -f3)"
        localmodel_fetch "$base/$name" "$dest" || { rm -f "$dest"; continue; }
        rm -rf "$ext"
        mkdir -p "$ext"
        tar -xzf "$dest" -C "$ext" 2>/dev/null && ok=1
        rm -f "$dest"
        [ -n "$ok" ] && break
    done
    [ -z "$ok" ] && { rm -rf "$ext"; return 1; }
    _srv=$(find "$ext" -type f -name llama-server | head -1)
    if [ -z "$_srv" ]; then rm -rf "$ext"; return 1; fi
    _dir=$(dirname "$_srv")
    chmod +x "$_srv" 2>/dev/null
    if ! env LD_LIBRARY_PATH="$_dir" "$_srv" --version >/dev/null 2>&1; then
        plog "localmodel: 预编译包与本机 glibc 不兼容，弃用（回退源码编译）"
        rm -rf "$ext"
        return 1
    fi
    if localmodel_install_bin "$_dir"; then
        rm -rf "$ext"
        return 0
    fi
    rm -rf "$ext"
    return 1
}

localmodel_install_compile() {
    localmodel_arch_tag >/dev/null || return 1
    # 编译依赖按发行版补齐（Debian: build-essential+cmake；已装则秒过）
    if ! command -v cmake >/dev/null 2>&1 || ! command -v g++ >/dev/null 2>&1; then
        case "$PMGR" in
            apt) pkg_add build-essential cmake pkg-config ;;
            dnf|yum) pkg_add gcc-c++ cmake make ;;
            pacman) pkg_add base-devel cmake ;;
            *) return 1 ;;
        esac
    fi
    command -v cmake >/dev/null 2>&1 || { plog "localmodel: cmake 不可用，无法编译"; return 1; }
    src="$LOCALMODEL_ROOT/src"
    if [ ! -f "$src/CMakeLists.txt" ]; then
        dest="$LOCALMODEL_ROOT/src.tar.gz"
        mkdir -p "$LOCALMODEL_ROOT"
        echo "下载 llama.cpp 源码（$LOCALMODEL_PREBUILT_TAG，约 35MB，弱网较久）..."
        ok=""
        for base in \
            "https://github.com/ggml-org/llama.cpp/archive/refs/tags" \
            "https://ghproxy.net/https://github.com/ggml-org/llama.cpp/archive/refs/tags" \
            "https://gh-proxy.com/https://github.com/ggml-org/llama.cpp/archive/refs/tags"
        do
            echo "  尝试来源：$(echo "$base" | cut -d/ -f3)"
            localmodel_fetch "$base/$LOCALMODEL_PREBUILT_TAG.tar.gz" "$dest" || { rm -f "$dest"; continue; }
            rm -rf "$src"
            tar -xzf "$dest" -C "$LOCALMODEL_ROOT" 2>/dev/null \
                && mv "$LOCALMODEL_ROOT/llama.cpp-$LOCALMODEL_PREBUILT_TAG" "$src" && ok=1
            rm -f "$dest"
            [ -n "$ok" ] && break
        done
        [ -z "$ok" ] && return 1
    fi
    build="$LOCALMODEL_ROOT/build"
    echo "编译 llama-server（Release，$(nproc) 并行；实测约 5~15 分钟，请耐心等待，失败重跑可续编）..."
    cmake -S "$src" -B "$build" -DCMAKE_BUILD_TYPE=Release \
        -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_CURL=OFF \
        >/dev/null 2>&1 || { plog "localmodel: cmake 配置失败"; return 1; }
    cmake --build "$build" --target llama-server -j"$(nproc)" >/dev/null 2>&1 \
        || { plog "localmodel: 编译失败（已保留 $build，重跑本场景可续编）"; return 1; }
    [ -f "$build/bin/llama-server" ] || return 1
    localmodel_install_bin "$build/bin"
}

install_scenario_localmodel() {
    # 幂等：llama.cpp server 可用即跳过
    if localmodel_server_ok; then
        # App 状态页只认 .installed 完成标记：引擎就绪但标记丢失时（如容器重建后二进制仍在）
        # 必须补写，否则「一键安装」永远幂等跳过而 UI 永远显示未安装
        mkdir -p "$LOCALMODEL_ROOT"
        date '+%Y-%m-%d %H:%M:%S' > "$LOCALMODEL_ROOT/.installed"
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
    elif localmodel_install_prebuilt || localmodel_install_compile; then
        # glibc 系（Debian 等）：官方预编译 → 源码编译兑底，产物已过 server_ok 校验
        :
    fi
    if localmodel_server_ok; then
        plog "localmodel: llama-server 就绪（$(llama-server --version 2>/dev/null | head -1)）"
    else
        echo "${C_RED}llama.cpp 安装失败。${C_RESET}"
        echo "  Alpine：仓库/跨版本源装 musl 包；其它发行版：官方预编译 → 源码编译，均未成功。"
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
