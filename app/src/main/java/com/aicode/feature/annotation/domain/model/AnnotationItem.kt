package com.aicode.feature.annotation.domain.model

/** 标注类型：四种反馈意图，决定标注颜色与汇总文案。 */
enum class AnnotationType {
    /** 这里有 bug / 问题。 */
    BUG,

    /** 这里要改（期望的行为/样式）。 */
    TO_CHANGE,

    /** 这里不要这样（错误示范）。 */
    DONT,

    /** 这里可以优化。 */
    OPTIMIZE
}

/** 账本状态机：待确认 → 已发AI → 修复中 → 待复验 → 关闭（可回退到待确认前重新编辑）。 */
enum class AnnotationStatus {
    PENDING,
    SENT,
    FIXING,
    REVIEW,
    CLOSED
}

/** 一条截图标注账目：截图落盘在工作区，标注以归一化坐标 JSON 存储，随时可重绘。 */
data class AnnotationItem(
    val id: String,
    val type: AnnotationType,
    val status: AnnotationStatus,
    /** 截图在工作区的容器路径（如 ~/workspace/.aicode/annotations/xxx.png）。 */
    val imagePath: String,
    /** 标注图形 JSON（归一化坐标），格式见 AnnotationMarksCodec。 */
    val marksJson: String,
    /** 用户补充说明（可空）。 */
    val note: String,
    val createdAt: Long,
    val updatedAt: Long
)
