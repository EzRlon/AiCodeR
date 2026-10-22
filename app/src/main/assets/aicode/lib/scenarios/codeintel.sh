#!/bin/sh
# 场景：代码智能四件套（ctags 符号索引 + ast-grep 结构化搜索 + repo-map/callchain 工具脚本）
# 装好后 AI 按 code-intel 技能的 SOP 探索代码：建索引 → 查结构 → 拉调用链 → 结构化搜索。
scenario_name_codeintel() { echo "代码智能（ctags 索引 + ast-grep 结构化搜索）"; }

# ast-grep 无 apk 仓库可用版（官方版无 Kotlin parser），走 npm glibc 版 + gcompat；
# --ignore-scripts 跳过 postinstall 的平台检查，直接装对应 libc 平台子包，--force 绕过 libc 断言。
CODEINTEL_NPM_ROOT="/usr/local/lib/node_modules"

codeintel_npm_pkg() {
    case "$(uname -m)" in
        x86_64) echo "@ast-grep/cli-linux-x64-gnu" ;;
        *)      echo "@ast-grep/cli-linux-arm64-gnu" ;;
    esac
}

codeintel_ctags_ok() {
    command -v ctags >/dev/null 2>&1 && command -v readtags >/dev/null 2>&1
}

codeintel_astgrep_ok() {
    command -v ast-grep >/dev/null 2>&1 && ast-grep --version >/dev/null 2>&1
}

install_scenario_codeintel() {
    # 幂等：两个组件都在即跳过
    if codeintel_ctags_ok && codeintel_astgrep_ok; then
        plog "代码智能环境已就绪，跳过"
        echo "${C_GREEN}✓ 代码智能四件套已就绪${C_RESET}（对 AI 说「给项目建索引」即可开始使用）"
        return 0
    fi

    # 1) ctags（Alpine 包名 ctags；Debian/Ubuntu 为 universal-ctags）。ctags 是主组件，失败即中止。
    if ! codeintel_ctags_ok; then
        echo "安装 ctags（符号索引）..."
        case "$PMGR" in
            apk) pkg_add ctags >/dev/null 2>&1 ;;
            apt) pkg_add universal-ctags >/dev/null 2>&1 ;;
        esac
    fi
    if codeintel_ctags_ok; then
        plog "codeintel: ctags 就绪（$(ctags --version 2>/dev/null | head -1)）"
    else
        echo "${C_RED}ctags 安装失败，符号索引不可用。可稍后重跑本场景。${C_RESET}"
        plog "codeintel 安装失败：ctags 不可用"
        return 1
    fi

    # 2) ast-grep（可选增强，失败降级不阻塞：ctags 索引已可用，结构化搜索可后补）
    if ! codeintel_astgrep_ok; then
        echo "安装 ast-grep（结构化搜索，走 npmmirror）..."
        case "$PMGR" in
            apk) pkg_add gcompat >/dev/null 2>&1 || plog "codeintel: gcompat 安装失败（glibc 兼容层缺失，ast-grep 可能无法运行）" ;;
        esac
        if command -v npm >/dev/null 2>&1; then
            pkg="$(codeintel_npm_pkg)"
            npm install -g "$pkg" --registry=https://registry.npmmirror.com --ignore-scripts --force --loglevel=error >/dev/null 2>&1
            if [ -f "$CODEINTEL_NPM_ROOT/$pkg/ast-grep" ]; then
                ln -sf "$CODEINTEL_NPM_ROOT/$pkg/ast-grep" /usr/bin/ast-grep 2>/dev/null
            fi
        else
            plog "codeintel: npm 不可用，ast-grep 跳过（可装好 Node.js 场景后重跑本场景补装）"
        fi
    fi
    if codeintel_astgrep_ok; then
        plog "codeintel: ast-grep 就绪（$(ast-grep --version 2>/dev/null | head -1)）"
    else
        echo "${C_YELLOW}ast-grep 未装成（可选组件）：结构化搜索暂不可用，ctags 索引不受影响；装好 Node.js 场景后重跑本场景可补上。${C_RESET}"
        plog "codeintel 警告：ast-grep 不可用（降级，仅 ctags）"
    fi

    # 3) 完成引导
    echo ""
    echo "${C_GREEN}✓ 代码智能场景安装完成${C_RESET}"
    echo "  对 AI 说「给当前项目建索引」生成 tags；之后探索代码时 AI 会自动按 code-intel 技能用四件套。"
    plog "codeintel 场景安装完成"
    return 0
}
