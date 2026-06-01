# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## 项目概述

这是一个电商秒杀系统，前后端分离架构。后端基于 Spring Boot 3.4.5 (Java 17)，前端基于 Vue 3 + Vite。核心功能包括用户认证、商品管理、购物车、订单系统、支付宝支付集成，以及高并发秒杀模块。

## 常用命令

### 后端

```bash
# 构建（跳过测试）
./mvnw clean package -DskipTests

# 运行
./mvnw spring-boot:run

# 运行测试
./mvnw test

# 运行单个测试类
./mvnw test -Dtest=MiaoshaTest
```

### 前端

```bash
cd frontend
npm install
npm run dev    # 开发服务器 (Vite, 端口 5173)
npm run build  # 生产构建
```

## 技术栈

- **后端**: Spring Boot 3.4.5, MyBatis Plus 3.5.15, Redis (Lettuce), RocketMQ 2.3.0, MySQL 8.0.26, MinIO 8.6.0, Alipay SDK, Hutool, Lombok
- **前端**: Vue 3.5, Vue Router 4.6, Element Plus 2.13, Axios, Sass, Vite 7.2

## 架构

### 后端分层

```
controller/    → REST API 控制器
service/       → 业务接口
service/Imp/   → 业务实现（注意目录名是 Imp 不是 Impl）
dao/           → MyBatis Plus Mapper 接口
domain/pojo/   → 数据库实体（Lombok 注解）
domain/Dto/    → 请求 DTO
domain/vo/     → 响应 VO
domain/enums/  → 业务枚举
common/        → 通用类（R 统一响应、UserContext ThreadLocal、ErrorInfo 错误码）
config/        → 配置类（Redis、RocketMQ、线程池、MyBatis Plus、支付宝、WebMVC）
consumer/      → RocketMQ 消费者
scheduler/     → 定时任务
Inceptor/      → 登录拦截器（注意拼写是 Inceptor）
utils/         → 工具类（MinIO）
```

### 秒杀核心流程

这是系统最关键的模块，采用三层防护设计：

1. **Redis Lua 脚本**（`src/main/resources/scripts/seckill.lua`）— 原子操作检查库存 + 防重复 + 扣减库存
2. **RocketMQ 异步下单** — 可靠消息方案（本地消息表 `SeckillMessage` + 消息补偿重发）
3. **MySQL 条件扣减** — `stock > 0` 条件更新，事务保证

消息流向：`SeckillProductServiceImp` → `RocketMQMessageService`(可靠发送) → `MiaoShaOrderConsumer`(消费创建订单)

### 定时任务

| 任务类 | 功能 |
|--------|------|
| `MiaoShaTask` | 秒杀商品预热到 Redis、状态更新、库存同步、过期缓存清理 |
| `RecoveryStockTask` | 每天凌晨2点恢复秒杀剩余库存到原商品 |
| `SeckillStockReconciliationTask` | 每2分钟对账补偿孤立扣减 |
| `SeckillMessageCompensationTask` | 每30秒重发失败消息 |

所有定时任务使用 Redis 分布式锁（`setIfAbsent` + Lua 脚本释放）防止多实例重复执行。

### RocketMQ Topics

| Topic | 消费者 | 用途 |
|-------|--------|------|
| `seckill-topic` | `MiaoShaOrderConsumer` | 秒杀订单异步创建 |
| `order-timeout-topic` | `OrderTimeoutConsumer` | 订单超时取消恢复库存 |

### Redis 缓存键前缀

- `login:token:{token}` — 登录令牌
- `seckill:stock:{id}` — 秒杀库存
- `seckill:users:{id}` — 已购用户 Set
- `seckill:pending:{id}` — 待处理队列
- `seckill:order:{userId}:{productId}` — 订单号缓存
- `seckillProduct:{id}` / `seckillProduct:list` — 秒杀商品缓存

### 认证机制

`LoginInceptor` 拦截所有请求，通过 Redis 中的 token 验证身份，白名单包括 `/auth/login`、`/auth/register`、`/alipay/*`。用户信息存储在 `UserContext`（ThreadLocal）。

### 前端结构

```
frontend/src/
├── views/           → 页面（Login, Register, Product, UserProduct, SeckillDetail, UserOrder 等）
├── components/      → 公共组件（AdminHeader, AppHeader, AppSidebar）
├── router/index.js  → 路由配置（含导航守卫，按角色 admin/user 分区）
├── utils/request.js → Axios 封装
└── assets/          → 静态资源
```

Vite 开发代理将 `/api/*` 转发到 `http://localhost:8080`，部分秒杀接口保留 `/api` 前缀，其余接口去掉前缀。

### 订单状态枚举（OrderStatusEnum）

- 10=待支付, 20=已支付, 30=已发货, 40=已收货, 50=已取消

## 外部依赖

系统运行需要：MySQL、Redis（端口 26739）、RocketMQ（NameServer 43.139.17.130:9876）、MinIO（文件存储）。配置见 `src/main/resources/application.yml`。

## 语言规范

所有回答和代码注释使用中文。
