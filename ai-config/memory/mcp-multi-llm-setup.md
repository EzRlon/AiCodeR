---
name: mcp-multi-llm-setup
description: mcp-multi-llm 在 musl 容器的完整启动链路：venv直跑、playwright手工装、chromium参数、Xvfb/VNC要点、登录方式
---
# mcp-multi-llm 启动与运维（proot/musl 容器专用）

## 启动方式
- 禁止 `uv run server.py`（uv 会重新同步依赖，musl 下 playwright 无官方轮子必失败）。用 `cd /root/workspace/mcp-multi-llm && .venv/bin/python server.py`。
- 全局 MCP 注册（~/.aicode/mcp.json）：command=`/root/workspace/mcp-multi-llm/.venv/bin/python`，args=[`/root/workspace/mcp-multi-llm/server.py`]，已用 manageMcp 配好，新会话自动生效。

## Playwright 安装（musl 无官方轮子）
1. 下载 manylinux2014_aarch64 轮子（pip download --platform manylinux2014_aarch64 --only-binary=:all:）
2. 用 python zipfile 直接解压进 .venv/lib/python3.12/site-packages/（uv 拒绝跨平台安装）
3. `uv pip install --python .venv/bin/python "pyee>=13,<14" "greenlet>=3.1.1,<4.0.0"`
4. 删除 playwright/driver/node（glibc 二进制在 musl 报 fcntl64 符号错误），软链到系统 node：`ln -s /usr/bin/node driver/node`
5. 解压后要 `chmod +x` driver/node

## chromium 必需参数（browser_manager.py 已内置）
- `--disable-gpu`：不加则 GPU 初始化风暴，playwright launch 180s 超时；加了 2.8s 启动
- `--disable-dev-shm-usage`：容器无 /dev/shm（proot 无法创建），playwright 自动带；手动跑 chromium 必须加
- Xvfb 必须 `-nolock`（proot 禁止 link() 创建 /tmp/.X1-lock）
- x11vnc 必须 `-noshm`（proot 无 SysV shm，否则启动后秒退）

## 登录（浏览器 profile 持久化在 /root/chrome_mcp_multi_llm）
- browser_manager 无 DISPLAY 时自动拉起 Xvfb :1 + x11vnc :5901（-localhost 无密码，仅手机本机可连）
- 登录方式：手机装 VNC Viewer（如 AVNC），连 127.0.0.1:5901，直接操作浏览器登录 6 个网站；支持微信扫码
- profile 目录被删（如重启清 home）需重新登录全部站点
- 服务器启动前会 pkill 残留 chromium（防 profile 锁）， pkill 模式注意 [] 转义防自匹配

## 代码适配 mcp SDK 2.3.0（从 git master 安装）
- handler 签名必须 `async def h(ctx: ServerRequestContext, params)` 两个参数
- tools/call 注册 params_type=CallToolRequestParams（name+arguments），不是 CallToolRequest
- 已修 6 个 bug：handler 签名×2、get_client 的 __all__ 误用、browser_manager 双 await、self.config 赋值、PWTimeoutError 别名

## QA 审查后新增的关键机制（都已实测）
- start() 加 asyncio.Lock + 双重检查（ask_all 并发冷启动会互相争 profile）
- 阻塞操作（pkill/等待进程退出）放 asyncio.to_thread，否则冻结事件循环
- 冷启动 chromium 在本设备偶发闪退且留下占锁孤儿进程：launch 失败自动清理重试 3 次即自愈
- pkill 后必须轮询 pgrep 等旧进程完全退出（SIGTERM 下 chromium 退出要几秒），必要时 SIGKILL
- default_retry 不重试 TimeoutError（消息已发出，重发=重复提问）；只重试 BrowserDisconnectedError/SelectorError
- 回复完成检测用文本稳定性（连续 12 次×0.5s 无变化即完成），旧版“重新生成”按钮标志在新 UI 全部失效
- server.py 加全局 _ask_lock：模型客户端是单例共享页面，并发请求会在同一页面交织（实测返回错乱文本）
- qwen_client._maybe_click_new_chat 改为 reload 页面：匿名使用每轮回复后弹登录推广弹窗挡住输入区，刷新开新会话绕开
- chat.qwen.ai 是通义海外版（登录只有 Google/邮箱）；国内手机号登录版是 tongyi.com，如需登录再适配

## 网络注意
- 手机息屏/省电时容器网络间歇性 DNS 失败或连接超时，亮屏后重试即可，非代码问题

## 故障模式实录（2026-10-15 夜，定时任务 v1 审查时）
- **deepseek 页面卡死**：`Page.wait_for_selector .ds-assistant-message-main-content` 超时（10s），连短消息也不通；chromium/server 进程都活着，属页面状态问题。多轮重试无效。
- **qwen 页面不稳**：短消息可用；发长文本（>2KB）时发送按钮 `element detached from DOM`（页面重渲染时序），失败后常连带输入框 locator 消失——需等一段时间再试。附件通道不可用（找不到 `input[type=file]`，匿名模式无附件入口）。
- 应对：审查请求**分批+精简正文嵌入**（不用附件）；两通道交替重试；都不通时改用自查（对照审查清单逐条核实）+ qa 子代理（注意 qa 也常无产出），审查顺延如实记录，不阻塞提交。
- qa 子代理坑：容器无 ripgrep 时 search 工具不可用，qa 可能在工具问题上空转无产出——派发时必须明确告知「用 Bash 的 grep/find 与 readFile，不要用 search」。
- chatgpt.com/claude.ai/gemini.google.com/grok.com 国内直连被墙，需代理才可用（用户暂不用）
- 调试截图在 /root/mcp-debug/
- 测试技巧：server 是 stdio 服务，用 FIFO 喂请求：`mkfifo /tmp/mcpin; tail -f /tmp/mcpin | .venv/bin/python server.py`，然后 `printf '%s\n' '{json...}' > /tmp/mcpin`；终端 send 带 JSON 会报参数错误，用 Bash printf 代替
- ⚠️ 严重教训：测试完必须清理 FIFO 实例！多实例并存会互相 pkill 对方的 chromium，用户调用时好时坏（表象：browser has been closed / 神秘失败）。正式实例（应用自启的，cmdline 是绝对路径 bin/python 绝对路径/server.py）应保留，测试实例（cmdline 含 tail -f /tmp/mcpin）必须杀干净