#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阅读笔记系统 —— MySQL 建库执行器（无需 mysql CLI，用 pymysql）

用法:
    python apply_mysql.py                        # 执行同目录/默认脚本 数据库建库脚本-MySQL版.sql
    python apply_mysql.py --sql path/to/file.sql # 指定脚本
    python apply_mysql.py --check                # 仅检查种子数据

连接参数（全部可环境变量覆盖，避免密码进代码/仓库）:
    DB_HOST (默认 localhost)  DB_PORT (默认 3306)
    DB_USER (默认 root)      DB_PASSWORD (默认空)
"""
import argparse
import os
import re
import sys

try:
    import pymysql
    import pymysql.constants
except ImportError:
    sys.exit("[error] 请先安装 pymysql: pip install pymysql")

HERE = os.path.dirname(os.path.abspath(__file__))
# 项目根 = HERE(mysql) 向上 6 级：sql -> resources -> main -> src -> backend -> reading-notes
PROJECT_ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", "..", ".."))
DEFAULT_SQL = os.path.join(PROJECT_ROOT, "数据库建库脚本-MySQL版.sql")
# 实际默认脚本：项目根目录《数据库建库脚本-MySQL版.sql》；若不存在则尝试本目录 mysql/0001_init.sql
if not os.path.exists(DEFAULT_SQL):
    DEFAULT_SQL = os.path.join(HERE, "0001_init.sql")


def connect():
    return pymysql.connect(
        host=os.environ.get("DB_HOST", "localhost"),
        port=int(os.environ.get("DB_PORT", "3306")),
        user=os.environ.get("DB_USER", "root"),
        password=os.environ.get("DB_PASSWORD", ""),
        charset="utf8mb4",
        client_flag=pymysql.constants.CLIENT.MULTI_STATEMENTS,
        autocommit=True,
    )


def apply_sql(path):
    with open(path, "r", encoding="utf-8-sig") as f:
        sql = f.read()

    # 提取头部建库块：完整脚本固定以 "CREATE DATABASE ...; USE reading_notes;" 开头
    # （多语句模式下 USE 不生效，需单独执行；此处用固定标记定位，不依赖正则）
    marker = "USE reading_notes;"
    idx = sql.find(marker)
    if idx >= 0:
        head = sql[:idx + len(marker)]
        body = sql[idx + len(marker):]
    else:
        head, body = "", sql

    # ① 无库连接：执行 CREATE DATABASE + USE（建库/切库）
    conn = connect()
    cur = conn.cursor()
    try:
        for stmt in head.split(";"):
            if stmt.strip():
                cur.execute(stmt)
    finally:
        conn.close()

    # ② 指定库连接：执行剩余建表/种子/验证语句（多语句一次执行）
    db = os.environ.get("DB_NAME", "reading_notes")
    conn = pymysql.connect(
        host=os.environ.get("DB_HOST", "localhost"),
        port=int(os.environ.get("DB_PORT", "3306")),
        user=os.environ.get("DB_USER", "root"),
        password=os.environ.get("DB_PASSWORD", ""),
        database=db,
        charset="utf8mb4",
        client_flag=pymysql.constants.CLIENT.MULTI_STATEMENTS,
        autocommit=True,
    )
    cur = conn.cursor()
    try:
        cur.execute(body)
        # 多语句模式：遍历所有结果集（末尾验证 SELECT 会有输出）
        while True:
            try:
                rows = cur.fetchall()
                if rows:
                    print("[select]", rows)
            except Exception:
                pass
            if cur.nextset() is None:
                break
        print(f"[done] 脚本执行成功: {os.path.basename(path)}")
    except Exception as e:
        print(f"[FAIL] 执行失败: {e}")
        return 1
    finally:
        conn.close()
    return 0


def check():
    conn = connect()
    cur = conn.cursor()
    try:
        cur.execute("USE reading_notes")
        for table, expect in (("eval_datasets", 50), ("prompt_versions", 8),
                              ("tags", 6), ("books", 5), ("schema_migrations", 4)):
            cur.execute(f"SELECT COUNT(*) FROM {table}")
            cnt = cur.fetchone()[0]
            status = "OK " if cnt == expect else "FAIL"
            print(f"[{status}] {table:<18} {cnt} 行 (期望 {expect})")
            if cnt != expect:
                return 1
        return 0
    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser(description="MySQL 建库执行器")
    parser.add_argument("--sql", default=DEFAULT_SQL, help="SQL 脚本路径")
    parser.add_argument("--check", action="store_true", help="仅检查种子数据")
    args = parser.parse_args()
    return check() if args.check else apply_sql(args.sql)


if __name__ == "__main__":
    sys.exit(main())
