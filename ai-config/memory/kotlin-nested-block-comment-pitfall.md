---
name: kotlin-nested-block-comment-pitfall
description: 坑（已验证）：Kotlin 块注释支持嵌套，KDoc 里写 notes/*.md 的 /* 会吞掉后面整个类，报成一片 companion 常量 Unresolved
---
# 坑：Kotlin 块注释支持嵌套，文档里写路径通配会注释掉后面的代码

## 症状
`SystemPromptProvider.kt` 编译报 40 个错误，全是 companion 常量 Unresolved（`TAG`/`SOURCE_CACHE_LIMIT`/`SUBAGENT_BASE_FILE` 等），外加多个文件的 `resolvePrompt` 级联 Unresolved。表面看像增量编译缓存污染，清缓存、`--rerun-tasks` 都无效。

## 真因
新加的 KDoc 里写了 `<项目>/.aicode/notes/*.md`。Kotlin 块注释**支持嵌套**（与 Java/C 不同），注释文本里的 `/*` 会开启一层新的块注释，`*/` 才闭合它——于是 KDoc 的 `*/` 只关掉了内层，真正的注释一直到文件里下一个 `*/` 才结束，中间整个类被吞进注释。

编译器报的是 `548:6 Syntax error: Missing '}'` + `644:2 Syntax error: Unclosed comment`，这两个才是真信号；前面几十条 Unresolved 全是级联噪音。

## 定位方法
看 Kotlin 构建报告的**最后两条**语法错误（`build/reports/kotlin-build/app-build-*.txt`），不要被前面成片的 Unresolved 带偏。凡是「一大批 companion 常量同时 Unresolved」+ 尾部有 Unclosed comment，先怀疑注释嵌套。

自写括号平衡检查脚本（逐字符状态机）**查不出**这个问题——它把 `/** ... */` 当普通块注释处理，不会模拟嵌套语义。

## 修法
注释里的路径通配不要写 `/*`：`notes/*.md` 改述为「notes 下各 md 文件」，或写成 `notes/xxx.md`。同理避免在注释里写 `/*` 开头的 C 风格代码片段。

## 验证
改完 `:app:compileArmsoloReleaseKotlin` 通过（5m26s）。本项目大文件编辑仍须用 editFile 精确替换，不要用脚本搬移代码块。