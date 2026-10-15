#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阅读笔记系统 —— SQLite 迁移执行器

用法:
    python apply.py                  # 应用到默认数据库 ./data/notes.db
    python apply.py --db path/to.db  # 指定数据库路径
    python apply.py --check          # 仅做完整性检查，不执行迁移
    python apply.py --reset          # 危险：删除数据库后重新执行全部迁移

说明:
    本脚本按文件名排序依次执行当前目录下 0001_*.sql / 0002_*.sql / ... 迁移文件。
    已执行过的迁移会记录在 schema_migrations 表中，重复运行不会重复执行。
    由于 sqlite3 CLI 未必在 PATH 中，本脚本使用 Python 内置 sqlite3 模块，
    跨平台可用（Windows / macOS / Linux）。
"""

import argparse
import os
import sqlite3
import sys

# 当前脚本所在目录（即 sql/ 目录）
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DB = os.path.join(SCRIPT_DIR, "data", "notes.db")


def find_migrations():
    """返回按文件名排序的迁移文件绝对路径列表。"""
    files = []
    for name in os.listdir(SCRIPT_DIR):
        if name.endswith(".sql") and name[:4].isdigit() and "_" in name:
            files.append(os.path.join(SCRIPT_DIR, name))
    return sorted(files)


def get_applied_versions(conn):
    """返回已执行过的迁移版本号集合。"""
    try:
        cur = conn.execute("SELECT version FROM schema_migrations")
        return {row[0] for row in cur.fetchall()}
    except sqlite3.OperationalError:
        # 表还不存在（首次运行 0001 之前）
        return set()


def extract_version(path):
    """从文件名前缀提取版本号，如 0001_init.sql -> 1"""
    return int(os.path.basename(path).split("_")[0])


def apply_one(conn, path):
    """执行单个迁移文件。"""
    with open(path, "r", encoding="utf-8") as f:
        sql = f.read()
    # executescript 会自动 COMMIT 并关闭当前事务
    conn.executescript(sql)
    return extract_version(path)


def main():
    parser = argparse.ArgumentParser(description="应用 SQLite 迁移")
    parser.add_argument("--db", default=DEFAULT_DB, help="数据库文件路径")
    parser.add_argument("--check", action="store_true", help="仅完整性检查")
    parser.add_argument("--reset", action="store_true", help="删除数据库后重新执行全部迁移")
    args = parser.parse_args()

    db_path = os.path.abspath(args.db)

    if args.reset:
        if os.path.exists(db_path):
            os.remove(db_path)
            print(f"[reset] 已删除旧数据库: {db_path}")
        # 一并清理 WAL / SHM 残留
        for suffix in ("-wal", "-shm"):
            side = db_path + suffix
            if os.path.exists(side):
                os.remove(side)

    os.makedirs(os.path.dirname(db_path), exist_ok=True)

    conn = sqlite3.connect(db_path)
    # 关键 PRAGMA：外键 + WAL + 总线超时
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA synchronous = NORMAL")
    conn.execute("PRAGMA busy_timeout = 5000")

    try:
        if args.check:
            result = conn.execute("PRAGMA integrity_check").fetchone()
            print(f"[check] integrity_check = {result[0]}")
            fk = conn.execute("PRAGMA foreign_key_check").fetchall()
            print(f"[check] foreign_key_check violations = {len(fk)}")
            return 0 if result[0] == "ok" else 1

        migrations = find_migrations()
        if not migrations:
            print("[error] 未找到任何迁移文件（需形如 0001_xxx.sql）")
            return 1

        applied = get_applied_versions(conn)
        print(f"[info] 数据库: {db_path}")
        print(f"[info] 发现 {len(migrations)} 个迁移文件，已执行版本: {sorted(applied) or '无'}")

        new_count = 0
        for path in migrations:
            ver = extract_version(path)
            name = os.path.basename(path)
            if ver in applied:
                print(f"[skip ] {name} (v{ver}) 已执行")
                continue
            try:
                apply_one(conn, path)
                print(f"[ok   ] {name} (v{ver}) 执行成功")
                new_count += 1
            except sqlite3.Error as e:
                print(f"[FAIL ] {name} (v{ver}) 执行失败: {e}")
                print(f"        SQL 文件: {path}")
                return 1

        print(f"\n[done] 本次新执行 {new_count} 个迁移")

        # 完整性校验
        integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
        fk_violations = conn.execute("PRAGMA foreign_key_check").fetchall()
        print(f"[check] integrity_check  = {integrity}")
        print(f"[check] foreign_key_check= {len(fk_violations)} 处违规")

        # 输出各表行数
        tables = conn.execute(
            "SELECT name FROM sqlite_master "
            "WHERE type='table' AND name NOT LIKE 'sqlite_%' "
            "ORDER BY name"
        ).fetchall()
        print("\n[info] 表清单与行数:")
        for (tname,) in tables:
            cnt = conn.execute(f'SELECT COUNT(*) FROM "{tname}"').fetchone()[0]
            print(f"       {tname:<20} {cnt:>6} 行")

        return 0 if integrity == "ok" else 1

    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
