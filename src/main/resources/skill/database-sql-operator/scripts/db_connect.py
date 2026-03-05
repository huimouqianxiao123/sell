#!/usr/bin/env python3
"""
数据库连接和SQL执行工具
支持 MySQL、PostgreSQL、SQLite

用法:
    python db_connect.py --type mysql --host localhost --port 3306 \
        --user root --password pass --database mydb \
        --query "SELECT * FROM users LIMIT 10"

    python db_connect.py --type sqlite --database /path/to/db.sqlite \
        --query "SELECT * FROM users"
"""

import argparse
import sys
import json
from contextlib import contextmanager


def get_mysql_connection(host, port, user, password, database):
    """建立MySQL连接"""
    try:
        import pymysql
        conn = pymysql.connect(
            host=host,
            port=int(port),
            user=user,
            password=password,
            database=database,
            charset='utf8mb4',
            cursorclass=pymysql.cursors.DictCursor
        )
        return conn
    except ImportError:
        print("错误: 请安装 pymysql: pip install pymysql", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"MySQL连接失败: {e}", file=sys.stderr)
        sys.exit(1)


def get_postgres_connection(host, port, user, password, database):
    """建立PostgreSQL连接"""
    try:
        import psycopg2
        import psycopg2.extras
        conn = psycopg2.connect(
            host=host,
            port=int(port),
            user=user,
            password=password,
            dbname=database
        )
        return conn
    except ImportError:
        print("错误: 请安装 psycopg2: pip install psycopg2-binary", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"PostgreSQL连接失败: {e}", file=sys.stderr)
        sys.exit(1)


def get_sqlite_connection(database):
    """建立SQLite连接"""
    import sqlite3
    try:
        conn = sqlite3.connect(database)
        conn.row_factory = sqlite3.Row
        return conn
    except Exception as e:
        print(f"SQLite连接失败: {e}", file=sys.stderr)
        sys.exit(1)


@contextmanager
def get_connection(args):
    """获取数据库连接（上下文管理器）"""
    conn = None
    try:
        db_type = args.type.lower()
        if db_type == 'mysql':
            conn = get_mysql_connection(
                args.host, args.port, args.user, args.password, args.database
            )
        elif db_type in ('postgres', 'postgresql', 'pg'):
            conn = get_postgres_connection(
                args.host, args.port, args.user, args.password, args.database
            )
        elif db_type == 'sqlite':
            conn = get_sqlite_connection(args.database)
        else:
            print(f"不支持的数据库类型: {args.type}", file=sys.stderr)
            sys.exit(1)

        yield conn
    finally:
        if conn:
            conn.close()


def execute_query(conn, sql, params=None, db_type='mysql'):
    """
    执行SQL语句
    - SELECT：返回结果列表
    - INSERT/UPDATE/DELETE：返回影响行数
    - DDL：返回成功状态
    """
    is_sqlite = db_type == 'sqlite'

    if is_sqlite:
        import sqlite3
        cursor = conn.cursor()
    else:
        cursor = conn.cursor()

    sql = sql.strip()
    sql_upper = sql.upper().lstrip()

    is_select = sql_upper.startswith('SELECT') or sql_upper.startswith('SHOW') or \
                sql_upper.startswith('DESCRIBE') or sql_upper.startswith('EXPLAIN')

    try:
        if params:
            cursor.execute(sql, params)
        else:
            cursor.execute(sql)

        if is_select:
            if is_sqlite:
                columns = [d[0] for d in cursor.description]
                rows = [dict(zip(columns, row)) for row in cursor.fetchall()]
            else:
                rows = cursor.fetchall()
                if rows and not isinstance(rows[0], dict):
                    # PostgreSQL返回元组，需要转换
                    import psycopg2.extras
                    columns = [d[0] for d in cursor.description]
                    rows = [dict(zip(columns, row)) for row in rows]

            return {
                'type': 'select',
                'rows': rows,
                'count': len(rows)
            }
        else:
            conn.commit()
            return {
                'type': 'write',
                'affected_rows': cursor.rowcount,
                'last_insert_id': getattr(cursor, 'lastrowid', None)
            }

    except Exception as e:
        conn.rollback()
        raise RuntimeError(f"SQL执行失败: {e}") from e
    finally:
        cursor.close()


def format_output(result, output_format='table'):
    """格式化输出结果"""
    if result['type'] == 'write':
        print(f"执行成功")
        print(f"  影响行数: {result['affected_rows']}")
        if result.get('last_insert_id'):
            print(f"  新增ID: {result['last_insert_id']}")
        return

    rows = result['rows']
    print(f"查询结果: {result['count']} 条记录\n")

    if not rows:
        print("（无数据）")
        return

    if output_format == 'json':
        print(json.dumps(rows, ensure_ascii=False, indent=2, default=str))
        return

    # 表格格式输出
    columns = list(rows[0].keys())
    col_widths = {col: len(str(col)) for col in columns}
    for row in rows:
        for col in columns:
            col_widths[col] = max(col_widths[col], len(str(row.get(col, ''))))

    # 表头
    header = " | ".join(str(col).ljust(col_widths[col]) for col in columns)
    separator = "-+-".join("-" * col_widths[col] for col in columns)
    print(header)
    print(separator)

    # 数据行
    for row in rows:
        line = " | ".join(str(row.get(col, '')).ljust(col_widths[col]) for col in columns)
        print(line)


def main():
    parser = argparse.ArgumentParser(description='数据库SQL执行工具')
    parser.add_argument('--type', default='mysql',
                        choices=['mysql', 'postgres', 'postgresql', 'pg', 'sqlite'],
                        help='数据库类型')
    parser.add_argument('--host', default='localhost', help='数据库主机')
    parser.add_argument('--port', default='3306', help='数据库端口')
    parser.add_argument('--user', '-u', help='用户名')
    parser.add_argument('--password', '-p', default='', help='密码')
    parser.add_argument('--database', '-d', required=True, help='数据库名或SQLite文件路径')
    parser.add_argument('--query', '-q', help='SQL语句')
    parser.add_argument('--file', '-f', help='SQL文件路径')
    parser.add_argument('--format', default='table', choices=['table', 'json'],
                        help='输出格式')

    args = parser.parse_args()

    # 获取SQL语句
    if args.file:
        with open(args.file, 'r', encoding='utf-8') as f:
            sql = f.read()
    elif args.query:
        sql = args.query
    else:
        # 从stdin读取
        print("请输入SQL语句（Ctrl+D结束）:")
        sql = sys.stdin.read()

    if not sql.strip():
        print("错误: 未提供SQL语句", file=sys.stderr)
        sys.exit(1)

    # 执行查询
    with get_connection(args) as conn:
        print(f"已连接到 {args.type.upper()} 数据库: {args.database}")
        print(f"执行SQL: {sql[:100]}{'...' if len(sql) > 100 else ''}\n")

        try:
            result = execute_query(conn, sql, db_type=args.type)
            format_output(result, args.format)
        except RuntimeError as e:
            print(f"错误: {e}", file=sys.stderr)
            sys.exit(1)


if __name__ == '__main__':
    main()
