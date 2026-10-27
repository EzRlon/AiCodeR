package com.aicode.core.util

/**
 * 单个 shell 参数的转义：含「安全字符」之外的字符时整体包单引号，内嵌单引号用 `'\''` 关闭-转义-重开。
 *
 * 全库唯一的引号实现。此前有四份拷贝（ShellArgs / GitRepository / WorkspaceRepository / HookRunner），
 * 其中一份把内嵌单引号错写成 `'"'"'`（关闭单引号后变成双引号字面量），转义结果与预期不同——
 * 这类安全相关代码只要有一份写错就是命令注入或误执行，故收口到一处。
 *
 * 注意 `|` 是 shell 管道符，**不可**列入安全集。
 */
fun shellQuote(arg: String): String {
    if (arg.isEmpty()) return "''"
    if (arg.all { it.isLetterOrDigit() || it in SAFE_CHARS }) return arg
    return "'" + arg.replace("'", "'\\''") + "'"
}

/** 无需加引号即可安全传递的字符集。 */
private const val SAFE_CHARS = "_.@/:=+,%-"
