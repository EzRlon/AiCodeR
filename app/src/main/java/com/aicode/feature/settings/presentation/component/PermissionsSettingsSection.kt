package com.aicode.feature.settings.presentation.component

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.data.local.entity.PermissionAuditEntity
import com.aicode.feature.agent.domain.permission.PermissionAuditDecision
import com.aicode.feature.agent.domain.permission.PermissionAuditSource
import com.aicode.feature.agent.domain.permission.PermissionDecision
import com.aicode.feature.agent.domain.permission.PermissionRule
import com.aicode.feature.agent.domain.permission.PermissionRuleExplainer
import com.aicode.feature.agent.domain.permission.ToolPermissionCatalog
import com.aicode.feature.agent.domain.permission.ToolPermissionGroup
import com.aicode.feature.agent.domain.permission.ToolPermissionInfo
import com.aicode.feature.agent.domain.permission.ToolPermissionProfile
import com.aicode.feature.agent.domain.permission.ToolPermissionTier
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Moon
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Shield
import compose.icons.feathericons.XCircle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 「工具授权」二级页：与设置页其它二级页一致的 iOS 分组列表。
 * 顶部是「安全防护」开关；其下按「当前项目 / 全局」分组列出已保存的授权规则，分组可折叠收起；
 * 规则左滑删除；项目规则可「提升为全局」。
 */
@Composable
internal fun PermissionsSection(
    projectName: String?,
    projectRules: List<PermissionRule>,
    globalRules: List<PermissionRule>,
    disableSafetyInterception: Boolean,
    watchMode: Boolean,
    onToggleWatchMode: (Boolean) -> Unit,
    toolTiers: Map<String, ToolPermissionTier>,
    auditRecords: List<PermissionAuditEntity>,
    onToggleSafetyInterception: (Boolean) -> Unit,
    onDeleteProject: (PermissionRule) -> Unit,
    onPromote: (PermissionRule) -> Unit,
    onDeleteGlobal: (PermissionRule) -> Unit,
    onSetToolTier: (String, ToolPermissionTier) -> Unit,
    onApplyProfile: (String) -> Unit,
    onJumpToSession: ((String) -> Unit)? = null
) {
    // 工具规则默认折叠（22 项长列表）；其余区默认展开。
    var toolsExpanded by rememberSaveable { mutableStateOf(false) }
    var profilesExpanded by rememberSaveable { mutableStateOf(true) }
    var projectExpanded by rememberSaveable { mutableStateOf(true) }
    var globalExpanded by rememberSaveable { mutableStateOf(true) }
    var auditExpanded by rememberSaveable { mutableStateOf(false) }
    // 开启「禁用安全拦截」前先弹强制确认框（确认按钮 5 秒倒计时）；关闭无需确认。
    var showSafetyConfirm by remember { mutableStateOf(false) }
    val tipContext = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.perm_safety_group))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Shield,
                title = stringResource(R.string.perm_disable_safety_interception),
                subtitle = stringResource(R.string.perm_disable_safety_interception_desc),
                trailing = {
                    AppSwitch(
                        checked = disableSafetyInterception,
                        onCheckedChange = { enabled ->
                            if (enabled) showSafetyConfirm = true
                            else onToggleSafetyInterception(false)
                        }
                    )
                }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.perm_watch_group))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Moon,
                title = stringResource(R.string.perm_watch_title),
                subtitle = stringResource(R.string.perm_watch_desc),
                trailing = {
                    AppSwitch(
                        checked = watchMode,
                        onCheckedChange = onToggleWatchMode
                    )
                }
            )
        }

        CollapsibleGroupHeader(
            text = stringResource(R.string.perm_tools_group),
            expanded = toolsExpanded,
            onToggle = { toolsExpanded = !toolsExpanded }
        )
        AnimatedVisibility(visible = toolsExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ToolPermissionGroup.entries.forEach { group ->
                    ToolGroupLabel(text = stringResource(group.labelRes))
                    SettingsGroup {
                        val items = ToolPermissionCatalog.all.filter { it.group == group }
                        items.forEachIndexed { index, info ->
                            if (index > 0) SettingsDivider()
                            ToolTierRow(
                                info = info,
                                tier = toolTiers[info.toolName] ?: info.defaultTier,
                                onSelect = { onSetToolTier(info.toolName, it) }
                            )
                        }
                    }
                }
            }
        }

        CollapsibleGroupHeader(
            text = stringResource(R.string.perm_profiles_group),
            expanded = profilesExpanded,
            onToggle = { profilesExpanded = !profilesExpanded }
        )
        AnimatedVisibility(visible = profilesExpanded) {
            SettingsGroup {
                ToolPermissionCatalog.profiles.forEachIndexed { index, profile ->
                    if (index > 0) SettingsDivider()
                    ProfileRow(
                        profile = profile,
                        onApply = {
                            onApplyProfile(profile.id)
                            Toast.makeText(
                                tipContext,
                                tipContext.getString(
                                    R.string.perm_profile_applied,
                                    tipContext.getString(profile.labelRes)
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        }

        CollapsibleGroupHeader(
            text = if (projectName != null) {
                stringResource(R.string.perm_current_project, projectName)
            } else {
                stringResource(R.string.perm_current_project_none)
            },
            expanded = projectExpanded,
            onToggle = { projectExpanded = !projectExpanded }
        )
        AnimatedVisibility(visible = projectExpanded) {
            SettingsGroup {
                if (projectRules.isEmpty()) {
                    RuleEmptyHint(stringResource(R.string.perm_no_project_rules))
                } else {
                    projectRules.forEachIndexed { index, rule ->
                        if (index > 0) SettingsDivider()
                        RuleRow(
                            rule = rule,
                            onDelete = { onDeleteProject(rule) },
                            onPromote = { onPromote(rule) }
                        )
                    }
                }
            }
        }

        CollapsibleGroupHeader(
            text = stringResource(R.string.perm_global),
            expanded = globalExpanded,
            onToggle = { globalExpanded = !globalExpanded }
        )
        AnimatedVisibility(visible = globalExpanded) {
            SettingsGroup {
                if (globalRules.isEmpty()) {
                    RuleEmptyHint(stringResource(R.string.perm_no_global_rules))
                } else {
                    globalRules.forEachIndexed { index, rule ->
                        if (index > 0) SettingsDivider()
                        RuleRow(
                            rule = rule,
                            onDelete = { onDeleteGlobal(rule) },
                            onPromote = null
                        )
                    }
                }
            }
        }

        CollapsibleGroupHeader(
            text = stringResource(R.string.perm_audit_group),
            expanded = auditExpanded,
            onToggle = { auditExpanded = !auditExpanded }
        )
        AnimatedVisibility(visible = auditExpanded) {
            SettingsGroup {
                if (auditRecords.isEmpty()) {
                    RuleEmptyHint(stringResource(R.string.perm_audit_empty))
                } else {
                    auditRecords.forEachIndexed { index, record ->
                        if (index > 0) SettingsDivider()
                        AuditRow(
                            record = record,
                            onJump = onJumpToSession
                        )
                    }
                }
            }
        }

        FooterNote(stringResource(R.string.perm_rules_short))
    }

    if (showSafetyConfirm) {
        SafetyConfirmDialog(
            onConfirm = {
                onToggleSafetyInterception(true)
                showSafetyConfirm = false
            },
            onDismiss = { showSafetyConfirm = false }
        )
    }
}

/** 「禁用安全拦截」确认框的倒计时秒数。 */
private const val SAFETY_CONFIRM_COUNTDOWN_SECONDS = 5

/** 开启「禁用安全拦截」前的强制确认框：确认按钮带 5 秒倒计时，倒计时结束前不可点。 */
@Composable
private fun SafetyConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var secondsLeft by remember { mutableIntStateOf(SAFETY_CONFIRM_COUNTDOWN_SECONDS) }
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.perm_safety_confirm_title)) },
        text = { Text(stringResource(R.string.perm_safety_confirm_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = secondsLeft == 0
            ) {
                Text(
                    if (secondsLeft > 0) {
                        stringResource(R.string.perm_safety_confirm_action, secondsLeft)
                    } else {
                        stringResource(R.string.perm_safety_confirm_action_ready)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * 单条规则行：紧凑布局（同设置页行），判定图标（允许对勾 / 禁止叉）+ 工具名（主）+ 匹配范围/判定（次），
 * 右侧「提升为全局」图标 (仅项目规则)；左滑露出删除按钮。
 */
@Composable
internal fun RuleRow(
    rule: PermissionRule,
    onDelete: () -> Unit,
    onPromote: (() -> Unit)?
) {
    val allowed = rule.decision == PermissionDecision.ALLOW
    val iconTint = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    SwipeToDeleteRow(onDelete = onDelete) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.xs, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (allowed) FeatherIcons.CheckCircle else FeatherIcons.XCircle,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = PermissionRuleExplainer.explain(LocalContext.current, rule),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = (if (rule.pattern == PermissionRule.WHOLE_TOOL) {
                        stringResource(R.string.perm_entire_tool)
                    } else {
                        rule.pattern
                    }) + " · " + if (allowed) {
                        stringResource(R.string.common_allow)
                    } else {
                        stringResource(R.string.perm_deny)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (onPromote != null) {
                IconButton(onClick = onPromote) {
                    Icon(
                        imageVector = FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.perm_promote_to_global),
                        tint = iconTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** 工具规则区的分组小标题。 */
@Composable
private fun ToolGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.md, top = Spacing.xs, bottom = 2.dp)
    )
}

/** 工具行：中文名 + 一句用途说明 + 右侧三档选择器。 */
@Composable
private fun ToolTierRow(
    info: ToolPermissionInfo,
    tier: ToolPermissionTier,
    onSelect: (ToolPermissionTier) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.sm, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(info.labelRes),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(info.descRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        ToolTierSelector(tier = tier, onSelect = onSelect)
    }
}

/** 三档选择器（允许 / 询问 / 禁止）：紧凑三格，选中格以语义色微底高亮。 */
@Composable
private fun ToolTierSelector(
    tier: ToolPermissionTier,
    onSelect: (ToolPermissionTier) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(2.dp)
    ) {
        TierOption(
            text = stringResource(R.string.perm_tier_auto_short),
            selected = tier == ToolPermissionTier.AUTO,
            accent = MaterialTheme.semanticColors.success,
            onClick = { onSelect(ToolPermissionTier.AUTO) }
        )
        TierOption(
            text = stringResource(R.string.perm_tier_ask_short),
            selected = tier == ToolPermissionTier.ASK,
            accent = MaterialTheme.colorScheme.primary,
            onClick = { onSelect(ToolPermissionTier.ASK) }
        )
        TierOption(
            text = stringResource(R.string.perm_tier_deny_short),
            selected = tier == ToolPermissionTier.DENY,
            accent = MaterialTheme.colorScheme.error,
            onClick = { onSelect(ToolPermissionTier.DENY) }
        )
    }
}

@Composable
private fun TierOption(
    text: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** 授权档案行：名称 + 说明 + 右侧「应用」按钮。 */
@Composable
private fun ProfileRow(
    profile: ToolPermissionProfile,
    onApply: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.xs, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(profile.labelRes),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(profile.descRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        TextButton(
            onClick = onApply,
            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
        ) {
            Text(stringResource(R.string.perm_profile_apply), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 授权记录行：中文摘要 + 决定/来源/时间；有会话时可点击跳转。 */
@Composable
private fun AuditRow(
    record: PermissionAuditEntity,
    onJump: ((String) -> Unit)?
) {
    val decision = PermissionAuditDecision.fromKey(record.decision)
    val source = PermissionAuditSource.fromKey(record.source)
    val icon = when (decision) {
        PermissionAuditDecision.ALLOW_ONCE, PermissionAuditDecision.ALLOW_ALWAYS, PermissionAuditDecision.ALLOW_WATCH -> FeatherIcons.CheckCircle
        PermissionAuditDecision.DENY_USER -> FeatherIcons.XCircle
        else -> FeatherIcons.Shield
    }
    val iconTint = when (decision) {
        PermissionAuditDecision.ALLOW_ONCE, PermissionAuditDecision.ALLOW_ALWAYS, PermissionAuditDecision.ALLOW_WATCH -> MaterialTheme.semanticColors.success
        PermissionAuditDecision.DENY_USER, PermissionAuditDecision.DENY_SYSTEM, PermissionAuditDecision.WATCH_TIMEOUT -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val decisionLabel = when (decision) {
        PermissionAuditDecision.ALLOW_ONCE -> stringResource(R.string.perm_audit_decision_allow_once)
        PermissionAuditDecision.ALLOW_ALWAYS -> stringResource(R.string.perm_audit_decision_allow_always)
        PermissionAuditDecision.DENY_USER -> stringResource(R.string.perm_audit_decision_deny_user)
        PermissionAuditDecision.DENY_SYSTEM -> stringResource(R.string.perm_audit_decision_deny_system)
        PermissionAuditDecision.ALLOW_WATCH -> stringResource(R.string.perm_audit_decision_allow_watch)
        PermissionAuditDecision.WATCH_TIMEOUT -> stringResource(R.string.perm_audit_decision_watch_timeout)
        null -> record.decision
    }
    val sourceLabel = when (source) {
        PermissionAuditSource.POPUP -> stringResource(R.string.perm_audit_source_popup)
        PermissionAuditSource.NOTIFICATION -> stringResource(R.string.perm_audit_source_notification)
        PermissionAuditSource.SYSTEM -> stringResource(R.string.perm_audit_source_system)
        PermissionAuditSource.FLOAT_BALL -> stringResource(R.string.perm_audit_source_float_ball)
        null -> record.source
    }
    val canJump = onJump != null && record.sessionId.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canJump) { onJump?.invoke(record.sessionId) }
            .padding(start = Spacing.lg, end = Spacing.md, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$decisionLabel · $sourceLabel · ${formatAuditTime(record.timestamp)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (canJump) {
            Icon(
                FeatherIcons.ChevronRight,
                contentDescription = stringResource(R.string.perm_audit_jump),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 授权记录时间：当天只显时间，今年显月日，更早显完整日期。 */
private fun formatAuditTime(timestamp: Long): String {
    val date = Date(timestamp)
    val time = Calendar.getInstance().apply { time = date }
    val now = Calendar.getInstance()
    val sameYear = time.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val sameDay = sameYear && time.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameDay -> "HH:mm"
        sameYear -> "MM-dd HH:mm"
        else -> "yyyy-MM-dd"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
}

/** 分组内空状态：一行灰字，与行内容对齐。 */
@Composable
private fun RuleEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
    )
}

/** 页脚说明：灰色小字。 */
@Composable
private fun FooterNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.md, top = Spacing.xs)
    )
}
