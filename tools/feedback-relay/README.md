# 反馈收集中转

App 的「设置 → 反馈与建议」不再打开浏览器/GitHub 登录，而是直接 POST JSON 到收集页。
本目录是一个最小 Cloudflare Worker：它用**服务端**的 GitHub 令牌在 `EzRlon/AiCodeR`
创建 Issue，App 与用户都不接触令牌。

## 部署

```bash
cd tools/feedback-relay
npx wrangler deploy
npx wrangler secret put GITHUB_TOKEN   # 细粒度令牌，仅 Issues: Read and write
npx wrangler secret put FEEDBACK_KEY   # 可选，建议设置
```

在 `wrangler.toml` 或 Cloudflare 控制台设置普通变量：

```
REPO_OWNER=EzRlon
REPO_NAME=AiCodeR
```

## 配置 App

在仓库 `data` 分支新增 `data/feedback-endpoint.json`：

```json
{
  "endpoint": "https://aicode-feedback-relay.<你的子域>.workers.dev",
  "key": "与 FEEDBACK_KEY 一致，可留空"
}
```

App 每 24 小时从该分支拉取一次端点（`RepoDataFetcher` 有磁盘缓存），也会回退到内置
`app/src/main/assets/feedback-endpoint.json`。端点为空或网络失败时，反馈先进入
`filesDir/feedback-outbox/`，下次启动/提交自动重试，不会丢内容。

## 安全边界

- `FEEDBACK_KEY` 会随 App 包/仓库数据下发，只能挡掉随手刷接口，不能当强密钥；
  公开项目建议在 Worker 侧再加 Cloudflare Rate Limiting。
- 令牌只放 Worker secret，绝不进 App。
- App 只上传用户填写的描述、可选脱敏日志与匿名安装标识/机型/系统版本，不含账号。
