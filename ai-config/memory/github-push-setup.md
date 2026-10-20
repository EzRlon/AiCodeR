---
name: github-push-setup
description: GitHub 账号 EzRlon 已登录（gh 设备码）；AiCode 已推送至私有仓库 EzRlon/AiCode（origin，默认分支 dev）；网络坑：直连 GitHub 被 TLS 干扰，需开加速器
---
# GitHub 上传配置（2026-10-17 建立并验证）

## 账号与登录
- GitHub 账号：EzRlon
- 登录方式：gh CLI 2.63.0 设备码流程（`gh auth login --hostname github.com --git-protocol https --web --skip-ssh-key`；交互中还需回答 "Authenticate Git with your GitHub credentials? → y"，然后按 Enter，把一次性代码给用户去 github.com/login/device 授权）
- 凭据位置：/root/.config/gh/hosts.yml（明文保存，容器私有）；token scopes: gist, read:org, repo, workflow
- gh 安装：`apk add --no-cache github-cli`（Alpine 3.21 aarch64；busybox trigger 报 2 errors 但功能正常）；gh credential helper 已配置，git push 无需再输密码

## 已推送仓库
- https://github.com/EzRlon/AiCode —— 私有，默认分支 dev
- 内容：~/workspace/AiCode 全部历史（分支 dev=484b215 / main=官方快照起点 7833778 / feat/vnc-overlay=fde91e5 + tag milestone/vnc-overlay）
- 本地 remote：origin → https://github.com/EzRlon/AiCode.git；upstream → /tmp/upstream-src（官方上游快照，注意区分勿推错）
- 后续更新：在 ~/workspace/AiCode 提交后 `git push origin <分支>` 即可
- 推送时本地有未提交改动 ChatDrawer.kt，未包含在已推送历史中

## 网络坑（已验证，2026-10-17）
- 用户网络直连 GitHub 会被干扰：DNS 解析正常（真实 IP）、TCP 443 可连、但 TLS 握手被切（`OpenSSL: unexpected eof while reading`），github.com / api.github.com / raw.githubusercontent.com 全部失败
- 判据：baidu 通 + GitHub 全域名 TLS 断 = 被干扰
- 解决：用户开启加速器（建议全局模式）后全部恢复（github.com 4/4 通），推送 54.7MiB 实测 5.81 MiB/s
- 排查命令：`wget -S -O /dev/null --timeout=15 https://api.github.com/zen` 看报错；`for i in 1 2 3; do wget -q -O /dev/null https://github.com && echo 通 || echo 失败; done`