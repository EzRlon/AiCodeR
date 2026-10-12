# 工具钩子（Hooks）

AI 每次调用工具的前后，可以自动运行你自己写的脚本：做静态检查、拦截危险操作、补充上下文，类似 Git hooks 的思路。

## 配置

在容器内编辑 `~/.aicode/hooks.json`（电脑上也可在 AiCode 私有目录 `aicode/hooks.json` 找到；文件不存在则钩子整体关闭）：

```json
{
  "hooks": {
    "PreToolUse": [
      { "matcher": "Bash|editFile", "script": "~/.aicode/hooks/check.sh", "timeoutMs": 10000 }
    ],
    "PostToolUse": [
      { "matcher": "*", "script": "~/.aicode/hooks/log.py" }
    ]
  }
}
```

- `matcher`：工具名匹配。`Bash` 精确匹配、`Bash|editFile` 多选、`*` 全部；大小写不敏感。
- `script`：脚本路径（容器内路径）。`.sh` / `.py` / `.js` 自动选择解释器，其它后缀要求文件自身可执行。
- `timeoutMs`：超时（默认 10000，最大 60000）。

## 行为

- **PreToolUse**：工具调用前运行。脚本**非零退出**即否决这次调用，AI 会收到你的脚本输出作为否决原因；零退出则放行，输出会附在结果里供 AI 参考。
- **PostToolUse**：工具调用完成后运行，输出附加到工具结果末尾（不阻断）。
- 脚本可读取环境变量：`AICODE_HOOK_EVENT`（事件名）、`AICODE_TOOL_NAME`（工具名）、`AICODE_TOOL_ARGS`（工具参数 JSON，超过 4096 字符截断）。
- 多个工具并行执行时，钩子也会并发运行，脚本请保证可重入。

## 示例：拦截对特定文件的修改

`~/.aicode/hooks/guard.sh`：

```sh
#!/bin/sh
# 禁止改 .env 文件：exit 2 表示否决
case "$AICODE_TOOL_ARGS" in
  *.env*) echo "禁止修改 .env，请改 .env.example"; exit 2 ;;
esac
```

## 边界与安全

- 仅本地模式（容器在手机上）生效；远程 SSH 模式暂不支持。
- 钩子配置位于 App 私有目录、经绑定进容器，等同于「你在终端里自己执行脚本」的信任级别，因此运行时**不逐次弹授权**——请只配置你信任的脚本。
- 配置仅全局一份（不随项目走），避免「项目文件被改写即静默执行命令」的风险。
- 明显的灾难性删除构造（best-effort 对脚本字段的粗检）会被兜底拒绝；这不是完整沙箱，请自行保证脚本安全。
