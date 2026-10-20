# 新设备恢复手册（AI 可执行）

> **触发条件**：clone 了本仓库但尚未完成构建初始化——`app/keystore.properties` 或 `~/.aicode/keys/aicode-release.jks` 不存在时，先完整执行本手册，再开始任何开发或构建。
> **执行者**：App 内 AI。按顺序执行，**每步完成自验后再进行下一步；任何一步失败即停下报告，不要跳过**。

## 第 0 步：前置确认

- 本仓库 clone 完成（你正在读这个文件即说明 ✓）
- 网络可达 GitHub（`timeout 10 wget -q -O /dev/null https://github.com && echo 通 || echo 断`；不通提醒用户开加速器）
- 容器基础环境已初始化（bash/git/rg/curl 可用；没有则先跑终端初始化菜单一键安装）

## 第 1 步：恢复发布签名（跳过则出的正式包无法覆盖升级）

备份文件**就在本仓库内**：`backup/aicoder-签名密钥与AI配置-20261017.tar.gz`（含发布签名 jks 与密码配置，是全仓库唯一密钥来源）。

```bash
# 从仓库根执行
mkdir -p ~/.aicode/keys /tmp/restore
tar -xzf "backup/aicoder-签名密钥与AI配置-20261017.tar.gz" -C /tmp/restore ./keys/
cp /tmp/restore/keys/aicode-release.jks ~/.aicode/keys/aicode-release.jks
cp /tmp/restore/keys/keystore.properties app/keystore.properties
rm -rf /tmp/restore
```

**自验（必须执行）**：
```bash
md5sum ~/.aicode/keys/aicode-release.jks
# 必须输出：1eefe16a7f717213e63ed6c8017fef1a  （与历史签发密钥逐字节一致）
test -f app/keystore.properties && echo "keystore.properties OK"
```
不一致 → 立即停止并报告，**禁止**用不一致的密钥继续构建。

## 第 2 步：恢复 AI 协作配置（可选但建议）

`ai-config/` 目录内容与放置位置：

| 文件/目录 | 放置目标 | 说明 |
|---|---|---|
| `ai-config/AGENTS.md` | **工作区根**（clone 目录的上一级，或直接以本仓库为项目根时已就位） | App 项目规则注入源 |
| `ai-config/notes/`、`ai-config/memory/` | 项目 `.aicode/notes/`、`.aicode/memory/` | 交接档案与项目记忆（28+21 个文件） |
| `ai-config/global-notes/`、`ai-config/global-memory/` | `~/.aicode/notes/`、`~/.aicode/memory/` | 全局笔记与用户偏好 |
| `ai-config/prompt-overrides/` | `~/.aicode/prompts/` | **谨慎**：这是旧版提示词定制（含心跳汇报纪律等），可能落后于新版内置提示词（仓库 assets 已新增 41/45 两个新文件）。**默认不恢复**；确需恢复先逐文件与 `app/src/main/assets/prompts/` 比对，避免盖掉新版改进 |

## 第 3 步：搭建编译环境

**优先跟随 App 内置文档**：`docs-site/docs/advanced/build-android-app.md`（JDK 17 + Android SDK 36 腾讯镜像一条命令安装，含 Alpine musl 注意事项）。要点：

- 推荐 Debian/Ubuntu 自定义镜像；内置 Alpine（musl）也可用但需第 4 步的 LD_PRELOAD 补丁
- JDK：`apt install -y openjdk-17-jdk-headless`（Alpine：`apk add openjdk17`）
- SDK：`sdkmanager` 装 `platforms;android-36` `build-tools;35.0.0`（镜像 `SDK_TEST_BASE_URL=https://mirrors.cloud.tencent.com/AndroidSDK/`）
- Gradle：**用仓库自带 wrapper**（`./gradlew`，distributionUrl 已指向腾讯镜像 gradle-8.14），无需手动安装

**自验**：`java -version` 输出 17；`ls $ANDROID_HOME/platforms/android-36` 存在。

## 第 4 步：musl 容器专用补丁（仅 Alpine 容器需要；Debian/Ubuntu 跳过）

musl 的 `remove()` 缺陷会让 AGP mergeJavaRes 必败（`Failed to delete /tmp/tempdir_*`）。补丁已随仓库入库：

```bash
mkdir -p /root/tmp
cp tools/fix-remove/fix_remove.c tools/fix-remove/fix_remove.so /root/tmp/
# .so 已是编译好的 aarch64 musl 产物，直接可用；若需重编：
# aarch64-alpine-linux-musl-gcc -shared -fPIC -o /root/tmp/fix_remove.so /root/tmp/fix_remove.c
```

之后**所有 gradle 命令都必须带** `LD_PRELOAD=/root/tmp/fix_remove.so`。

## 第 5 步：构建验证

```bash
# 快速语法验证（约 1-3 分钟）
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk \
  LD_PRELOAD=/root/tmp/fix_remove.so ./gradlew :app:compileArmsoloReleaseKotlin --console=plain
# 正式包（R8，约 20-30 分钟）
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/root/android/sdk \
  LD_PRELOAD=/root/tmp/fix_remove.so ./gradlew assembleArmsoloRelease --console=plain
```

产物：`app/build/outputs/apk/armsolo/release/app-armsolo-release.apk`

**自验**：`aapt2 dump badging <apk> | head -2` 确认 versionCode 单调递增、包名 `com.aicoder`。

## 第 6 步：App 供应商配置（AI 无法代做，提醒用户）

模型供应商与 API key 存于 App 内部数据库，git 覆盖不到。两条路：
1. **旧设备 App「设置 → 备份与还原」加密导出 → 本机导入**（无缝恢复，含聊天历史）
2. 重新手配（设置 → AI 供应商）

## 已知环境坑（构建失败先对照这里）

| 症状 | 处置 |
|---|---|
| mergeJavaRes 报 `Failed to delete /tmp/tempdir_*` | 漏了 `LD_PRELOAD=/root/tmp/fix_remove.so` |
| `apk add <已装包>` 退出码 2 无明细 | PRoot 正常现象，按缺补装 + 二进制存在性验证 |
| KSP 报 `Not sure how to convert query result` | 增量缓存损坏：移走 `app/build/kspCaches` 与 `app/build/generated/ksp` 重跑 |
| Kotlin 报错误清单为空/构建异常 | 移走 `app/build/tmp/kotlin-classes` 重跑 |
| 直连 GitHub TLS 断（baidu 正常） | 用户开加速器（全局模式） |

## 维护说明

- 签名密钥（jks + keystore.properties）**永远不入库**（.gitignore 已排除）；唯一来源是 `backup/` 内的这份打包
- 仓库保持**私有**：backup/ 内含签名密码，转公开前必须先移除并重写历史
- 修改 `backup/` 打包内容或本手册后，必须同步更新第 1 步的 md5 校验值
