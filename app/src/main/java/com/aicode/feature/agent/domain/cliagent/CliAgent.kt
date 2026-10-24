package com.aicode.feature.agent.domain.cliagent

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
    /** 终端标签里实际执行的启动命令；默认裸命令，需要启动前自愈/预处理的智能体覆写。 */
    val launchCommand: String = command
) {
    /**
     * 固定版本的安装命令；npm 缺失时提示先装 Node.js 环境（aicode 菜单）。
     *
     * 下载主体是数百 MB 的平台二进制，无 VPN 直连 npmjs 官方源极易超时：国内镜像
     * （npmmirror）优先、探测不可达回退官方源；镜像安装失败时再回退官方源重试一次。
     * 不写入 /root/.npmrc，海外环境仍可走官方源。
     */
    val installCommand: String =
        "if ! command -v npm >/dev/null 2>&1; then " +
            "echo '未检测到 Node.js/npm：请先在终端执行 aicode，选择安装 Node.js 环境后重试'; exit 127; fi; " +
            "REG=https://registry.npmmirror.com; " +
            "if command -v curl >/dev/null 2>&1; then " +
            "curl -fsI --max-time 4 \"\$REG/\" >/dev/null 2>&1 || REG=https://registry.npmjs.org; " +
            "elif command -v wget >/dev/null 2>&1; then " +
            "wget -q --spider -T 4 \"\$REG/\" || REG=https://registry.npmjs.org; fi; " +
            "echo \"npm registry: \$REG\"; " +
            "NPM_ARGS='--fetch-retries=3 --fetch-timeout=600000 --no-audit --no-fund'; " +
            "if ! npm install -g --registry=\"\$REG\" \$NPM_ARGS $npmPackage@$version; then " +
            "if [ \"\$REG\" != 'https://registry.npmjs.org' ]; then " +
            "echo '镜像源安装失败，改用官方源重试…'; " +
            "npm install -g --registry=https://registry.npmjs.org \$NPM_ARGS $npmPackage@$version; " +
            "else echo '安装失败：请检查网络/代理后重试'; exit 1; fi; fi; " +
            "echo '───'; " +
            "if command -v $command >/dev/null 2>&1; then " +
            "echo \"安装成功：\$($command --version 2>/dev/null | head -1)\"; " +
            "else P=\$(npm prefix -g 2>/dev/null)/bin; " +
            // npm 已报安装成功但 PATH 探测不到：大概率是全局 bin 目录不在当前会话 PATH。
            // 探测真实 bin 目录后直接给出可复制的 export 命令，省去用户盲猜与重启。
            "if [ -x \"\$P/$command\" ]; then " +
            "echo '已安装但当前会话未检测到（PATH 未含全局 bin），执行下面这行立即可用，重启容器永久生效：'; " +
            "echo \"export PATH=\$P:\$PATH\"; " +
            "else echo '安装流程结束但未检测到命令，请重启容器后重试'; fi; fi"

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
    val ALL: List<CliAgent> = listOf(
        CliAgent(
            id = "claude",
            displayName = "Claude Code",
            npmPackage = "@anthropic-ai/claude-code",
            version = "2.1.296",
            command = "claude",
            websiteUrl = "https://claude.com/product/claude-code"
        ),
        CliAgent(
            id = "codex",
            displayName = "Codex",
            npmPackage = "@openai/codex",
            version = "0.162.1",
            command = "codex",
            websiteUrl = "https://developers.openai.com/codex",
            launchCommand = selfHealingLaunch("codex")
        ),
        CliAgent(
            id = "opencode",
            displayName = "OpenCode",
            npmPackage = "opencode-ai",
            version = "1.19.0",
            command = "opencode",
            websiteUrl = "https://opencode.ai"
        )
    )
}
