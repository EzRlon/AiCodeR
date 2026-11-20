package com.aicode.feature.agent.domain.cliagent

/**
 * CLI 终端标签的供应商注入：env 以 `export K='V'; ` 前置于启动命令，argsSuffix 追加在启动命令之后。
 * 无可用供应商时不需要注入，直接按标签自带的裸命令启动。
 */
data class CliTabInjection(val env: Map<String, String> = emptyMap(), val argsSuffix: String = "") {
    /** 拼成可直接交给 shell 的完整命令。 */
    fun apply(command: String): String =
        env.entries.joinToString("") { (k, v) -> "export $k='$v'; " } + command + argsSuffix
}

/** 容器内 AI CLI 智能体定义：npm 包 + 启动命令。 */
data class CliAgent(
    /** 唯一标识，与启动命令同名（claude / codex / opencode）。 */
    val id: String,
    val displayName: String,
    val npmPackage: String,
    /** 锁定的实测可用版本，保证一键安装可复现。 */
    val version: String,
    val command: String,
    val websiteUrl: String,
    /** 官方登录/取 Key 页面：未登录时「去登录」直接开系统浏览器（容器内 CLI 的回调地址在手机上不可达，
     *  所以推荐在浏览器授权后取 API Key 配到 App 供应商，切引擎时自动透传，不必在终端里登录）。 */
    val loginUrl: String = websiteUrl,
    /** 终端标签里实际执行的启动命令；默认裸命令，需要启动前自愈/预处理的智能体覆写。 */
    val launchCommand: String = command
) {
    /**
     * 固定版本的安装命令；npm 缺失时**自动补装 Node.js**（全自动，不再让用户去跑 aicode 菜单）。
     *
     * 补装优先复用容器内的 `~/.aicode/lib/env-common.sh`（已处理各发行版包管理器、镜像与
     * 官方二进制兜底）；旧容器没有该库时再按 apk/apt/dnf/yum/pacman 直接安装。
     *
     * 下载主体是数百 MB 的平台二进制，无 VPN 直连 npmjs 官方源极易超时：国内镜像
     * （npmmirror）优先、探测不可达回退官方源；镜像安装失败时再回退官方源重试一次。
     * 不写入 /root/.npmrc，海外环境仍可走官方源。
     */
    val installCommand: String =
        "ensure_npm() { " +
            "command -v npm >/dev/null 2>&1 && return 0; " +
            "echo '未检测到 Node.js/npm，正在自动安装 Node.js 环境…'; " +
            "if [ -f \"\$HOME/.aicode/lib/env-common.sh\" ]; then " +
                ". \"\$HOME/.aicode/lib/env-common.sh\"; detect_pmgr; " +
                // 22 而非 18：claude-code 2.x 要求 node>=22，node18+npm9 会直接崩溃（Exit handler
                // never called），镜像/官方源三次重试全灭，新用户容器怎么装都装不上。
                "env_ensure_node 22.0.0 >/dev/null 2>&1 || true; " +
                "command -v npm >/dev/null 2>&1 || { [ -n \"\$PMGR\" ] && pkg_add npm >/dev/null 2>&1 || true; }; " +
            "fi; " +
            "command -v npm >/dev/null 2>&1 && return 0; " +
            "if command -v apk >/dev/null 2>&1; then apk add --no-cache nodejs npm; " +
            "elif command -v apt-get >/dev/null 2>&1; then export DEBIAN_FRONTEND=noninteractive; apt-get update -y && apt-get install -y nodejs npm; " +
            "elif command -v dnf >/dev/null 2>&1; then dnf install -y nodejs npm; " +
            "elif command -v yum >/dev/null 2>&1; then yum install -y nodejs npm; " +
            "elif command -v pacman >/dev/null 2>&1; then pacman -Sy --noconfirm nodejs npm; " +
            "fi; " +
            "command -v npm >/dev/null 2>&1; " +
        "}; " +
        "ensure_npm || { echo 'Node.js 自动安装失败：请检查网络/代理后重试'; exit 127; }; " +
        "echo \"Node: \$(node -v 2>/dev/null) / npm: \$(npm -v 2>/dev/null)\"; " +
        "REG=https://registry.npmmirror.com; " +
            "if command -v curl >/dev/null 2>&1; then " +
            "curl -fsI --max-time 4 \"\$REG/\" >/dev/null 2>&1 || REG=https://registry.npmjs.org; " +
            "elif command -v wget >/dev/null 2>&1; then " +
            "wget -q --spider -T 4 \"\$REG/\" || REG=https://registry.npmjs.org; fi; " +
            "echo \"npm registry: \$REG\"; " +
            "NPM_ARGS='--fetch-retries=3 --fetch-timeout=600000 --no-audit --no-fund'; " +
            "try_install() { npm install -g --registry=\"\$1\" \$NPM_ARGS $npmPackage@$version; }; " +
            // 全新安装前先清掉中断残留的损坏安装：npm 装到一半的包命令不可用，
            // 不卸载直接重装，残留文件会让新版本安装后依旧失效。
            "npm uninstall -g $npmPackage >/dev/null 2>&1 || true; " +
            // npm 中断还会残留 .包名-随机 临时目录（uninstall/install 均报 ENOTEMPTY）
            // 与全局 bin 死链（install 报 EEXIST），残留后怎么重装都失败；用 node fs
            // 程序化清掉包目录、scope 内残留与 bin 同名残留，保证重装自愈。
            "NODE_ROOT=\$(npm root -g 2>/dev/null); " +
            "if [ -n \"\$NODE_ROOT\" ]; then " +
            "node -e 'const fs=require(\"fs\");const root=process.argv[1],pkg=process.argv[2],cmd=process.argv[3],bin=process.argv[4];const segs=pkg.split(\"/\");const name=segs[segs.length-1].split(\"@\")[0];try{fs.rmSync(root+\"/\"+segs.join(\"/\"),{recursive:true,force:true});}catch(e){}const scopeDir=segs.length>1?root+\"/\"+segs[0]:root;try{for(const d of fs.readdirSync(scopeDir)){if(d.startsWith(\".\")&&d.includes(name)){fs.rmSync(scopeDir+\"/\"+d,{recursive:true,force:true});}}}catch(e){}try{fs.unlinkSync(bin+\"/\"+cmd);}catch(e){}' \"\$NODE_ROOT\" \"$npmPackage\" \"$command\" \"\$(npm prefix -g 2>/dev/null)/bin\" >/dev/null 2>&1 || true; " +
            "fi; " +
            // npmmirror 对平台二进制包（含 bin 的 optionalDependencies）返回 cnpm 风格 .l2s.* 硬链布局，
            // npm 装完后 bin 符号链接指向不存在的文件（命令 not found），npm 自身退出码却为 0；
            // 因此装完必须实测命令可执行，断链时卸载后回退官方源重装。
            "if ! try_install \"\$REG\"; then " +
            "if [ \"\$REG\" != 'https://registry.npmjs.org' ]; then " +
            "echo '镜像源安装失败，改用官方源重试…'; " +
            "try_install https://registry.npmjs.org; " +
            "else echo '安装失败：请检查网络/代理后重试'; exit 1; fi; fi; " +
            "if ! $command --version >/dev/null 2>&1; then " +
            "if [ \"\$REG\" != 'https://registry.npmjs.org' ]; then " +
            "echo '镜像源安装产物异常（命令不可执行），卸载后改用官方源重装…'; " +
            "npm uninstall -g $npmPackage >/dev/null 2>&1; " +
            "try_install https://registry.npmjs.org; " +
            "fi; fi; " +
            "echo '───'; " +
            // 成功与否以版本命令实测为准：软链存在但原生二进制缺失（如 claude 的 install.cjs 未执行）
            // 时命令并不可用，只看 command -v 会假报成功，让设置页复检必然失败。
            "if $command --version >/dev/null 2>&1; then " +
            "echo \"安装成功：\$($command --version 2>/dev/null | head -1)\"; " +
            "else P=\$(npm prefix -g 2>/dev/null)/bin; " +
            // npm 已报安装成功但 PATH 探测不到：全局 bin 目录不在 PATH。先尝试软链到
            // /usr/local/bin（恒在 PATH 中），让「点安装」直接可用；仍不行才提示手动 export。
            "mkdir -p /usr/local/bin 2>/dev/null; " +
            "if [ -x \"\$P/$command\" ]; then " +
            "ln -sf \"\$P/$command\" /usr/local/bin/$command 2>/dev/null; " +
            "hash -r 2>/dev/null; fi; " +
            // 部分包（如 claude-code）安装时原生二进制的 postinstall 未随 npm 执行，命令软链存在但本体缺失；
            // 补跑包自带的 install.cjs 完成平台二进制下载，补完后再次实测版本。
            "PKG_DIR=\$(npm root -g 2>/dev/null)/$npmPackage; " +
            "if [ -f \"\$PKG_DIR/install.cjs\" ]; then " +
            "echo '命令不可执行：补跑包原生二进制 postinstall（install.cjs）…'; " +
            "node \"\$PKG_DIR/install.cjs\" 2>&1 || true; " +
            "fi; " +
            "if $command --version >/dev/null 2>&1; then " +
            "echo \"安装成功：\$($command --version 2>/dev/null | head -1)\"; " +
            "elif command -v $command >/dev/null 2>&1; then " +
            "echo '命令存在但版本检测失败：安装可能被中断，请检查网络后重试'; exit 1; " +
            "elif [ -x \"\$P/$command\" ]; then " +
            "echo \"已安装但当前会话未检测到，请执行 export PATH=\$P:\$PATH（重启容器永久生效）\"; " +
            // 真失败必须返回非零，让设置页如实显示「安装失败」，而不是假成功。
            "else echo '安装失败：未生成可执行命令，请检查网络/代理后重试'; exit 1; fi; fi"

    /** 检测命令：已安装则输出首行版本号，未安装静默失败。 */
    val detectCommand: String =
        "command -v $command >/dev/null 2>&1 && $command --version 2>/dev/null | head -1"
}

/**
 * Codex 启动自愈脚本。其后台守护进程读进程启动时间用 procps 语法（`ps -p <pid> -o lstart=`），
 * BusyBox ps 不认；更深的限制是 Android 宿主对容器 /proc 的挂载策略（hidepid，/proc/stat
 * 不可读）——lstart 需要 /proc/stat 的 btime 换算，此环境装真 procps 也恒失败。
 * 三层判定：① lstart 可用直接启动；② /proc/stat 不可读（procps 无解）→ 如实提示后
 * --no-daemon 前台模式（交互功能完整，仅无后台守护）；③ 其余（BusyBox ps 但 /proc 开放）
 * 按缺补装 procps 后重试（apk 对已装包必 exit 2，须预检），仍失败再降级 --no-daemon。
 */
private fun selfHealingLaunch(command: String): String =
    "if ps -p 1 -o lstart= >/dev/null 2>&1; then " +
        "$command; " +
    "elif ! [ -r /proc/stat ]; then " +
        "echo '容器 /proc 受限（宿主挂载策略），以前台模式启动 Codex（--no-daemon）。'; " +
        "$command --no-daemon; " +
    "else " +
        "echo '检测到 BusyBox ps（Codex 守护进程依赖 procps），正在补装…'; " +
        "apk info -e procps >/dev/null 2>&1 || apk add procps 2>/dev/null || " +
        "apt-get install -y procps 2>/dev/null || dnf install -y procps 2>/dev/null || " +
        "yum install -y procps 2>/dev/null || pacman -Sy --noconfirm procps-ng 2>/dev/null; " +
        "if ps -p 1 -o lstart= >/dev/null 2>&1; then " +
            "$command; " +
        "else " +
            "echo 'procps 补装失败（请检查网络），降级为前台模式（--no-daemon）启动。'; " +
            "$command --no-daemon; " +
        "fi; " +
    "fi"

/** 内置智能体目录：三个容器实测跑通的 AI CLI。 */
object CliAgentCatalog {

    /** 按会话引擎 id（"claude"/"codex"/"opencode"）查找目录项；未知返回 null。 */
    fun byEngine(engine: String): CliAgent? = ALL.firstOrNull { it.id == engine }

    val ALL: List<CliAgent> = listOf(
        CliAgent(
            id = "claude",
            displayName = "Claude Code",
            npmPackage = "@anthropic-ai/claude-code",
            version = "2.1.296",
            command = "claude",
            websiteUrl = "https://claude.com/product/claude-code",
            loginUrl = "https://console.anthropic.com/settings/keys"
        ),
        CliAgent(
            id = "codex",
            displayName = "Codex",
            npmPackage = "@openai/codex",
            version = "0.162.1",
            command = "codex",
            websiteUrl = "https://developers.openai.com/codex",
            loginUrl = "https://platform.openai.com/api-keys",
            launchCommand = selfHealingLaunch("codex --config check_for_update_on_startup=false")
        ),
        CliAgent(
            id = "opencode",
            displayName = "OpenCode",
            npmPackage = "opencode-ai",
            version = "1.19.0",
            command = "opencode",
            websiteUrl = "https://opencode.ai",
            loginUrl = "https://opencode.ai/auth"
        )
    )
}
