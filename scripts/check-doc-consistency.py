#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文档一致性与「残留误导」一键检查 / 矫正。

背景见 ai-config/notes/residual-misleading-cleanup-plan.md：
仓库里有几批「功能早已删除或改名，但用户文档、侧栏索引、能力地图仍当它存在」的残留，
读者（含 AI）会被带去不存在的页面，或在发版当天让文档站构建因死链失败。

本脚本只做两件事，均零网络、纯本地、秒级：

  1) 检查（默认）：侧栏死链、孤儿文档、三方索引一致、中英 strings 成对、已删功能名黑名单。
  2) 矫正（--fix）：把台账里「确定无疑」的 A 类残留按精确串就地修正（幂等，可反复跑）。

用法：
    python3 scripts/check-doc-consistency.py            # 只检查，有发现则非零退出
    python3 scripts/check-doc-consistency.py --fix      # 先矫正，再检查
    python3 scripts/check-doc-consistency.py --list     # 只列出将要做的矫正，不改文件

退出码：0 = 无问题；1 = 有未处置的问题（--fix 后仍报错即为此）；2 = 用法错误。
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs-site", "docs")
CONFIG = os.path.join(ROOT, "docs-site", ".vitepress", "config.ts")

# ── 已删功能名黑名单：出现在用户可见资产里即报错 ──
# 只扫「用户可见资产」（文档站 / README / 字符串资源）。ai-config/ 下的历史交接笔记有意保留旧名，
# 不扫（改写会毁证据）。命中即说明又有陈旧表述渗入，需要按台账处置。
BLACKLIST = [
    # (正则, 说明)
    (r"test-builds", "已删功能「测试包」的文档链接"),
    (r"reverse-engineering", "已改名文档「程序分析」的旧链接"),
]

# 扫描面：用户可见资产
BLACKLIST_SCAN = [
    (os.path.join(DOCS), (".md",)),
    (os.path.join(ROOT, "README.md"), None),
    (os.path.join(ROOT, "README.en.md"), None),
    (os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml"), None),
    (os.path.join(ROOT, "app", "src", "main", "res", "values-en", "strings.xml"), None),
]

# ── A 类残留的确定性矫正（精确串，幂等）──
# kind=delete_file   ：整文件删除（描述的功能已不存在）
# kind=delete_line   ：删除含 match 的行（索引/侧栏项）
# kind=replace       ：精确子串替换
FIXES = [
    dict(
        kind="delete_file",
        path="docs-site/docs/guide/test-builds.md",
        reason="A1.1 整页描述已于 f7a4e1f 删除的「测试包」功能",
    ),
    dict(
        kind="delete_line",
        path="docs-site/.vitepress/config.ts",
        match="link: '/guide/test-builds'",
        reason="A1.2 侧栏项指向已删除的页面（死链，会让文档站构建失败）",
    ),
    dict(
        kind="delete_line",
        path="docs-site/docs/guide/overview.md",
        match="测试包与一键安装",
        reason="A1.3 功能总览表格行描述已删除功能",
    ),
    dict(
        kind="delete_line",
        path="docs-site/docs/guide/capabilities.md",
        match="[测试包与一键安装](/guide/test-builds)",
        reason="A1.4 能力条目整条描述已删除功能",
    ),
    dict(
        kind="replace",
        path="docs-site/docs/guide/capabilities.md",
        find="聊天文件卡片、测试包、存储产物",
        repl="聊天文件卡片、交付物条目、存储产物",
        reason="A1.5 功能名已不存在，改指承担该职责的交付物条目",
    ),
    dict(
        kind="replace",
        path="docs-site/docs/guide/capabilities.md",
        find="侧边栏「测试包」装到手机试用",
        repl="交付物页装到手机试用",
        reason="A1.6 组合菜谱里的入口名已不存在",
    ),
    dict(
        kind="replace",
        path="README.md",
        find="聊天文件、测试包、存储产物",
        repl="聊天文件、交付物、存储产物",
        reason="A1.7 README 功能名已不存在",
    ),
    dict(
        kind="replace",
        path="README.en.md",
        find="chat files, test builds and storage artifacts",
        repl="chat files, deliverables and storage artifacts",
        reason="A1.8 README 英文功能名已不存在",
    ),
    dict(
        kind="replace",
        path="docs-site/.vitepress/config.ts",
        find="{ text: '逆向分析（REA）', link: '/guide/reverse-engineering' }",
        repl="{ text: '程序分析（REA）', link: '/guide/program-analysis' }",
        reason="A2.1 文档已在 7173cd6 改名，侧栏未同步（死链 + 新页成孤儿）",
    ),
]


def rel(path):
    return os.path.relpath(path, ROOT)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


# ────────────────────────── 矫正 ──────────────────────────

def plan_fixes():
    """返回 [(fix, 是否会真的改动)]——用于 --list 与幂等判断。"""
    plan = []
    for fix in FIXES:
        path = os.path.join(ROOT, fix["path"])
        if fix["kind"] == "delete_file":
            plan.append((fix, os.path.exists(path)))
            continue
        if not os.path.exists(path):
            plan.append((fix, False))
            continue
        text = read(path)
        if fix["kind"] == "delete_line":
            hit = any(fix["match"] in line for line in text.splitlines())
        else:
            hit = fix["find"] in text
        plan.append((fix, hit))
    return plan


def apply_fixes():
    changed = 0
    for fix, will_change in plan_fixes():
        if not will_change:
            continue
        path = os.path.join(ROOT, fix["path"])
        if fix["kind"] == "delete_file":
            os.remove(path)
            print(f"  [删除文件] {fix['path']}  —— {fix['reason']}")
        elif fix["kind"] == "delete_line":
            kept = [ln for ln in read(path).splitlines(keepends=True)
                    if fix["match"] not in ln]
            write(path, "".join(kept))
            print(f"  [删行] {fix['path']}  含「{fix['match']}」  —— {fix['reason']}")
        else:
            write(path, read(path).replace(fix["find"], fix["repl"]))
            print(f"  [替换] {fix['path']}  「{fix['find']}」→「{fix['repl']}」  —— {fix['reason']}")
        changed += 1
    if changed == 0:
        print("  无需矫正：全部项已是目标状态。")
    return changed


# ────────────────────────── 检查 ──────────────────────────

def check_sidebar_links(problems):
    """侧栏 link 目标必须存在（不存在的页会让 vitepress build 失败）。"""
    cfg = read(CONFIG)
    for m in re.finditer(r"link:\s*'/([^']+)'", cfg):
        target = m.group(1)
        candidates = [
            os.path.join(DOCS, target + ".md"),
            os.path.join(DOCS, target, "index.md"),
        ]
        if not any(os.path.exists(c) for c in candidates):
            line = cfg[:m.start()].count("\n") + 1
            problems.append(f"侧栏死链：config.ts:{line} → /{target}（目标不存在，构建会失败）")


def check_orphan_docs(problems):
    """docs 下的正文页必须被侧栏登记，否则读者/AI 无从发现。"""
    cfg = read(CONFIG)
    linked = {m.group(1) for m in re.finditer(r"link:\s*'/([^']+)'", cfg)}
    for sub in ("guide", "advanced"):
        d = os.path.join(DOCS, sub)
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            if not name.endswith(".md") or name == "index.md":
                continue
            key = f"{sub}/{name[:-3]}"
            if key not in linked:
                problems.append(f"孤儿文档：docs/{key}.md 未被 config.ts 侧栏登记")


def check_three_way_index(problems):
    """overview.md / capabilities.md 里链接的页必须存在。"""
    have = set()
    for sub in ("guide", "advanced"):
        d = os.path.join(DOCS, sub)
        if os.path.isdir(d):
            have |= {f"{sub}/{n[:-3]}" for n in os.listdir(d) if n.endswith(".md")}
    for fname in ("guide/overview.md", "guide/capabilities.md"):
        p = os.path.join(DOCS, fname)
        if not os.path.exists(p):
            continue
        for m in re.finditer(r"\]\(/((?:guide|advanced)/[^)#\s]+)\)", read(p)):
            if m.group(1) not in have:
                line = read(p)[:m.start()].count("\n") + 1
                problems.append(f"索引失效：{fname}:{line} 链接 /{m.group(1)}（页面不存在）")


def check_bilingual_strings(problems):
    """中英 strings.xml 的 name 集合必须一致（资产同步硬规则）。"""
    def names(path):
        if not os.path.exists(path):
            return None
        return set(re.findall(r'<string\s+name="([^"]+)"', read(path)))

    zh = names(os.path.join(ROOT, "app/src/main/res/values/strings.xml"))
    en = names(os.path.join(ROOT, "app/src/main/res/values-en/strings.xml"))
    if zh is None or en is None:
        problems.append("strings.xml 缺失，无法比对中英一致性")
        return
    for k in sorted(zh - en):
        problems.append(f"双语缺失：英文 values-en 缺 string「{k}」")
    for k in sorted(en - zh):
        problems.append(f"双语多余：英文 values-en 多出 string「{k}」（中文无）")


def check_blacklist(problems):
    """已删/已改名的功能名不得再出现在用户可见资产里。"""
    for target, exts in BLACKLIST_SCAN:
        if os.path.isfile(target):
            files = [target]
        elif os.path.isdir(target):
            files = [os.path.join(dp, f) for dp, _, fs in os.walk(target) for f in fs
                     if not exts or f.endswith(exts)]
        else:
            continue
        for f in files:
            for lineno, line in enumerate(read(f).splitlines(), 1):
                for pat, desc in BLACKLIST:
                    if re.search(pat, line):
                        problems.append(
                            f"陈旧表述：{rel(f)}:{lineno} 命中「{pat}」（{desc}）")


CHECKS = [
    ("侧栏链接", check_sidebar_links),
    ("孤儿文档", check_orphan_docs),
    ("三方索引", check_three_way_index),
    ("中英 strings", check_bilingual_strings),
    ("残留黑名单", check_blacklist),
]


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--fix", action="store_true", help="先应用台账里的确定性矫正，再检查")
    ap.add_argument("--list", action="store_true", help="只列出将要做的矫正，不改动文件")
    args = ap.parse_args()

    if args.list:
        print("将要执行的矫正（幂等）：")
        plan = plan_fixes()
        for fix, will_change in plan:
            mark = "待改" if will_change else "已是"
            print(f"  [{mark}] {fix['kind']:12s} {fix['path']}  —— {fix['reason']}")
        return 0

    if args.fix:
        print("应用矫正：")
        apply_fixes()
        print()

    problems = []
    for title, fn in CHECKS:
        before = len(problems)
        fn(problems)
        found = len(problems) - before
        print(f"{'OK  ' if found == 0 else 'FAIL'} {title}：{found} 处")

    if problems:
        print("\n发现的问题：")
        for p in problems:
            print(f"  - {p}")
        print(f"\n合计 {len(problems)} 处。台账：ai-config/notes/residual-misleading-cleanup-plan.md")
        return 1

    print("\n全部通过：文档索引一致，无残留误导。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
