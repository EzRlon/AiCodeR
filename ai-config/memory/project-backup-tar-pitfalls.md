---
name: project-backup-tar-pitfalls
description: 项目备份两包构成与 busybox tar 排除/多 -C 坑（已验证）：--exclude 前缀需与归档参数一致、不支持多 -C、发布签名 jks 是迁移关键
---
# 项目备份打包与 busybox tar 两个坑（2026-10-17 已验证）

## 项目迁移/备份的两个包（用户「迁移到新版本」需求）

1. **源码包**（排除构建产物后 ~526M）：`~/workspace/aicoder-项目全量备份-20261017.tar.gz`
   - 内容：AiCode 全仓库（含 .git 历史）、工作区根仓库 .git、AGENTS.md、mcp-multi-llm、交付 APK
   - 排除：AiCode/.gradle、AiCode/app/build、AiCode/docs-site/node_modules、AiCode/build、.kotlin、.idea、local.properties、app/keystore.properties
2. **密钥与 AI 配置包**（~1.7M）：`~/workspace/aicoder-签名密钥与AI配置-20261017.tar.gz`
   - 内容：~/.aicode 全部（keys/aicode-release.jks 发布签名 + memory/skills/mcp.json/notes 等，排除 tool-output 日志）+ app/keystore.properties（临时复制进 keys/ 打包后已清理）
   - **发布签名是迁移关键**：无 jks+keystore.properties，新环境编的正式包与旧包签名不一致，无法覆盖升级

## busybox tar 坑（都验证过）

- **--exclude 路径前缀必须与归档参数完全一致**：`tar -czf x.tar.gz AiCode ...` 时 exclude 写 `./AiCode/...` 不生效（静默），构建产物全打进去（1.8G）；写成 `AiCode/...` 才生效（526M）。
- **不支持多个 -C 且无 -r 追加**：多 -C 时只有最后一个生效；要合并多个源只能临时复制到单一目录再打包，或接受「把文件临时放进源目录打完再删」。
- 校验方法：`tar -tzf <包> | grep -c <不该有的路径>` 应为 0。