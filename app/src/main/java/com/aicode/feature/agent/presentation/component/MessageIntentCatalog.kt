package com.aicode.feature.agent.presentation.component

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.aicode.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.BarChart
import compose.icons.feathericons.Book
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Globe
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Package
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Search
import compose.icons.feathericons.Zap

/**
 * 消息类型目录：「消息类型」v1 的默认 13 项（编程 7 + 通用 6）+ 未分类（null）。
 * key 是持久化标识（落库、队列、模型瞬态行共用），label/desc/icon 仅随界面语言与主题渲染。
 */
internal object MessageIntentCatalog {

    /** 编程方向 7 项。 */
    val coding: List<MessageIntent> = listOf(
        MessageIntent("bug_fix", R.string.intent_bug_fix, R.string.intent_bug_fix_desc, FeatherIcons.AlertCircle),
        MessageIntent("new_feature", R.string.intent_new_feature, R.string.intent_new_feature_desc, FeatherIcons.Plus),
        MessageIntent("plan", R.string.intent_plan, R.string.intent_plan_desc, FeatherIcons.Clipboard),
        MessageIntent("refactor", R.string.intent_refactor, R.string.intent_refactor_desc, FeatherIcons.RefreshCw),
        MessageIntent("testing", R.string.intent_testing, R.string.intent_testing_desc, FeatherIcons.CheckCircle),
        MessageIntent("build_deploy", R.string.intent_build_deploy, R.string.intent_build_deploy_desc, FeatherIcons.Package),
        MessageIntent("feedback", R.string.intent_feedback, R.string.intent_feedback_desc, FeatherIcons.MessageSquare)
    )

    /** 通用方向 6 项。 */
    val general: List<MessageIntent> = listOf(
        MessageIntent("research", R.string.intent_research, R.string.intent_research_desc, FeatherIcons.Search),
        MessageIntent("writing", R.string.intent_writing, R.string.intent_writing_desc, FeatherIcons.Edit2),
        MessageIntent("translation", R.string.intent_translation, R.string.intent_translation_desc, FeatherIcons.Globe),
        MessageIntent("data_analysis", R.string.intent_data_analysis, R.string.intent_data_analysis_desc, FeatherIcons.BarChart),
        MessageIntent("learning", R.string.intent_learning, R.string.intent_learning_desc, FeatherIcons.Book),
        MessageIntent("automation", R.string.intent_automation, R.string.intent_automation_desc, FeatherIcons.Zap)
    )

    private val byKey = (coding + general).associateBy { it.key }

    /** 按持久化键查类型；null 或未知键返回 null（视为未分类，容错未来自定义/历史数据）。 */
    fun find(key: String?): MessageIntent? = key?.let { byKey[it] }

    /**
     * 模型输入的消息类型瞬态说明（「（本条消息类型：X）」）；未分类返回空串。
     * 标签取当前界面语言，与提示词片段中对类型的描述无关——模型按语义理解即可。
     */
    fun modelNote(context: Context, key: String?): String =
        find(key)?.let {
            "\n\n" + context.getString(R.string.intent_model_note, context.getString(it.labelRes))
        }.orEmpty()
}

/** 单个消息类型的展示元数据。 */
@Immutable
internal data class MessageIntent(
    val key: String,
    @param:StringRes val labelRes: Int,
    @param:StringRes val descRes: Int,
    val icon: ImageVector
)
