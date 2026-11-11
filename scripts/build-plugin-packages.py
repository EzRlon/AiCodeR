#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 data/plugins/<id>/ 打成可分发的插件包（zip）。

插件不再随 APK 内置：用户拿到 zip 后走「设置 → 插件 → 从文件安装」。
本脚本只做打包，不改插件内容。

用法：
    python3 scripts/build-plugin-packages.py [--out dist/plugins]

包布局：zip 根目录直接是 plugin.json 与插件文件（安装器两种布局都支持，
这里选根目录布局，解压出来最直观）。
"""

import argparse
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(ROOT, "data", "plugins")

# 打包时排除的目录/文件：Python 缓存、系统垃圾、编辑器临时文件。
SKIP_DIRS = {"__pycache__", ".git", ".idea"}
SKIP_SUFFIXES = (".pyc", ".pyo", ".swp", ".DS_Store")


def iter_package_files(package_dir):
    for dirpath, dirnames, filenames in os.walk(package_dir):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            if name.endswith(SKIP_SUFFIXES):
                continue
            full = os.path.join(dirpath, name)
            yield full, os.path.relpath(full, package_dir)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=os.path.join(ROOT, "dist", "plugins"))
    args = parser.parse_args()

    if not os.path.isdir(SRC_DIR):
        print(f"找不到插件源目录: {SRC_DIR}", file=sys.stderr)
        return 1

    os.makedirs(args.out, exist_ok=True)
    built = 0

    for name in sorted(os.listdir(SRC_DIR)):
        package_dir = os.path.join(SRC_DIR, name)
        manifest_path = os.path.join(package_dir, "plugin.json")
        if not os.path.isdir(package_dir) or not os.path.isfile(manifest_path):
            continue

        with open(manifest_path, encoding="utf-8") as f:
            manifest = json.load(f)
        plugin_id = manifest.get("id")
        version = manifest.get("version", "0.0.0")
        if plugin_id != name:
            print(f"[跳过] {name}: 目录名与清单 id（{plugin_id}）不一致", file=sys.stderr)
            continue

        target = os.path.join(args.out, f"{plugin_id}-{version}.zip")
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zf:
            for full, rel in iter_package_files(package_dir):
                # 统一用 / 作分隔符，避免 Windows 上打出的包在 Android 侧被判为非法路径。
                zf.write(full, rel.replace(os.sep, "/"))
        size_kb = os.path.getsize(target) // 1024
        print(f"[OK] {target}  ({size_kb} KB)")
        built += 1

    if built == 0:
        print("没有可打包的插件", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
