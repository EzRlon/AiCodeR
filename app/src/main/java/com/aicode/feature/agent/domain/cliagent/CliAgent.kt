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
    val websiteUrl: String
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
            "else echo '安装失败：请检查网络/代理后重试'; exit 1; fi; fi"

    /** 检测命令：已安装则输出首行版本号，未安装静默失败。 */
    val detectCommand: String =
        "command -v $command >/dev/null 2>&1 && $command --version 2>/dev/null | head -1"
}

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
            websiteUrl = "https://developers.openai.com/codex"
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
