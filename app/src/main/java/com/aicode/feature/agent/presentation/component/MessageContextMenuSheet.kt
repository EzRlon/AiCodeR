package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.hasVisibleContent
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.HelpCircle
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Search
import compose.icons.feathericons.Share2
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Type

/**
 * 长按消息内容弹出的「消息操作」菜单：复制 / 引用追问 / 解释这段 / 在工作区中查找 / 分享 / 选择文本，
 * 用户消息另带编辑 / 删除。替代系统文本选择菜单；智能类操作以**整条消息**为粒度
 * （系统文本选择不向应用回调选中内容，无法对任意选区做智能操作，故以「选择文本」入口兜底局部摘取）。
 *
 * 菜单只负责收集意图，具体动作（注入输入框 / 剪贴板 / 分享）由宿主 [AIChatPanel] 执行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageContextMenuSheet(
    message: AgentUIMessage,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onExplain: () -> Unit,
    onSearchWorkspace: () -> Unit,
    onShare: () -> Unit,
    onSelectText: () -> Unit,
    onEditClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.chat_message_actions_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
            )

            Spacer(Modifier.height(Spacing.xs))

            if (message.content.hasVisibleContent()) {
                MessageActionItem(
                    icon = FeatherIcons.Copy,
                    title = stringResource(R.string.chat_action_copy)
                ) {
                    onDismiss()
                    onCopy()
                }
                MessageActionItem(
                    icon = FeatherIcons.MessageSquare,
                    title = stringResource(R.string.chat_action_quote)
                ) {
                    onDismiss()
                    onQuote()
                }
                // 解释 / 工作区查找：仅助手回复有意义
                if (message.role == MessageRole.ASSISTANT) {
                    MessageActionItem(
                        icon = FeatherIcons.HelpCircle,
                        title = stringResource(R.string.chat_action_explain)
                    ) {
                        onDismiss()
                        onExplain()
                    }
                    MessageActionItem(
                        icon = FeatherIcons.Search,
                        title = stringResource(R.string.chat_action_search_workspace)
                    ) {
                        onDismiss()
                        onSearchWorkspace()
                    }
                }
                MessageActionItem(
                    icon = FeatherIcons.Share2,
                    title = stringResource(R.string.chat_action_share)
                ) {
                    onDismiss()
                    onShare()
                }
                MessageActionItem(
                    icon = FeatherIcons.Type,
                    title = stringResource(R.string.chat_action_select_text)
                ) {
                    onDismiss()
                    onSelectText()
                }
            }

            // 用户消息的编辑 / 删除
            onEditClick?.let { edit ->
                MessageActionItem(
                    icon = FeatherIcons.Edit2,
                    title = stringResource(R.string.chat_action_edit)
                ) {
                    onDismiss()
                    edit()
                }
            }
            onDeleteClick?.let { delete ->
                MessageActionItem(
                    icon = FeatherIcons.Trash2,
                    title = stringResource(R.string.chat_action_delete),
                    isDestructive = true
                ) {
                    onDismiss()
                    delete()
                }
            }
        }
    }
}
