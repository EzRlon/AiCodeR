#!/bin/sh
# 用法: release-selfcheck.sh <apk> [基线 apk]
# 正式包多维自检（2.2）：把能自动化的发版纪律收敛成一次命令，构建后跑一遍再交付。
#
# 覆盖五类：
#   1. 版本号   —— apk 内 versionCode 必须等于 git 提交数 + 1（与 build.gradle.kts 的生成规则对账）
#   2. 体积差分 —— 总大小与逐文件增量 Top N；有基线包时给出膨胀来源（res / dex / assets 各自贡献）
#   3. 启动路径 —— 扫 AIEditorApp / MainActivity 的 onCreate，抓阻塞与常驻轮询（性能功耗纪律）
#   4. keep 规则 —— proguard-rules.pro 里点名保留的类，逐个确认在 dex 中存在（未被重命名/裁掉）
#   5. dex 引用数 —— 单 dex 方法引用逼近 65536 上限时预警
#
# 只做能静态判定的部分；「列表稳定 key」「高频重组」「动画时长」等仍需人工自查。
# 输出以 [PASS]/[WARN]/[FAIL] 标注，存在 FAIL 时退出码 1（可直接接在构建脚本后面）。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-/root/android/sdk}"
A="$SDK/cmdline-tools/latest/bin/apkanalyzer"
PRO="$ROOT/app/proguard-rules.pro"

if [ ! -x "$A" ]; then
    echo "[FAIL] 找不到 apkanalyzer：$A（请设置 ANDROID_HOME）"
    exit 1
fi
APK="$1"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "用法: $0 <apk> [基线 apk]"
    exit 1
fi

fails=0
warns=0
pass() { echo "[PASS] $*"; }
warn() { echo "[WARN] $*"; warns=$((warns + 1)); }
fail() { echo "[FAIL] $*"; fails=$((fails + 1)); }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "── 自检目标 ──"
echo "$APK"
echo

# ── 1. 版本号 ────────────────────────────────────────────────
echo "── 1. 版本号 ──"
summary="$("$A" apk summary "$APK")"
pkg="$(echo "$summary" | cut -f1)"
code="$(echo "$summary" | cut -f2)"
name="$(echo "$summary" | cut -f3)"
commits="$(cd "$ROOT" && git rev-list --count HEAD)"
expect=$((commits + 1))
echo "包名 $pkg / versionCode $code / versionName $name"
if [ "$code" = "$expect" ]; then
    pass "versionCode $code == 提交数 $commits + 1"
else
    fail "versionCode $code != 提交数 $commits + 1（构建时 HEAD 与现在不一致，或配置缓存冻结了旧版本号）"
fi
if echo "$name" | grep -q -- "-dirty"; then
    warn "versionName 带 -dirty：构建时工作区有未提交改动，交付包无法对应到某个提交"
else
    pass "versionName 不含 -dirty"
fi
echo

# ── 2. 体积差分 ──────────────────────────────────────────────
echo "── 2. 体积差分 ──"
size="$("$A" apk file-size "$APK")"
dl="$("$A" apk download-size "$APK")"
echo "包体 $(($size / 1024)) KB / 估算下载 $(($dl / 1024)) KB"

BASE="$2"
if [ -z "$BASE" ]; then
    # 缺省在 ~/workspace 里找最近一次交付包作为基线
    BASE="$(ls -t "$HOME"/workspace/*.apk 2>/dev/null | head -1)"
fi
if [ -n "$BASE" ] && [ -f "$BASE" ]; then
    base_abs="$(cd "$(dirname "$BASE")" && pwd)/$(basename "$BASE")"
    apk_abs="$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"
    if [ "$base_abs" = "$apk_abs" ]; then
        echo "无基线（未提供，且未找到其他交付包）——跳过差分"
    else
        base_size="$("$A" apk file-size "$BASE")"
        delta=$((size - base_size))
        echo "基线 $(basename "$BASE")：$(($base_size / 1024)) KB → 本包 $(($size / 1024)) KB（${delta} 字节）"
        "$A" files list --raw-size "$BASE" | awk -F'\t' 'NF==2 && $2!="/"{print $2"\t"$1}' > "$TMP/base.txt"
        "$A" files list --raw-size "$APK" | awk -F'\t' 'NF==2 && $2!="/"{print $2"\t"$1}' > "$TMP/new.txt"
        echo "逐文件增量 Top 8（字节）："
        awk -F'\t' '
            NR==FNR { old[$1]=$2; next }
            { new[$1]=$2 }
            END {
                for (p in new) { d = new[p] - (p in old ? old[p] : 0); if (d != 0) print d "\t" p }
                for (p in old) if (!(p in new)) print (-old[p]) "\t" p
            }' "$TMP/base.txt" "$TMP/new.txt" | sort -rn | head -8 | while IFS="$(printf '\t')" read -r d p; do
            echo "  $d	$p"
        done
        # 体积膨胀超过 2MB 视为需要解释的变化（新增功能/资源通常在这个量级内）
        if [ "$delta" -gt 2097152 ]; then
            warn "包体增长超过 2MB（${delta} 字节），确认是否来自预期内的新增资源"
        else
            pass "包体变化在 2MB 以内"
        fi
    fi
else
    echo "无基线（未提供，且未找到其他交付包）——跳过差分"
fi
echo

# ── 3. 启动路径 ──────────────────────────────────────────────
echo "── 3. 启动路径（阻塞 / 常驻轮询）──"
# 只看两个启动入口的 onCreate：其余初始化都在 appScope.launch 里，属后台协程不阻塞首帧。
START_FILES="$ROOT/app/src/main/java/com/aicode/AIEditorApp.kt $ROOT/app/src/main/java/com/aicode/MainActivity.kt"
hits=0
for f in $START_FILES; do
    [ -f "$f" ] || continue
    awk '/override fun onCreate/ {inblock = 1}
         inblock && /^    \}$/ {print FILENAME": "NR": "$0; inblock = 0}
         inblock {print FILENAME": "NR": "$0}' "$f" \
        | grep -E 'runBlocking|Thread\.sleep|while \(true\)|scheduleAtFixedRate|Timer\(|\.blockingGet\(' \
        | grep -v 'themeModeFlow\.first()' > "$TMP/start.txt" || true
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        echo "  $line"
        hits=$((hits + 1))
    done < "$TMP/start.txt"
done
if [ "$hits" -eq 0 ]; then
    pass "onCreate 内无阻塞调用与常驻轮询（主题偏好首读 runBlocking 属已知白名单）"
else
    warn "onCreate 命中 $hits 处阻塞/轮询，逐条确认是否可移出主线程"
fi
echo

# ── 4. keep 规则 ─────────────────────────────────────────────
echo "── 4. keep 规则核对 ──"
if [ ! -f "$PRO" ]; then
    fail "找不到 $PRO"
else
    # 点名类：类名含 * 的通配规则单独处理
    grep -E '^-keep (class|interface) ' "$PRO" | awk '{print $3}' | grep -v '\*' | sort -u > "$TMP/keep.txt"
    # 包级通配：`-keep class <pkg>.** { *; }`——前缀里不含 * 的才能静态验证（JNA/Vosk/sshj 类崩溃就出在这层）
    grep -E '^-keep (class|interface) ' "$PRO" | awk '{print $3}' \
        | grep '\.\*\*$' | sed 's/\.\*\*$//' | grep -v '\*' | sort -u > "$TMP/keeppkg.txt"
    skipped="$(grep -E '^-keep (class|interface) ' "$PRO" | awk '{print $3}' | grep -c '\*' || true)"
    "$A" dex packages --defined-only "$APK" | awk -F'\t' '$1 ~ /^C / {print $4}' | sort -u > "$TMP/classes.txt"
    missing="$(comm -23 "$TMP/keep.txt" "$TMP/classes.txt")"
    total="$(grep -c . "$TMP/keep.txt" || true)"
    if [ -z "$missing" ]; then
        pass "$total 个点名 keep 类全部在 dex 中存在（未被裁掉或重命名）"
    else
        echo "$missing" | sed 's/^/  缺失: /'
        fail "$(echo "$missing" | grep -c .) 个 keep 类在 dex 中找不到（反射会 ClassNotFoundException）"
    fi
    echo "  （另有 $skipped 条通配 keep 规则无法静态验证：包级 / * extends 形式）"

    # 包级通配：每个包前缀下至少要有 1 个类存活，否则说明整包被裁（历史坑：JNA/Vosk/sshj）
    pkg_total="$(grep -c . "$TMP/keeppkg.txt" || true)"
    pkg_missing=0
    if [ "$pkg_total" -gt 0 ]; then
        while IFS= read -r pkg; do
            [ -z "$pkg" ] && continue
            n="$(awk -v p="$pkg" 'index($0, p".") == 1' "$TMP/classes.txt" | grep -c . || true)"
            if [ "$n" -eq 0 ]; then
                echo "  空包: $pkg（keep 规则点名保留，但 dex 里一个类都没有）"
                pkg_missing=$((pkg_missing + 1))
            fi
        done < "$TMP/keeppkg.txt"
        if [ "$pkg_missing" -eq 0 ]; then
            pass "$pkg_total 个包级 keep 规则均有类存活（整包未被裁掉）"
        else
            fail "$pkg_missing 个包级 keep 规则对应的包在 dex 中为空"
        fi
    fi
fi
echo

# ── 5. dex 引用数 ────────────────────────────────────────────
echo "── 5. dex 引用数 ──"
"$A" dex references "$APK" | awk -F'\t' 'NF==2 && $2+0 > 0 {printf "  %s: %d\n", $1, $2; if ($2+0 > max) max=$2+0} END {print "  单 dex 最大 " max}' 
maxref="$("$A" dex references "$APK" | awk -F'\t' 'NF==2 {if ($2+0 > max) max=$2+0} END {print max+0}')"
if [ "$maxref" -gt 63000 ]; then
    warn "单 dex 引用数 $maxref 逼近 65536 上限，再加大依赖会构建失败（需拆 dex 或裁依赖）"
elif [ "$maxref" -gt 0 ]; then
    pass "单 dex 最大引用 $maxref，距 65536 上限有余量"
fi
echo

# ── 汇总 ────────────────────────────────────────────────────
echo "── 汇总 ──"
echo "FAIL $fails / WARN $warns"
if [ "$fails" -gt 0 ]; then
    echo "存在硬性失败项，修好后再交付。"
    exit 1
fi
echo "自检通过。仍须人工确认：列表稳定 key、高频重组、动画时长、后台任务可取消。"
