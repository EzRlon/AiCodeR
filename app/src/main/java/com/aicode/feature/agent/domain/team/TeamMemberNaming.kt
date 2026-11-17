package com.aicode.feature.agent.domain.team

import com.aicode.core.util.containsCjk
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.store.data.StoreTranslation

/**
 * 专家团成员的显示命名：把成员标识（如 `backend`）翻成用户看得懂的名字。
 *
 * 成员在派发与落库时用的是定义名（`agents/<name>.md` 的 name，多为英文标识）。直接把这串
 * 标识摆给用户看，既读不懂也分不清谁是谁；而子代理定义里的 [AgentDefinition.description]
 * 本来就是中文（如「后端/逻辑实现者：独立完成业务逻辑…」），取它的首段即可当名字用。
 *
 * 命名优先级（前者命中即用）：
 * 1. 商店译文 [StoreTranslation.nameZh]——商店装的成员自带中文名，**新装团队零维护**；
 * 2. 内置名表 [BUILTIN_NAMES]——内置 8 名角色的官方中文名，比描述首段更简短统一（`PM` → 产品经理）；
 * 3. 子代理定义的 description 首段（截断到第一个分隔符，如「后端/逻辑实现者」）；
 * 4. 英文标识原样兜底。
 *
 * 「商店新增团队也保持同一纪律」由优先级链天然保证：商店团队走第 1 级（随翻译自动获得），
 * 它引用内置成员时走第 2 级，引用自定义成员时走第 3 级——任何新团队都不需要额外配置。
 *
 * 本对象只做「标识 → 名字」的纯映射，不持有状态，便于在 UI 各处（选择面板、消息行、侧栏、
 * 成员条）统一调用，保证同一成员在任何位置显示同一个名字。
 */
object TeamMemberNaming {

    /** description 里常见的分隔符：取首段作为名字（「后端/逻辑实现者：独立完成…」→「后端/逻辑实现者」）。 */
    private val NAME_SEPARATORS = charArrayOf('：', ':', '，', ',', '。', '（', '(')

    /**
     * 内置角色名的官方中文名（key 为小写标识）。与 `docs-site/docs/guide/subagent.md` 的角色表口径一致：
     * 这些角色的标识多为缩写（`PM` / `qa`），描述首段又偏长，直接展示不友好，故显式给名。
     * 只覆盖内置 8 名；商店与自定义成员不走这里（分别由译文与描述首段覆盖）。
     */
    private val BUILTIN_NAMES: Map<String, String> = mapOf(
        "pm" to "产品经理",
        "product-manager" to "产品经理",
        "architect" to "架构师",
        "frontend" to "前端",
        "backend" to "后端",
        "qa" to "质检",
        "ui-designer" to "设计师",
        "explore" to "探索",
        "optimizer" to "优化师"
    )

    /**
     * 解析一个成员的显示名。
     *
     * @param memberId 成员标识（`AgentDefinition.name`，如 `backend`）
     * @param definition 该成员的定义（取不到时为 null，此时退回标识）
     * @param translations 商店译文表（key 见 [StoreTranslationStore] 体系）；可空
     */
    fun displayName(
        memberId: String,
        definition: AgentDefinition? = null,
        translations: Map<String, StoreTranslation> = emptyMap()
    ): String {
        val id = memberId.trim()
        if (id.isEmpty()) return id

        // 1. 商店译文：成员若来自商店装的专家团，译文表里可直接查到中文名。
        translations.values.firstOrNull { it.sourceName.equals(id, ignoreCase = true) }
            ?.nameZh?.takeIf { it.isNotBlank() && it.containsCjk() }
            ?.let { return it }

        // 2. 内置角色名表：缩写标识（PM/qa）看这里，比描述首段更短更统一。
        BUILTIN_NAMES[id.lowercase()]?.let { return it }

        // 3. 定义的 description 首段：自定义成员走这条（description 通常是中文）。
        definition?.description?.let { desc ->
            shortName(desc)?.let { return it }
        }

        // 4. 英文标识兜底。
        return id
    }

    /**
     * 从 description 取「名字段」：截到第一个分隔符，并限制长度，避免整段描述挤进行首。
     * 返回 null 表示取不出合适短名（调用方继续走下一优先级）。
     */
    internal fun shortName(description: String): String? {
        val trimmed = description.trim()
        if (trimmed.isEmpty()) return null
        val cut = trimmed.indexOfFirst { it in NAME_SEPARATORS }
        val head = (if (cut > 0) trimmed.substring(0, cut) else trimmed).trim()
        // 首段过长（描述里没有分隔符）时不硬当名字用，交给标识兜底，避免行首一大段文字。
        return head.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
    }

    /** 名字段长度上限：超过则认为该 description 没有「名字」，退回标识。 */
    private const val MAX_NAME_LENGTH = 12
}
