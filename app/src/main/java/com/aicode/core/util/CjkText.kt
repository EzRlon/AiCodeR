package com.aicode.core.util

/** 是否包含中日韩统一表意文字（含扩展 A）。用于判断一段文案是否已经是中文。 */
private val CJK_REGEX = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF]")

fun String.containsCjk(): Boolean = CJK_REGEX.containsMatchIn(this)
