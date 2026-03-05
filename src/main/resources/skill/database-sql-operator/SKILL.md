---
name: database-sql-operator
description: 数据库SQL操作技能 - 指导Agent连接数据库、执行SQL查询、增删改查操作、事务管理和安全规范。支持MySQL、PostgreSQL、SQLite等主流数据库。
---

# Database SQL Operator

Agent操纵数据库的完整工作流程。执行任何数据库操作前，必须遵循本指南。

## 工作流程总览

```
1. 确认数据库类型和连接信息
2. 建立连接并验证
3. 分析操作需求（查询 / 写入 / DDL）
4. 安全检查（高风险操作需确认）
5. 执行SQL语句
6. 验证结果
7. 关闭连接
```

---

## Phase 1: 确认数据库类型

询问用户（若未提供）：

- **数据库类型**：MySQL / PostgreSQL / SQLite / SQLServer / Oracle
- **连接方式**：本地 / 远程 / 容器（Docker）
- **连接信息**：host、port、database、user、password
- **操作类型**：只读查询 / 数据写入 / 结构修改

---

## Phase 2: 建立连接

### MySQL 连接
```bash
# 命令行连接
mysql -h <host> -P <port> -u <user> -p<password> <database>

# 测试连接是否成功
mysql -h localhost -u root -p -e "SELECT 1;"
```

### PostgreSQL 连接
```bash
# 命令行连接
psql -h <host> -p <port> -U <user> -d <database>

# 测试连接
psql -h localhost -U postgres -c "SELECT version();"
```

### SQLite 连接
```bash
sqlite3 /path/to/database.db
```

### Python 连接（通用推荐方式）
```python
# MySQL
import pymysql
conn = pymysql.connect(
    host='localhost', port=3306,
    user='root', password='password',
    database='mydb', charset='utf8mb4'
)

# PostgreSQL
import psycopg2
conn = psycopg2.connect(
    host='localhost', port=5432,
    user='postgres', password='password',
    dbname='mydb'
)

# SQLite
import sqlite3
conn = sqlite3.connect('/path/to/database.db')

cursor = conn.cursor()
```

**连接验证**：
```python
# 连接后必须验证
cursor.execute("SELECT 1")
print("连接成功:", cursor.fetchone())
```

---

## Phase 3: 查询操作（SELECT）

### 基本查询
```sql
-- 查询所有数据
SELECT * FROM table_name;

-- 条件查询（使用参数化防止注入）
SELECT id, name, email FROM users WHERE id = %s;

-- 分页查询
SELECT * FROM orders ORDER BY created_at DESC LIMIT 10 OFFSET 0;

-- 聚合查询
SELECT department, COUNT(*) AS count, AVG(salary) AS avg_salary
FROM employees
GROUP BY department
HAVING COUNT(*) > 5;

-- 多表JOIN
SELECT u.id, u.name, o.order_no, o.total_amount
FROM users u
LEFT JOIN orders o ON u.id = o.user_id
WHERE o.status = 'paid';
```

### Python执行查询
```python
# 参数化查询（必须使用，防止SQL注入）
sql = "SELECT * FROM users WHERE id = %s AND status = %s"
cursor.execute(sql, (user_id, 'active'))

# 获取结果
rows = cursor.fetchall()          # 获取所有行
row = cursor.fetchone()           # 获取一行
rows = cursor.fetchmany(size=10)  # 获取指定数量

# 以字典格式获取（MySQL）
cursor = conn.cursor(pymysql.cursors.DictCursor)
```

---

## Phase 4: 写入操作（INSERT / UPDATE / DELETE）

> **警告**：写入操作必须使用事务，失败时回滚。

### INSERT
```sql
-- 单行插入
INSERT INTO users (name, email, created_at)
VALUES ('张三', 'zhangsan@example.com', NOW());

-- 批量插入
INSERT INTO products (name, price, stock)
VALUES
  ('商品A', 99.00, 100),
  ('商品B', 199.00, 50),
  ('商品C', 299.00, 30);

-- 忽略重复（MySQL）
INSERT IGNORE INTO tags (name) VALUES ('python');

-- 冲突更新（PostgreSQL）
INSERT INTO inventory (sku, qty)
VALUES ('SKU001', 10)
ON CONFLICT (sku) DO UPDATE SET qty = EXCLUDED.qty + inventory.qty;
```

### UPDATE
```sql
-- 条件更新（必须带WHERE！）
UPDATE users
SET status = 'inactive', updated_at = NOW()
WHERE last_login < DATE_SUB(NOW(), INTERVAL 90 DAY);

-- 多表关联更新（MySQL）
UPDATE orders o
JOIN users u ON o.user_id = u.id
SET o.priority = 'vip'
WHERE u.level = 'gold';
```

### DELETE
```sql
-- 条件删除（必须带WHERE！）
DELETE FROM logs WHERE created_at < DATE_SUB(NOW(), INTERVAL 30 DAY);

-- 软删除（推荐替代硬删除）
UPDATE users SET deleted_at = NOW() WHERE id = %s;
```

### Python事务执行
```python
try:
    conn.begin()  # 开启事务

    cursor.execute(
        "INSERT INTO orders (user_id, total) VALUES (%s, %s)",
        (user_id, total_amount)
    )
    order_id = cursor.lastrowid

    cursor.execute(
        "UPDATE inventory SET stock = stock - %s WHERE product_id = %s",
        (quantity, product_id)
    )

    conn.commit()  # 提交事务
    print(f"订单创建成功，ID: {order_id}")

except Exception as e:
    conn.rollback()  # 回滚事务
    print(f"操作失败，已回滚: {e}")
    raise

finally:
    cursor.close()
    conn.close()
```

---

## Phase 5: DDL结构操作（高风险）

> **高风险操作**：执行前必须向用户确认，建议先备份。

### 创建表
```sql
CREATE TABLE IF NOT EXISTS user_profiles (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT NOT NULL UNIQUE,
    nickname    VARCHAR(100),
    avatar_url  TEXT,
    bio         TEXT,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
);
```

### 修改表结构
```sql
-- 添加列
ALTER TABLE users ADD COLUMN phone VARCHAR(20) AFTER email;

-- 修改列类型
ALTER TABLE products MODIFY COLUMN price DECIMAL(10, 2) NOT NULL;

-- 添加索引
CREATE INDEX idx_created_at ON orders (created_at);

-- 删除列（不可逆！）
ALTER TABLE users DROP COLUMN legacy_field;
```

### 查看表结构
```sql
-- MySQL
DESCRIBE users;
SHOW CREATE TABLE orders;
SHOW INDEX FROM users;

-- PostgreSQL
\d users
SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'users';
```

---

## Phase 6: 安全规范（必须遵守）

### 防SQL注入（最关键）
```python
# 错误示例（绝对禁止）
sql = f"SELECT * FROM users WHERE name = '{user_input}'"  # 危险！

# 正确示例（必须使用参数化查询）
sql = "SELECT * FROM users WHERE name = %s"
cursor.execute(sql, (user_input,))
```

### 高风险操作检查清单
执行以下操作前，必须确认：
- [ ] UPDATE/DELETE 是否有 WHERE 条件
- [ ] DDL操作是否已备份数据
- [ ] 操作是否在正确的数据库（生产/测试）
- [ ] 是否有回滚方案

### 权限最小化原则
```sql
-- 为应用创建只读账号
CREATE USER 'app_readonly'@'%' IDENTIFIED BY 'strong_password';
GRANT SELECT ON mydb.* TO 'app_readonly'@'%';

-- 为应用创建写入账号（不给DDL权限）
CREATE USER 'app_writer'@'%' IDENTIFIED BY 'strong_password';
GRANT SELECT, INSERT, UPDATE, DELETE ON mydb.* TO 'app_writer'@'%';
```

---

## Phase 7: 常用诊断命令

```sql
-- 查看所有数据库
SHOW DATABASES;

-- 查看所有表
SHOW TABLES;

-- 查看正在执行的查询
SHOW PROCESSLIST;                     -- MySQL
SELECT * FROM pg_stat_activity;      -- PostgreSQL

-- 查看表数据量
SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = 'mydb'
ORDER BY table_rows DESC;

-- 查询执行计划（优化性能）
EXPLAIN SELECT * FROM orders WHERE user_id = 1;
EXPLAIN ANALYZE SELECT * FROM orders WHERE user_id = 1;  -- PostgreSQL
```

---

## 错误处理

| 错误类型 | 原因 | 解决方案 |
|---------|------|---------|
| `Connection refused` | 数据库未启动或端口错误 | 检查服务状态和端口 |
| `Access denied` | 账号密码错误或权限不足 | 确认凭证和GRANT权限 |
| `Table doesn't exist` | 表名错误或数据库未选择 | 检查USE database |
| `Duplicate entry` | 主键/唯一键冲突 | 使用INSERT IGNORE或ON CONFLICT |
| `Deadlock found` | 事务死锁 | 重试机制 + 检查事务顺序 |
| `Lock wait timeout` | 锁等待超时 | 减少事务范围，检查长事务 |

详细SQL语法参考：`references/sql-syntax.md`
数据库连接脚本：`scripts/db_connect.py`
