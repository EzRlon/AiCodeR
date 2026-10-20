---
name: editfile-no-match-verify-first
description: editFile 报错（如 NO_MATCH）时先 readFile 核对实际状态再决定重试，避免盲目重试造成重复应用
---
# editFile 报错先核对实际状态（2026-10-15 案例）

## 观察
对 `MessageContextMenuPopup.kt` 一次提交 2 个编辑（①加 `import androidx.compose.foundation.layout.Box` ②把 `entries.size * MENU_ROW_HEIGHT` 改为 `MENU_ROW_HEIGHT * entries.size`），工具返回错误「第 2 个编辑未找到 old_string」（NO_MATCH）。但随后 readFile 核验发现：**两处编辑实际都已生效**（文件状态正确、行数吻合）。

## 已知
- 工作区源码 `EditFileTool.kt` 的设计是「全部在内存应用，任一失败不写盘（全有或全无）」——按此设计不该出现"报错但已生效"。
- 但**实际运行的工具是宿主 App 里的版本**，可能与工作区源码版本不一致；根因未定位。

## 准则（待未来验证，但先遵守）
1. editFile 报错（尤其 NO_MATCH / MULTIPLE_MATCHES）时，**先 readFile 核对文件实际状态**，再决定是否重试——不要按原样盲目重试。
2. 盲目重试的典型风险：编辑其实已应用，重试会失配；对"插入型"编辑（new_string 包含 old_string 原文）重试可能造成重复插入（如重复 import 行）。
3. 若再次遇到，注意收集证据：调用前后的文件 hash/行数、报错原文，以便定位根因。