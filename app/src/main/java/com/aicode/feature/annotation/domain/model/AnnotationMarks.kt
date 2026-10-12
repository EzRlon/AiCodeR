package com.aicode.feature.annotation.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 标注图形：坐标全部归一化到 [0,1]（相对截图图片区域），与图片实际像素尺寸解耦，
 * 任意分辨率下都能原样重绘或烧制。
 */
@Serializable
data class AnnotationMark(
    /** 标注类型（[AnnotationType] 的 name），决定颜色。 */
    val type: String,
    /** 图形种类：RECT（矩形框）或 STROKE（自由笔迹）。 */
    val kind: String,
    /** RECT 的左上角与宽高（归一化）。 */
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 0f,
    val h: Float = 0f,
    /** STROKE 的点集（归一化）。 */
    val points: List<AnnotationPoint> = emptyList()
) {
    companion object {
        const val KIND_RECT = "RECT"
        const val KIND_STROKE = "STROKE"
    }
}

@Serializable
data class AnnotationPoint(val x: Float, val y: Float)

/** 标注图形的 JSON 编解码（落库 marksJson 字段）。 */
object AnnotationMarksCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(marks: List<AnnotationMark>): String =
        runCatching { json.encodeToString(marks) }.getOrDefault("[]")

    fun decode(raw: String): List<AnnotationMark> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<AnnotationMark>>(raw) }.getOrDefault(emptyList())
    }
}
