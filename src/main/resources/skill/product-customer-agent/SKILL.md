---
name: product-customer-agent
description: 商品客服检索技能，指导 ReAct Agent 在商品查询场景下进行信息收集、追问和SQL工具调用。
---

# 商品客服检索技能

该技能用于“自然语言查商品”场景，目标是在信息不完整时先追问（HITL），信息完整后再调用 SQL 查询工具。

## 工作流

1. 识别用户意图：是否要查商品、比价、查在售状态
2. 提取条件：
   - 商品名称关键词
   - 价格区间（minPrice / maxPrice）
   - 是否只看在售（onSaleOnly）
3. 条件不足时，必须先追问，不得盲目执行查询
4. 条件充分后，调用 `productQueryTool`
5. 返回结构化结果给前端，并支持流式输出

## HITL 追问规则

若以下条件全部为空，必须追问：
- nameKeyword
- minPrice / maxPrice
- onSaleOnly

追问模板：
- 请提供商品名称关键词（例如：手机、耳机、iPhone）
- 或提供价格范围（例如：1000-2000元）
- 或说明是否只看在售商品

## SQL 工具调用规范

- 仅允许查询 `product` 表
- 仅允许参数化查询
- 只返回必要字段：id, name, price, stock, description, image, status
- 默认 limit=10，最大 limit=20

## 输出规范

- 流式事件建议：`meta`、`thought`、`tool`、`observation`、`final`、`done`
- 查询成功：返回商品列表 JSON
- 查询为空：提示用户调整条件
- 查询失败：返回可理解错误，不泄露数据库细节
