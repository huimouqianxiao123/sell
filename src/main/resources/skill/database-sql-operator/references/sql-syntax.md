# SQL语法速查参考

## SELECT 完整语法

```sql
SELECT [DISTINCT] 列名1, 列名2, 聚合函数
FROM 表名
[JOIN 表名2 ON 条件]
[WHERE 过滤条件]
[GROUP BY 分组列]
[HAVING 分组后过滤]
[ORDER BY 排序列 ASC|DESC]
[LIMIT 数量 OFFSET 偏移]
```

## 常用聚合函数

| 函数 | 说明 |
|------|------|
| `COUNT(*)` | 行数 |
| `COUNT(DISTINCT col)` | 去重行数 |
| `SUM(col)` | 求和 |
| `AVG(col)` | 平均值 |
| `MAX(col)` | 最大值 |
| `MIN(col)` | 最小值 |
| `GROUP_CONCAT(col)` | 拼接字符串（MySQL） |
| `STRING_AGG(col, ',')` | 拼接字符串（PostgreSQL） |

## JOIN类型

```sql
-- INNER JOIN：两表都有匹配的行
SELECT * FROM a INNER JOIN b ON a.id = b.a_id;

-- LEFT JOIN：保留左表所有行
SELECT * FROM a LEFT JOIN b ON a.id = b.a_id;

-- RIGHT JOIN：保留右表所有行
SELECT * FROM a RIGHT JOIN b ON a.id = b.a_id;

-- FULL OUTER JOIN：保留两表所有行（PostgreSQL支持，MySQL需UNION模拟）
SELECT * FROM a FULL OUTER JOIN b ON a.id = b.a_id;

-- CROSS JOIN：笛卡尔积
SELECT * FROM a CROSS JOIN b;
```

## 子查询

```sql
-- WHERE子查询
SELECT * FROM users WHERE id IN (SELECT user_id FROM orders WHERE total > 1000);

-- FROM子查询（派生表）
SELECT dept, avg_sal FROM (
    SELECT department AS dept, AVG(salary) AS avg_sal
    FROM employees GROUP BY department
) AS dept_stats
WHERE avg_sal > 5000;

-- EXISTS子查询
SELECT * FROM users u
WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND o.status = 'paid');

-- 相关子查询
SELECT name, salary,
    (SELECT AVG(salary) FROM employees WHERE department = e.department) AS dept_avg
FROM employees e;
```

## 窗口函数（MySQL 8+, PostgreSQL）

```sql
-- 排名
SELECT name, salary,
    ROW_NUMBER() OVER (PARTITION BY dept ORDER BY salary DESC) AS rank
FROM employees;

-- 累计求和
SELECT date, amount,
    SUM(amount) OVER (ORDER BY date ROWS UNBOUNDED PRECEDING) AS cumulative
FROM sales;

-- 前后行比较
SELECT date, revenue,
    LAG(revenue, 1) OVER (ORDER BY date) AS prev_revenue,
    LEAD(revenue, 1) OVER (ORDER BY date) AS next_revenue
FROM monthly_revenue;
```

## 字符串函数

```sql
-- MySQL
CONCAT(s1, s2)          -- 拼接
SUBSTRING(s, pos, len)  -- 截取
REPLACE(s, from, to)    -- 替换
TRIM(s)                 -- 去除首尾空格
UPPER(s) / LOWER(s)    -- 大小写转换
LENGTH(s)               -- 字节长度
CHAR_LENGTH(s)          -- 字符长度
REGEXP_REPLACE(s, pattern, replace)  -- 正则替换（MySQL 8+）

-- PostgreSQL
s || s2                 -- 拼接（用||）
SUBSTRING(s FROM pos FOR len)
REGEXP_REPLACE(s, pattern, replacement, flags)
```

## 日期函数

```sql
-- MySQL
NOW()                   -- 当前时间戳
CURDATE()               -- 当前日期
DATE_ADD(date, INTERVAL n DAY/MONTH/YEAR)
DATE_SUB(date, INTERVAL n DAY)
DATE_FORMAT(date, '%Y-%m-%d')
DATEDIFF(date1, date2)  -- 天数差
UNIX_TIMESTAMP(date)    -- 转时间戳
FROM_UNIXTIME(timestamp)-- 时间戳转日期

-- PostgreSQL
NOW()
CURRENT_DATE
date + INTERVAL '1 day'
TO_CHAR(date, 'YYYY-MM-DD')
EXTRACT(YEAR FROM date)
AGE(date1, date2)
```

## 条件表达式

```sql
-- CASE WHEN
SELECT name,
    CASE status
        WHEN 1 THEN '活跃'
        WHEN 0 THEN '禁用'
        ELSE '未知'
    END AS status_label
FROM users;

-- CASE搜索形式
SELECT name, salary,
    CASE
        WHEN salary < 3000 THEN '初级'
        WHEN salary BETWEEN 3000 AND 8000 THEN '中级'
        ELSE '高级'
    END AS level
FROM employees;

-- COALESCE（返回第一个非NULL值）
SELECT COALESCE(nickname, name, '匿名') AS display_name FROM users;

-- NULLIF（两值相等时返回NULL）
SELECT NULLIF(quantity, 0) AS safe_qty FROM inventory;
```

## 事务隔离级别

```sql
-- 查看当前隔离级别
SELECT @@transaction_isolation;  -- MySQL
SHOW TRANSACTION ISOLATION LEVEL;  -- PostgreSQL

-- 设置隔离级别
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
-- READ UNCOMMITTED：读未提交（脏读）
-- READ COMMITTED：读已提交（默认PostgreSQL）
-- REPEATABLE READ：可重复读（默认MySQL）
-- SERIALIZABLE：串行化（最严格）
```

## 索引管理

```sql
-- 创建索引
CREATE INDEX idx_name ON table_name (column);
CREATE UNIQUE INDEX idx_email ON users (email);
CREATE INDEX idx_compound ON orders (user_id, status, created_at);

-- 查看索引
SHOW INDEX FROM table_name;  -- MySQL
\di table_name               -- PostgreSQL

-- 删除索引
DROP INDEX idx_name ON table_name;  -- MySQL
DROP INDEX idx_name;                -- PostgreSQL

-- 强制使用指定索引（MySQL）
SELECT * FROM orders USE INDEX (idx_user_id) WHERE user_id = 1;
```

## 备份与恢复

```bash
# MySQL 备份
mysqldump -u root -p mydb > backup.sql
mysqldump -u root -p mydb table1 table2 > partial_backup.sql

# MySQL 恢复
mysql -u root -p mydb < backup.sql

# PostgreSQL 备份
pg_dump -U postgres mydb > backup.sql
pg_dump -U postgres -F c mydb > backup.dump  # 自定义格式

# PostgreSQL 恢复
psql -U postgres mydb < backup.sql
pg_restore -U postgres -d mydb backup.dump
```
