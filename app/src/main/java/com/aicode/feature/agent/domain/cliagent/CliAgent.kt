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
    /** 固定版本的安装命令；npm 缺失时提示先装 Node.js 环境（aicode 菜单）。 */
    val installCommand: String =
        "if ! command -v npm >/dev/null 2>&1; then " +
            "echo '未检测到 Node.js/npm：请先在终端执行 aicode，选择安装 Node.js 环境后重试'; exit 127; fi; " +
            "npm install -g $npmPackage@$version"

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
