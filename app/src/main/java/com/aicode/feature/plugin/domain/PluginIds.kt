package com.aicode.feature.plugin.domain

/**
 * 随仓库 `data/plugins` 发布、与本 App 功能位一一对应的插件 id。
 *
 * 入口显隐、旧版内置插件的清理都以这里的常量为准，避免字符串散落在 UI、迁移与清单校验三处。
 * 注意：这里只登记「宿主认识」的插件；第三方插件不在此列，宿主对它们保持零特例。
 */
object PluginIds {
    /** 顾问团（multi-llm）：把多家大模型网页版接入为 MCP 工具。 */
    const val MULTILLM = "com.aicode.multillm"

    /** 分析台（REA）：App / APK / JS 包分析与证据包。 */
    const val REA = "com.aicode.rea"
}
