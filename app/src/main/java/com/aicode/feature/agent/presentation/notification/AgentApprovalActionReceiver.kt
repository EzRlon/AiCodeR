package com.aicode.feature.agent.presentation.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aicode.feature.agent.domain.permission.PermissionAuditSource
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.presentation.notification.AgentApprovalNotificationManager.ApprovalKind
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 审批通知上按钮的接收器：把用户在通知上的选择直接回传给挂起的审批请求，无需回到 App。
 *
 * 仅在 [AgentApprovalNotificationManager] 判定当前只有一个可批准项时才会挂出按钮，
 * 但按钮仍带请求 id：通知会被新请求更新，不带 id 就只能按「最早待决」解决，
 * 用户点旧通知会误批后来的请求。
 */
@AndroidEntryPoint
class AgentApprovalActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var toolPermissionManager: ToolPermissionManager

    @Inject
    lateinit var planApprovalManager: PlanApprovalManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val kind = intent.getStringExtra(EXTRA_KIND)?.let { ApprovalKind.valueOf(it) } ?: return
        val choice = intent.getStringExtra(EXTRA_CHOICE) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf { it.isNotBlank() }
        when (kind) {
            ApprovalKind.PERMISSION -> {
                if (requestId != null) {
                    toolPermissionManager.resolve(requestId, PermissionChoice.valueOf(choice), PermissionAuditSource.NOTIFICATION)
                } else {
                    toolPermissionManager.resolveEarliest(PermissionChoice.valueOf(choice))
                }
            }
            ApprovalKind.PLAN -> {
                if (requestId != null) {
                    planApprovalManager.resolve(requestId, PlanApprovalChoice.valueOf(choice))
                } else {
                    planApprovalManager.resolve(PlanApprovalChoice.valueOf(choice))
                }
            }
            ApprovalKind.QUESTION -> Unit
        }
    }

    companion object {
        const val ACTION = "com.aicode.action.AGENT_APPROVAL"
        const val EXTRA_KIND = "extra_kind"
        const val EXTRA_CHOICE = "extra_choice"
        const val EXTRA_REQUEST_ID = "extra_request_id"
    }
}
