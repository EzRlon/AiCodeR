---
name: kotlin-syntax-check-without-gradle
description: 禁止 gradle 时用 gradle 缓存内的 kotlin-compiler-embeddable 做 Kotlin 语法级验证的命令与判读方法（已实测）
---
# 在「禁止运行 gradle」的约束下做 Kotlin 语法级验证（2026-10-15 实测走通）

场景：多会话并行改同一工作区时，任务常要求本会话「不运行任何 gradle/构建命令」（避免与主进程构建冲突）。此时可用 gradle 缓存里的 kotlin-compiler-embeddable 直接对改动文件做「语法解析」验证，捕获括号不配平、语法笔误等编译阻断级问题。

## 命令模板（容器内已验证）

```bash
JAVA=/usr/lib/jvm/java-17-openjdk/bin/java
JAR=/root/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21/b13aed5cf8412f9ee9d1e4db21bdcfebcc24f606/kotlin-compiler-embeddable-2.2.21.jar
STDLIB=/root/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.21/fa374a986e128314c3db00a20aae55f72a258511/kotlin-stdlib-2.2.21.jar
REFLECT=/root/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-reflect/2.2.21/17bc47f5dfe27c2351538e711f0afe377b0de6b2/kotlin-reflect-2.2.21.jar
COROUTINES=/root/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/ac1dc37a30a93150b704022f8d895ee1bd3a36b3/kotlinx-coroutines-core-jvm-1.8.0.jar
$JAVA -Djava.awt.headless=true -cp "$JAR:$STDLIB:$REFLECT:$COROUTINES" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -classpath "$STDLIB:$COROUTINES" \
  -d /tmp/kotlinc-out-check <要检查的 .kt 文件...> > /tmp/kotlinc-out.txt 2>&1
```

## 判读结果

- 语法错误 ← grep "expecting\|unexpected token\|syntax error"；输出为空 = 语法层面 OK（括号、字符串、结构配平）。
- 输出里几百条 `unresolved reference 'androidx'/'R'/'MaterialTheme'` 与 `cannot infer type for type parameter` 均为 classpath 缺失（无 Android 依赖）的级联噪声，属预期；判断方法：未改动的旧代码同样报同类错。
- jar 路径随版本变，用 `find /root/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable -name "*.jar"` 现查现用。
- 注意这是「语法级」验证，不覆盖类型正确性与 Compose API 真伪——字面 API 仍需人工逐字核对。

## 辅助脚本

改 session-interaction 批时写过 /tmp/brace_check.py（Kotlin 字符级括号配平检查，处理 //·/* */·"..."·"""..."""·'c' 转义）——临时文件，如需重建按同样思路写即可。