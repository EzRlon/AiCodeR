package com.aicode.feature.store.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.SegmentedTabs
import com.aicode.feature.store.data.StoreItem
import com.aicode.feature.store.data.StoreKind
import com.aicode.feature.store.data.StoreTranslation
import com.aicode.feature.store.data.storeTranslationKey
import com.aicode.feature.store.presentation.StoreUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Download
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Search

/**
 * 在线商店（「设置 → 在线商店」的内容组件，无自带 Scaffold）：
 * 技能 / MCP 双 Tab、搜索（技能）、分页加载、安装（安装 ≠ 启用）。
 */
@Composable
internal fun StoreScreen(
    state: StoreUiState,
    onTab: (StoreKind) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onInstall: (StoreItem) -> Unit,
    onConsumeMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 一次性提示（安装结果）→ Toast
    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            onConsumeMessage()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Tab：技能 / MCP
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SegmentedTabs(
                selected = if (state.tab == StoreKind.SKILL) 0 else 1,
                labels = listOf(
                    stringResource(R.string.store_tab_skills),
                    stringResource(R.string.store_tab_mcp)
                ),
                onSelect = { onTab(if (it == 0) StoreKind.SKILL else StoreKind.MCP) }
            )
        }

        // 搜索行（技能 tab 提供；MCP 不提供搜索）
        if (state.tab == StoreKind.SKILL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    AppTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        label = stringResource(R.string.store_search_hint),
                        singleLine = true
                    )
                }
                IconButton(onClick = onSearch) {
                    Icon(
                        FeatherIcons.Search,
                        contentDescription = stringResource(R.string.store_search_action),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (state.translating) {
            Text(
                text = stringResource(R.string.store_translating),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.xs)
            )
        }

        Spacer(Modifier.size(Spacing.xs))

        when {
            state.loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }

            state.error != null -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { onSearch(); }) {
                    Text(stringResource(R.string.store_retry))
                }
            }

            state.items.isEmpty() -> Text(
                text = stringResource(R.string.store_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 24.dp)
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    bottom = Spacing.xl
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.items, key = { "${it.kind}:${it.id}" }) { item ->
                    StoreItemCard(
                        item = item,
                        translation = state.translations[item.storeTranslationKey()],
                        installed = item.id in state.installedIds,
                        installing = state.installingId == item.id,
                        installBusy = state.installingId != null,
                        onInstall = { onInstall(item) }
                    )
                }
                if (state.hasMore) {
                    item(key = "load-more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.sm),
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.loadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(onClick = onLoadMore) {
                                    Text(stringResource(R.string.store_load_more))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 单条商店卡片：名称/作者/版本/描述 + 安装按钮 + 来源链接。 */
@Composable
private fun StoreItemCard(
    item: StoreItem,
    translation: StoreTranslation?,
    installed: Boolean,
    installing: Boolean,
    installBusy: Boolean,
    onInstall: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.md, top = 12.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val nameZh = translation?.nameZh.orEmpty()
                Text(
                    text = if (nameZh.isBlank()) item.name else "${item.name}（${nameZh}）",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.version.isNotBlank()) {
                    Text(
                        text = "v${item.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val subtitle = listOfNotNull(
                item.author.takeIf { it.isNotBlank() },
                item.category.takeIf { it.isNotBlank() },
                item.transportType.takeIf { it.isNotBlank() && item.kind == StoreKind.MCP }
            ).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val descriptionZh = translation?.descriptionZh.orEmpty()
            if (descriptionZh.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = descriptionZh,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.description.isNotBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (item.description.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.detailUrl.isNotBlank()) {
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(item.detailUrl))
                                )
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = Spacing.xs, vertical = 0.dp
                        )
                    ) {
                        Icon(FeatherIcons.ExternalLink, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.store_open_source), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.weight(1f))
                when {
                    installed -> Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = stringResource(R.string.store_installed),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    item.stdinUnsupported() -> Text(
                        text = stringResource(R.string.store_mcp_stdio_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    else -> TextButton(onClick = onInstall, enabled = !installBusy) {
                        if (installing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.store_installing))
                        } else {
                            Icon(FeatherIcons.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.store_install))
                        }
                    }
                }
            }
        }
    }
}

/** MCP 条目仅提供本地包（无远程地址）时不支持一键安装。 */
private fun StoreItem.stdinUnsupported(): Boolean = kind == StoreKind.MCP && stdioOnly
