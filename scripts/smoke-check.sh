#!/usr/bin/env bash
# 装包后容器侧自检（只读，不修改任何数据）。
#
# 用法：
#   bash ~/workspace/AiCodeR/scripts/smoke-check.sh
# 或在 App 终端里：
#   bash scripts/smoke-check.sh
#
# 覆盖范围：引擎 CLI 是否可用、随记目录/文件、任务板文件、桥接脚本。
# 不覆盖（必须人工点）：反馈页提交、随记浮窗交互、三引擎实际对话、粉/润色效果。

set -u
fail=0
ok()   { printf '  ✅ %s\n' "$*"; }
bad()  { printf '  ❌ %s\n' "$*"; fail=1; }
info() { printf '  ·  %s\n' "$*"; }

WS="${AICODE_WORKSPACE:-$HOME/workspace}"
echo "== 1) 引擎 CLI =="
for bin in claude codex opencode node python3; do
  if command -v "$bin" >/dev/null 2>&1; then
    v=$("$bin" --version 2>/dev/null | head -1)
    ok "$bin 可用 ${v:+($v)}"
  else
    info "$bin 未安装（用到该引擎时再装）"
  fi
done

echo "== 2) 随记（顶栏「随记」→ 写一句 → 等 1 秒）=="
NB="$WS/.aicode/notebook"
if [ -d "$NB" ]; then
  n=$(find "$NB" -maxdepth 1 -name '*.md' | wc -l | tr -d ' ')
  ok "目录存在：$NB（$n 篇）"
  find "$NB" -maxdepth 1 -name '*.md' -printf '     - %f (%s B)\n' 2>/dev/null | head -5
  empties=$(find "$NB" -maxdepth 1 -name '*.md' -size 0 | wc -l | tr -d ' ')
  [ "$empties" -gt 0 ] && info "有 $empties 篇空文件（新建后未输入属正常）"
else
  bad "目录不存在：$NB（先在 App 里打开随记并输入内容）"
fi

echo "== 3) 任务板（外部引擎 + 专家团跑一轮后生成）=="
BOARD="$WS/.aicode/orchestration/board.json"
if [ -f "$BOARD" ]; then
  ok "board.json 存在（$(wc -c < "$BOARD" | tr -d ' ') B）"
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$BOARD" <<'PY' 2>/dev/null || info "无法解析 JSON（文件可能正被写入）"
import json,sys
d=json.load(open(sys.argv[1]))
tasks=d.get("tasks") if isinstance(d,dict) else d
print(f"     任务数: {len(tasks) if isinstance(tasks,list) else '?'}")
if isinstance(tasks,list):
    for t in tasks[:5]:
        if isinstance(t,dict):
            print(f"     - {t.get('id','?')} [{t.get('status','?')}] {str(t.get('title',''))[:40]}")
PY
  fi
else
  info "board.json 不存在（用 Claude Code/Codex/OpenCode + 专家团跑一轮再看）"
fi

echo "== 4) 桥接资产 =="
for p in "$HOME/.aicode/.aicode/bridge/aicode-bridge.mjs" "$HOME/.aicode/bridge/aicode-bridge.mjs"; do
  [ -f "$p" ] && { ok "桥脚本：$p"; break; }
done

echo
if [ "$fail" -eq 0 ]; then
  echo "容器侧自检通过。接下来按清单人工验证 UI（反馈页 / 随记浮窗 / 三引擎）。"
else
  echo "有项目未通过，见上面 ❌ 行。"
fi
exit "$fail"
