#!/usr/bin/env bash
# 一键验证（CI 同款口径）：单测 + 迁移对账 + detekt。
#
# 为什么写死在脚本里，而不是每次手敲 gradlew：
#   1) 容器 locale 不是 UTF-8 时，测试源集里的中文函数名会让 Kotlin 编译器抛
#      InvalidPathException（class 文件名写不进去）。LANG 必须在 daemon 启动前
#      生效；已在跑的旧 daemon 不受 export 影响，所以先 --stop 再跑。
#   2) 失败运行会留下乱码类名产物，增量快照引用后即使编码修好也报
#      "Failed to create MD5 hash"。每次先清对应变体的 kotlin-classes 与旧报告。
#   3) Gradle 构建缓存会让测试任务显示 FROM-CACHE，看似通过实则未执行。
#      默认用 --rerun-tasks --no-build-cache 强制真跑；--fast 才放行缓存。
#
# 注意：脚本开头会停掉本机 Gradle daemon（本容器单人开发，run-ui-tests.sh 同款做法）。
#
# 用法：
#   sh scripts/verify-all.sh              # 全量：真跑两个模块单测 + 迁移 + detekt
#   sh scripts/verify-all.sh --fast       # 允许 Gradle 缓存，日常快速自查
#   sh scripts/verify-all.sh --no-tests   # 只跑迁移 + detekt（纯文档/脚本改动）
#   sh scripts/verify-all.sh --no-detekt  # 跳过 detekt
# 退出码：任一步失败即非 0。

# 允许用 sh 调用（本容器 sh 是 dash，不支持进程替换/$(...)之外的 bash 语法）
if [ -z "${BASH_VERSION:-}" ]; then
  exec bash "$0" "$@"
fi

set -u

cd "$(dirname "$0")/.." || exit 1

FAST=0
RUN_TESTS=1
RUN_DETEKT=1
for arg in "$@"; do
  case "$arg" in
    --fast) FAST=1 ;;
    --no-tests) RUN_TESTS=0 ;;
    --no-detekt) RUN_DETEKT=0 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg（-h 查看用法）" >&2; exit 2 ;;
  esac
done

# UTF-8 必须在任何 Gradle 进程启动之前设置
export LANG=C.UTF-8 LC_ALL=C.UTF-8

LOG="/tmp/aicoder-verify-$(date +%Y%m%d-%H%M%S).log"
FAILED=0

# 旧 daemon 可能带着非 UTF-8 的 sun.jnu.encoding，且会占用产物目录
./gradlew --stop >/dev/null 2>&1 || true
sleep 2

# 清掉上次失败残留（中文函数名的乱码类产物）与旧测试报告，强制重新编译
rm -rf app/build/tmp/kotlin-classes/universalDebugUnitTest \
       app/build/test-results/testUniversalDebugUnitTest \
       terminal-emulator/build/test-results/testDebugUnitTest

# 同一 shell 内 tee，保证各阶段退出码能带回 SUMMARY
exec > >(tee "$LOG") 2>&1

echo "HEAD: $(git rev-parse HEAD)"
echo "BRANCH: $(git branch --show-current)"
echo "WORKTREE:"
git status --porcelain

if [ "$RUN_TESTS" = 1 ]; then
  echo "=== UNIT TESTS ==="
  if [ "$FAST" = 1 ]; then
    ./gradlew :app:testUniversalDebugUnitTest :terminal-emulator:testDebugUnitTest --console=plain
  else
    ./gradlew :app:testUniversalDebugUnitTest :terminal-emulator:testDebugUnitTest \
      --console=plain --rerun-tasks --no-build-cache
  fi
  rc=$?
  echo "TEST_EXIT=$rc"
  [ "$rc" -ne 0 ] && FAILED=1
fi

echo "=== DOCS CONSISTENCY ==="
# 侧栏死链 / 孤儿文档 / 三方索引 / 中英 strings 配对 / 已删功能名残留。
# 纯本地秒级；拦下「功能删了文档没删」「改名了侧栏没改」这类发版当天才炸的问题。
python3 scripts/check-doc-consistency.py
rc=$?
echo "DOCS_EXIT=$rc"
[ "$rc" -ne 0 ] && FAILED=1

echo "=== MIGRATIONS ==="
python3 scripts/check_migrations.py
rc=$?
echo "MIG_EXIT=$rc"
[ "$rc" -ne 0 ] && FAILED=1

if [ "$RUN_DETEKT" = 1 ]; then
  echo "=== DETEKT (changed files, CI parity) ==="
  FILES=""
  if git rev-parse -q --verify HEAD^ >/dev/null 2>&1; then
    FILES=$(git diff --name-only --diff-filter=ACMR "HEAD^...HEAD" -- 'app/src/main/java/**/*.kt' || true)
  fi
  # 顺带扫未提交改动，避免提交前跑验证时漏掉正在改的文件（重复路径靠 sort -u 去掉）
  FILES=$(printf '%s\n%s\n' "$FILES" "$(git diff --name-only --diff-filter=ACMR -- 'app/src/main/java/**/*.kt' || true)" \
    | sed '/^$/d' | sort -u)
  if [ -n "$FILES" ]; then
    # shellcheck disable=SC2086  # 需要按行拆分成多个文件参数
    ./tools/detekt/run-detekt.sh $FILES
    rc=$?
  else
    echo "无 app/src/main 下的 Kotlin 改动，跳过 detekt"
    rc=0
  fi
  echo "DETEKT_EXIT=$rc"
  [ "$rc" -ne 0 ] && FAILED=1
fi

# 构建结束立即释放内存，别让 daemon 常驻挤占宿主 App
./gradlew --stop >/dev/null 2>&1 || true

echo "=== SUMMARY ==="
if [ "$FAILED" -eq 0 ]; then
  echo "ALL PASSED"
else
  echo "FAILED (见上方各阶段 *_EXIT)"
fi
echo "LOG=$LOG"
exit "$FAILED"
