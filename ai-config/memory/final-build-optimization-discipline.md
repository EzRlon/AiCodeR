---
name: final-build-optimization-discipline
description: 用户强制纪律：每次最终编译必做性能/功耗/R8 商业级优化与自查（五项清单+编译顺序）
---
# 最终编译必做商业级优化（用户 2026-10-18 明确要求，长期纪律）

**每次最终编译（正式包）前必须执行以下优化与自查，缺一不可：**

1. **性能优化**：无主线程重活（磁盘/DB/网络/大计算一律 IO 线程）；列表稳定 key；避免高频重组（派生状态 remember/derivedStateOf、collectAsStateWithLifecycle、debounce 高频流）；冷启动懒加载；关键路径无阻塞 IO。
2. **功耗优化**：无常驻轮询/定时器；动画只在交互与忙碌态、时长克制；后台任务克制可取消、有缓存不重复消耗；无无谓唤醒与系统 IPC；网络请求 12h 缓存 + 失败静默 + 不重复发起（如镜像目录刷新在发布源就绪前保持停用）。
3. **R8 优化**：release 必须 `isMinifyEnabled=true` + `isShrinkResources=true` + R8 full mode；检查 proguard 新增 keep 是否必要（历史坑：JNA/Vosk/eddsa 需显式 keep）；构建后核对 mapping 与资源收缩日志；改动涉及反射/序列化/资源名访问时评估混淆风险（如 `getIdentifier` 依赖资源名——禁用资源名混淆）。
4. **流畅性**：Compose 列表帧率、交互跟手；涉及 UI 改动尽量真机验收或明确标注未验证项。
5. 编译验证顺序固定：compileUniversalDebugKotlin（快速语法）→ testUniversalDebugUnitTest（全绿）→ assembleUniversalRelease（R8 正式包）→ aapt 校验版本 → 交付。

**交付说明中须注明本次优化项与未验证项**（AGENTS.md 性能与功耗纪律 + 本条）。