---
name: file-actions-plan
description: 文件操作增强批设计（2026-10-15 用户要求）：聊天/编译产出的文件统一支持 安装/打开、用 MT 管理器/文件管理器跳转到文件、保存到手机；含现状盘点与接入点；排队中
---

# 文件操作增强批（2026-10-15 用户要求）

## 需求原话
"以后每个编译或者会话中发出的文件相关内容，点击应该可以直接安装或者打开，以及支持使用 MT 或者已安装文件管理器直接跳转到文件地址。"

## 现状盘点（已核实代码）
- ✅ 已有：文件卡片点击 → APK 走 ApkInstaller 安装（含未知来源引导）；其他走 FileProvider + ACTION_VIEW 系统打开；文件失效自动重取（containerPath→copyToLocal）；不可授权时拷 cache 分享。（MessageAttachmentComponents.kt: openSentAttachment/openLocalFile）
- ❌ 缺失：① 用 MT 管理器/文件管理器"跳转到文件地址"；② 保存到手机共享存储（下载/AiCode/）；③ 文件卡片无长按操作菜单（现仅点击）；④ 测试包页/存储空间页没有"用 MT/保存到手机"等操作。

## 设计
### 核心工具（新 `ExternalFileActions.kt`）
- `exportToShared(context, localFile, name)` → 复制到 `下载/AiCode/<name>`（重名自动改名；targetSdk 28 legacy 存储直写；可选 MediaScanner 触达文件管理器）。
- `openWithMt(context, hostFile)`：`Intent(ACTION_VIEW).setDataAndType(file://, */*).setPackage("bin.mt.plus")`（runCatching；未安装/失败 → 回退系统 chooser；再无处理者提示）。MT 打开文件即可看到其所在目录，满足"跳转到文件地址"。
- `viewInFileManager(context, hostFile)`：先试目录（`resource/folder`）→ 回退文件本身 ACTION_VIEW。
- `isMtInstalled(context)`：Menu 中"用 MT 管理器打开"仅在其已安装时显示。

### 统一操作菜单（文件长按 / 页面操作点）
- 菜单项：安装/打开（APK=安装）；用 MT 管理器打开（仅装 MT 时）；在文件管理器中查看；保存到手机（下载/AiCode）；分享；复制路径。
- 流程：先 `resolveLocalFile`（容器→本地）→ 需要宿主可见时**自动导出共享存储**（轻提示"已保存到 下载/AiCode/…"）→ 再跳转。
- 形态：小卡片浮窗（与"长按消息 mini 浮窗"同款视觉；待会话交互批产出后对齐/复用其容器）。

### 接入点
1. 聊天文件卡片（MessageAttachmentComponents.kt）：点击=打开（现状）；长按=操作菜单。
2. 测试包页（TestBuildListScreen.kt）：点击=安装（现状）；长按=操作菜单。
3. 存储空间产物菜单（StorageSection.kt）：追加"用 MT 管理器打开""保存到手机"。
4. "以后每个编译/会话文件"由 1/2 覆盖（编译产物即以卡片/测试包形式出现）。

## 排期
依赖会话交互批的 mini 浮窗视觉/锚点方案；顺序：密钥管理批 → **本批**（如要求提前再说）。
