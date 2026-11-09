#!/bin/sh
# 跑 Compose UI 自动化测试（Robolectric 载体，无需设备/模拟器）。
#
# 为什么要有这个脚本，而不是直接调 gradlew：
# 本机（arm64 容器）的测试任务在构建被中断后会**静默使用陈旧字节码**——
# 源码改了但 .class 没重编，测试跑的是上一版，红了也定位不到真因。
# 这个坑在反复中断的会话里踩过三次，所以把「清产物」固化成脚本的一部分。
#
# 用法：
#   sh scripts/run-ui-tests.sh              # 跑全部 UI 用例
#   sh scripts/run-ui-tests.sh <类名>       # 只跑指定类（如 com.aicode.core.ui.ComposeInfraTest）
set -e

cd "$(dirname "$0")/.."

TARGET="${1:-com.aicode.core.ui.ComposeInfraTest}"

if [ -z "$1" ]; then
  echo "==> 未指定类名，跑全部 UI 测试类"
fi

# 1) 停 daemon：避免清理时 daemon 正在写产物而崩溃
./gradlew --stop >/dev/null 2>&1 || true
sleep 2

# 2) 清掉测试编译产物与旧报告：强制重新编译，规避陈旧字节码
rm -rf app/build/intermediates/classes/universalDebugUnitTest \
       app/build/tmp/kotlin-classes/universalDebugUnitTest \
       app/build/test-results/testUniversalDebugUnitTest 2>/dev/null || true

# 3) LANG 必须显式设置：测试方法名含中文，缺了它类文件名会炸
if [ -n "$1" ]; then
  LANG=C.UTF-8 LC_ALL=C.UTF-8 ./gradlew :app:testUniversalDebugUnitTest \
    --tests "$TARGET" --console=plain
else
  LANG=C.UTF-8 LC_ALL=C.UTF-8 ./gradlew :app:testUniversalDebugUnitTest \
    --tests "com.aicode.core.ui.ComposeInfraTest" \
    --tests "com.aicode.feature.agent.presentation.component.ComposeUiSmokeTest" \
    --tests "com.aicode.feature.agent.presentation.component.ChatInputBarLayoutTest" \
    --console=plain
fi
