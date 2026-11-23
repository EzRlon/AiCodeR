# 子程序 MCP 调试通道模板

由母程序（AiCodeR）生成的每个子程序内置本模块，实现「母程序 ↔ 子程序」标准化调试通道（MCP 2026-07-28 Streamable HTTP 简化模式）。零特殊权限：仅 127.0.0.1 + token。

## 集成步骤（AI 生成子程序时执行）

1. **依赖**：子程序 `app/build.gradle.kts` 加
   `implementation("org.nanohttpd:nanohttpd:2.3.1")`
2. **文件**：复制 `SubprogramMcpServer.kt`、`SubprogramMcpService.kt`、`AppContextHolder.kt` 到子程序源码（改包名为子程序包名）
3. **Manifest**：
   - `<uses-permission android:name="android.permission.INTERNET"/>`
   - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>`
   - `<service android:name=".mcp.SubprogramMcpService" android:foregroundServiceType="specialUse" android:exported="false"/>`
4. **取分配值**：读工作区 `.aicode/subprograms.json`，取母程序预分配的 `port` 与 `token`
5. **启动**：宿主 Application `onCreate` 调
   `SubprogramMcpService.start(context, port = <分配的port>, token = "<分配的token>")`
6. **回填**：集成完成后把子程序包名写回 `.aicode/subprograms.json` 对应条目的 `packageName` 字段（母程序体检与工具调用依赖它）

## 安全规则（不可省略）

- 仅绑定 127.0.0.1；Bearer token 必须非空且随机（母程序用 UUID 生成）
- 拒绝带 Origin 头的请求（防浏览器跨源探测内网端口）
- token 不写入任何公开位置（Manifest/日志/仓库），仅母程序注册表与子程序运行时持有

## 工具集（母程序可调用的 MCP tools）

- `ping`：存活检查
- `get_status`：内存/运行时长
- `get_recent_logs`：应用日志尾部
- `run_selftest`：内置自检项（宿主 App 可扩展业务自检）
- `get_info`：包名/版本

## 母程序侧

设置 → 「子程序调试」选项卡（实现于母程序注册表 + McpManager 本地 HTTP 接入）；
AI 侧经 MCP client 以 `mcp__<子程序名>__<tool>` 直接调用诊断工具。
