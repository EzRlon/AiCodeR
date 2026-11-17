package com.aicode.feature.settings.domain.feedback

import kotlinx.serialization.Serializable

/**
 * 自动提交到项目反馈收集页的载荷。
 *
 * 不含账号/登录信息；[installId] 是每次安装随机生成的匿名标识，仅用于去重与定位同一台设备
 * 的连续反馈。设备型号/系统版本属于排障必需信息，用户可在页面关闭「环境信息」。
 */
@Serializable
data class FeedbackPayload(
    val installId: String,
    val kind: String,
    val title: String,
    val body: String,
    val appVersion: String,
    val device: String,
    val manufacturer: String,
    val android: String,
    val abi: String,
    val createdAt: Long
)
