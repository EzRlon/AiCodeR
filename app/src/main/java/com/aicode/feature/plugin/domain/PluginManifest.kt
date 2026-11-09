package com.aicode.feature.plugin.domain

import kotlinx.serialization.Serializable

/**
 * 插件能申请的权限。清单里出现未列出的名字视为清单非法，不做「静默忽略」——
 * 少授权会导致插件运行时莫名失败，不如在安装前就把问题摊开。
 */
enum class PluginPermission(val id: String) {
    CONTAINER_EXEC("container.exec"),
    NETWORK("network"),
    WORKSPACE_READ("workspace.read"),
    WORKSPACE_WRITE("workspace.write"),
    SECRETS("secrets");

    companion object {
        fun fromId(id: String): PluginPermission? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 插件可占用的界面插槽。宿主在固定位置渲染入口，插件只能声明「占哪个、排在谁前/后」，
 * 不能自己决定往哪塞界面——这是「装完像自带功能」的前提：位置由宿主说了算。
 */
enum class PluginSlot(val id: String) {
    SETTINGS_SECTION("settings.section"),
    SIDEBAR_ITEM("sidebar.item"),
    CHAT_TOOLBAR("chat.toolbar"),
    CHAT_TOOLSET("chat.toolset"),
    SCHEDULER_TASK("scheduler.task");

    companion object {
        fun fromId(id: String): PluginSlot? = entries.firstOrNull { it.id == id }
    }
}

enum class SlotPosition(val id: String) {
    BEFORE("before"),
    AFTER("after"),
    APPEND("append");

    companion object {
        fun fromId(id: String): SlotPosition? = entries.firstOrNull { it.id == id }
    }
}

/** 插件目录内的一份内容释放到容器目标路径。 */
@Serializable
data class AssetMapping(
    /** 插件目录内的相对路径，禁止绝对路径与 `..`。 */
    val from: String,
    /** 释放目标，必须是 `~/.aicode/…` 或 `~/workspace/…` 之内。 */
    val to: String
)

/**
 * 容器内环境准备：跑安装脚本，用 marker 文件做幂等判定。
 *
 * [script] 与 [builtinScenario] 二选一：
 * - [script]：插件目录内的相对路径，按 `sh <插件目录>/<script>` 执行；
 * - [builtinScenario]：宿主内置场景 id（见 `lib/scenarios` 下的场景脚本），按
 *   `sh ~/.aicode/provision.sh --install <id>` 执行。场景脚本留在宿主 assets 里，
 *   继续复用共享库（`lib/env-common.sh`）与幂等标记，插件只声明「要哪个场景」。
 */
@Serializable
data class ProvisionSpec(
    val scenario: String,
    val script: String? = null,
    val builtinScenario: String? = null,
    val marker: String? = null
)

@Serializable
data class McpSpec(
    val name: String,
    /** `stdio` 或 `http`。 */
    val transport: String = TRANSPORT_STDIO,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val url: String? = null,
    val env: Map<String, String> = emptyMap(),
    /** 写入宿主 MCP 表后是否直接启用（默认启用，保存即用）。 */
    val enabled: Boolean = true,
    /** 工具白名单：非空时只注册名单内工具（REA 等大工具集必须靠它压 prompt 预算）。 */
    val enabledTools: List<String> = emptyList()
) {
    companion object {
        const val TRANSPORT_STDIO = "stdio"
        const val TRANSPORT_HTTP = "http"
    }
}

@Serializable
data class HookSpec(
    val event: String,
    val script: String,
    val matcher: String = "*",
    val timeoutMs: Int = 30_000
)

/** 插槽占用声明：`anchor` + `position` 决定入口落在既有条目的前/后。 */
@Serializable
data class PluginSlotSpec(
    val slot: String,
    val anchor: String? = null,
    val position: String = SlotPosition.APPEND.id,
    /** 设置分组内的归属（仅 `settings.section` 用），如 `ai` / `plugins`。 */
    val group: String? = null
)

/**
 * 插件声明的全部能力。每一项都对应「宿主既有功能位」，插件不另建平行体系：
 * 技能进设置→技能、MCP 进设置→MCP、hooks 进既有钩子表、assets/provision 走既有释放与安装约定。
 */
@Serializable
data class PluginCapabilities(
    val assets: List<AssetMapping> = emptyList(),
    val provision: ProvisionSpec? = null,
    val mcp: List<McpSpec> = emptyList(),
    val hooks: List<HookSpec> = emptyList(),
    /** 插件目录内的技能目录相对路径，如 `skills`。 */
    val skills: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
    val teams: List<String> = emptyList(),
    val scripts: List<String> = emptyList(),
    val prompts: List<String> = emptyList()
)

/**
 * 插件清单（`plugin.json`）。
 *
 * [apiVersion] 是宿主 API 版本，宿主按它判兼容：不兼容直接在安装前拒绝，避免装上一个跑不起来的插件。
 * [disclaimer] 是随包免责文本，安装时必须展示并留痕（`PluginState.consentAt`）。
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int = 1,
    val author: String? = null,
    val license: String? = null,
    val homepage: String? = null,
    val icon: String? = null,
    val description: String = "",
    val disclaimer: String? = null,
    val permissions: List<String> = emptyList(),
    val capabilities: PluginCapabilities = PluginCapabilities(),
    val slots: List<PluginSlotSpec> = emptyList()
) {
    /** 解析后的权限集合；清单校验已保证每个名字可识别。 */
    val grantedPermissions: Set<PluginPermission>
        get() = permissions.mapNotNull(PluginPermission::fromId).toSet()
}
