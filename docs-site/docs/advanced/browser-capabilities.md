# 浏览器功能索引

本页给出内置浏览器**每个功能的快速映射**：用户入口（界面上哪里操作）、AI 调用方式（`browser` 工具 action）、代码位置（文件名与函数名）。适合两种场景：想给浏览器加功能时先看这里；AI 想直接调用某个能力时照此查找。

代码内可通过搜索 `[浏览器功能:` 标记快速定位各功能实现块。

## 实现总览（四层）

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 工具接口层 | `app/src/main/java/com/aicode/feature/agent/domain/tool/browser/BrowserTool.kt` | AI 的 `browser` 工具：action 分发与参数校验 |
| 会话层 | 同目录 `BrowserManager.kt` | WebView 生命周期、标签、导航、JS 注入、截图、书签/历史/下载 |
| JS 桥 | `BrowserManager.kt` 内 `BrowserJsBridge`（注入名 `__browserBridge__`） | 页面挂起的 confirm/prompt 回传与 JS 交互 |
| 数据库 | 同目录 `BrowserLinkStore.kt` | 书签/浏览历史的本地持久化（SharedPreferences + JSON） |
| UI 层 | `app/src/main/java/com/aicode/feature/browser/presentation/BrowserScreen.kt` | 地址栏、底栏、菜单、书签/历史面板 |

## 页面浏览

| 功能 | 用户入口 | AI 调用 | 代码位置 |
| --- | --- | --- | --- |
| 地址栏导航 | 输入网址回车 | `navigate` | `BrowserManager.navigate`；不像网址时由 `looksLikeHost` 判定后走 Bing 搜索 |
| 本地文件预览 | 地址栏输入 `file://` 或容器路径 | `navigate`（容器路径） | `BrowserManager.isLocalPathInput` / `resolveLocalFileUrl` |
| 前进 / 后退 / 刷新 | 底栏按钮、地址栏刷新键 | `back` / `forward` / `reload` | `BrowserManager.goBack` / `goForward` / `reload` |
| 多标签 | 底栏新建 / 标签管理 | `newTab` / `closeTab` / `selectTab` / `listTabs` | `BrowserManager`（`TabHolder` 集合） |
| 加载进度条 | 地址栏下方 | — | `BrowserScreen`（加载状态 StateFlow） |

## 阅读辅助

| 功能 | 用户入口 | AI 调用 | 代码位置 |
| --- | --- | --- | --- |
| 夜间模式 | 菜单开关 | —（仅手动） | `BrowserManager.toggleNightMode` / `applyNightMode`（原生深色 + 蒙版压暗双轨） |
| 开发者工具（Eruda） | 菜单开关 | — | `BrowserManager.injectEruda` / `loadErudaScript`；开关时同步启用 `WebContentsDebugging` |
| 浏览器标识（UA） | 菜单选择预设 | — | `BrowserManager.setUserAgent`（`BrowserUserAgentPreset`） |
| 控制台日志 | 开发者工具面板 | `console` | `BrowserManager.configureWebView` 内 `onConsoleMessage` 收集；读取见 `getConsoleLogs` |

## 内容操作

| 功能 | 用户入口 | AI 调用 | 代码位置 |
| --- | --- | --- | --- |
| 分享 | 菜单「分享」 | — | `BrowserScreen` 菜单回调（`ACTION_SEND`） |
| 复制链接 | 菜单「复制链接」 | — | `BrowserScreen` 菜单回调（`ClipEntry`） |
| 书签 | 菜单「添加书签」/「书签」列表 | — | `BrowserManager.addBookmark` / `removeBookmark` / `isBookmarked`；列表 `BrowserLinksSheet`；持久化 `BrowserLinkStore` |
| 历史记录 | 菜单「历史记录」 | — | `BrowserManager.recordHistory`（去重置顶、上限 200）/ `clearHistory`；列表 `BrowserLinksSheet` |
| 下载 | 网页内触发下载链接 | — | `BrowserManager.configureWebView` 内 `setDownloadListener` → `enqueueSystemDownload`（交给系统下载管理器） |

## 视频与声音

| 功能 | 用户入口 | AI 调用 | 代码位置 |
| --- | --- | --- | --- |
| 页内声音 | 自动（无需先点页面） | — | `configureWebView`：`mediaPlaybackRequiresUserGesture = false` |
| 全屏视频 | 网页播放器全屏按钮；返回键退出 | — | `onShowCustomView` / `onHideCustomView` / `hideFullscreenVideo` / `exitFullscreenVideo`；`BrowserScreen` 的 `BackHandler` |

## AI 自动化（browser 工具）

工具共 15 个 action，分发在 `BrowserTool.kt`；以下为执行端（均在 `BrowserManager`）：

| action | 执行端 | 说明 |
| --- | --- | --- |
| `navigate` | `navigate` | 导航并等待加载完成 |
| `evaluate` | `evaluateJavaScript` | 执行任意 JS（支持 Promise） |
| `click` | `clickElement` | 完整事件链点击 |
| `fill` | `fillElement` | native setter + React tracker |
| `select` | `selectOption` | 原生下拉框 |
| `hover` | `hoverElement` | 悬停展开菜单 |
| `press` | `pressKey` | 按键 |
| `getText` | `getText` | 页面文本 |
| `getHtml` | `getHtml` | 页面 HTML |
| `getBackbone` | `getBackbone` | 无障碍树（role/name/ref） |
| `screenshot` | `screenshot` / `screenshotIfVisible` | 截图（支持后台离屏） |
| `console` | `getConsoleLogs` | 控制台日志 |
| `wait` | `wait` | `waitForCondition` / `waitForDomStable` |
| `scroll` | `scroll` | 滚动到元素或坐标 |
| `dialog` | `holdDialog` / `resolveDialog` / `resolveDialogFromUi` | 处理挂起的 confirm/prompt |
| `back` / `forward` / `reload` | `goBack` / `goForward` / `reload` | 导航控制 |

页面弹窗的行为（面板打开时由用户处理、后台时挂起交由 AI `dialog` 应答）与选择器格式详见《内置浏览器》指南。
