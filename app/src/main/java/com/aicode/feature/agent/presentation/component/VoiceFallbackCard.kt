package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.voice.VoskModelManager
import compose.icons.FeatherIcons
import compose.icons.feathericons.X

/**
 * 离线语音模型引导卡片（验收反馈 F2/F7）：系统识别不可用且离线模型未就绪时出现在输入区上方。
 *
 * 「立即下载」内嵌进度（下载中显示百分比进度行，安装中显示安装文案）；失败显示原因并可重试
 * （重试走 [onDownload]，多源自动切换与断点续传由 [VoskModelManager] 负责）；就绪由宿主
 * 自动隐藏并提示（引擎由 ViewModel 自动切换到离线）。右上角 × 仅收起卡片，不打断进行中的下载。
 */
@Composable
internal fun VoiceFallbackCard(
    state: VoskModelManager.ModelState,
    failureReason: String?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.xs),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.voice_fallback_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(R.string.common_cancel),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.voice_fallback_body, VoskModelManager.MODEL_SIZE_MB),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.sm))
            when (state) {
                is VoskModelManager.ModelState.Downloading -> {
                    if (state.percent >= 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = { state.percent / 100f },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = stringResource(R.string.voice_fallback_progress, state.percent),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                VoskModelManager.ModelState.Installing -> Text(
                    text = stringResource(R.string.voice_fallback_installing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                VoskModelManager.ModelState.Failed -> {
                    Text(
                        text = stringResource(
                            R.string.voice_fallback_failed,
                            failureReason ?: stringResource(R.string.chat_voice_failed)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDownload) {
                            Text(stringResource(R.string.voice_fallback_retry))
                        }
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.voice_fallback_later))
                        }
                        TextButton(onClick = onDownload) {
                            Text(stringResource(R.string.voice_fallback_download))
                        }
                    }
                }
            }
        }
    }
}
